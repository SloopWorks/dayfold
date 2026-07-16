package com.sloopworks.dayfold.client

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.sloopworks.dayfold.client.theme.DayfoldTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// AUTH-S5 Slice B — full-flow UI e2e via runComposeUiTest (JVM/desktop, headless,
// no Espresso/InputManager). Drives the REAL route gate + Dayfold screens through
// sign-in → create-family → feed → account → sign-out, with the callbacks
// dispatching the actions the AuthEngine would (engine logic is covered by
// AuthEngineTest). Mirror of the instrumented AuthFlowE2ETest, which targets the
// emulator but is blocked by the API-37 espresso incompatibility (see
// androidApp/build.gradle.kts).
//
// waitUntil between steps: redux-kotlin-compose `selectorState` pushes state via
// the store subscription, which the skiko test harness doesn't auto-sync — so we
// wait for each screen's marker to appear after a route-changing dispatch.
@OptIn(ExperimentalTestApi::class)
class AuthFlowUiTest {
  @Test fun busyProviderButtonsBlockDoubleSubmit() = runComposeUiTest {
    // #223 loading states: while one provider sign-in is in flight (pendingProvider set), ALL
    // provider buttons disable (AuthButton effectiveEnabled = enabled && !busy) so a stray
    // second tap can't fire a concurrent auth flow. The signInBusy snapshot only proves the
    // visual (spinner); this pins the double-submit GUARD behaviorally.
    setContent { DayfoldTheme { SignInScreen(pendingProvider = "google") } }
    onNodeWithText("Continue with Google").assertIsNotEnabled()   // the in-flight provider — not re-tappable
    onNodeWithText("Continue with Apple").assertIsNotEnabled()    // a different provider — also blocked
  }

  @Test fun createFamilyDisabledWhileBusy() = runComposeUiTest {
    // A double-tap on "Create family" would create TWO families (a data-integrity bug). While
    // the create is in flight (busy, via CreateFamilyRequested→authBusy) the button disables —
    // initialName supplies a VALID name so the guard under test is `busy`, not the blank-name
    // check (enabled = !busy && name.isNotBlank()). The happy-path test only covers non-busy.
    setContent { DayfoldTheme { CreateFamilyScreen(busy = true, initialName = "The Smiths") } }
    onNodeWithText("Creating…").assertIsNotEnabled()
  }

  @Test fun joinDisabledWhileBusy() = runComposeUiTest {
    // The onboarding twin of create-family: double-tapping "Join" would dispatch two joins → a
    // duplicate membership request. While the join is in flight (joinBusy) the button disables —
    // type a code so the guard under test is `busy`, not the blank-token check
    // (enabled = !busy && token.isNotBlank()). Only the snapshots + reducer covered joinBusy.
    setContent { DayfoldTheme { JoinInviteScreen(AppState(session = SessionState(joinBusy = true), navigation = NavigationState(route = Route.JoinInvite))) } }
    onNode(hasSetTextAction()).performTextInput("FAMILY-INVITE-7K2P")
    onNodeWithText("Join").assertIsNotEnabled()
  }

  @Test fun signIn_createFamily_feed_account_signOut() = runComposeUiTest {
    val store = createTestAppStore(AppState(navigation = NavigationState(route = Route.SignIn)), debug = false)
    setContent {
      DayfoldTheme {
        TestFeedApp(
          store,
          onSignIn = {
            store.dispatch(SignInSucceeded(Session("a", "r")))
            store.dispatch(MembershipsLoaded(emptyList()))      // no family → CreateFamily
          },
          onCreateFamily = { name -> store.dispatch(FamilyCreated("fam1", name)) },
          onSignOut = { store.dispatch(SignedOut) },
        )
      }
    }
    fun seen(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    // 1) Sign in
    onNodeWithText("Continue with Google").assertIsDisplayed().performClick()

    // 2) Onboarding → create the family
    waitUntil(timeoutMillis = 5_000L) { seen("Name your family") }
    onNode(hasSetTextAction()).performTextInput("The Jacksons")
    onNodeWithText("Create family").performClick()

    // 3) Feed (empty → null state); open the account overlay via the monogram
    waitUntil(timeoutMillis = 5_000L) { seen("Your family space is ready") }
    onNodeWithContentDescription("Account").performClick()

    // 4) Account → sign out → confirm dialog → confirm
    waitUntil(timeoutMillis = 5_000L) { seen("Sign out") }
    onNodeWithText("Sign out").performClick()                 // opens the confirm dialog
    waitUntil(timeoutMillis = 5_000L) { seen("Sign out?") }
    onNodeWithTag("confirm-signout").performClick()

    // 5) Back at sign-in — the loop is closed
    waitUntil(timeoutMillis = 5_000L) { seen("Continue with Google") }
    onNodeWithText("Continue with Google").assertIsDisplayed()
  }

  @Test fun signIn_joinByInvite_waitsForApproval() = runComposeUiTest {
    val store = createTestAppStore(AppState(navigation = NavigationState(route = Route.SignIn)), debug = false)
    setContent {
      DayfoldTheme {
        TestFeedApp(
          store,
          onSignIn = {
            store.dispatch(SignInSucceeded(Session("a", "r")))
            store.dispatch(MembershipsLoaded(emptyList()))      // signed in, no family → CreateFamily
          },
          onRedeemInvite = { token ->
            store.dispatch(RedeemRequested(token))
            store.dispatch(InviteRedeemed("The Riveras"))        // success → pending, waiting for approval
          },
        )
      }
    }
    fun seen(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    // sign in → onboarding
    onNodeWithText("Continue with Google").performClick()
    waitUntil(timeoutMillis = 5_000L) { seen("Name your family") }

    // take the invitee path → paste a token → join
    onNodeWithText("Have an invite? Join a family").performClick()
    waitUntil(timeoutMillis = 5_000L) { seen("Join a family") }
    onNode(hasSetTextAction()).performTextInput("INVITE-TOKEN-123")
    onNodeWithText("Join").performClick()

    // waiting-for-approval state (every invite is owner-approved)
    waitUntil(timeoutMillis = 5_000L) { seen("Almost in") }

    // dismiss → back to the onboarding gate
    onNodeWithText("Done").performClick()
    waitUntil(timeoutMillis = 5_000L) { seen("Name your family") }
  }

  @Test fun owner_approvesPendingMember() = runComposeUiTest {
    val store = createTestAppStore(AppState(navigation = NavigationState(route = Route.SignIn)), debug = false)
    setContent {
      DayfoldTheme {
        TestFeedApp(
          store,
          onSignIn = {
            store.dispatch(SignInSucceeded(Session("a", "r")))
            store.dispatch(MembershipsLoaded(listOf(FamilyMembership("fam1", "The Jacksons", role = "owner", status = "active"))))
          },
          onLoadApprovals = { store.dispatch(ApprovalsLoaded(listOf(PendingMember("u9", "Sam Rivera")))) },
          onLoadMembers = {
            store.dispatch(RosterLoaded(listOf(
              FamilyMember("u1", "Pat Jackson", role = "owner"), FamilyMember("u2", "Maya Jackson", role = "adult"),
            )))
          },
          onApproveMember = { uid -> store.dispatch(MemberResolved(uid)) },
          onRemoveMember = { uid -> store.dispatch(MemberRemoved(uid)) },
        )
      }
    }
    fun seen(t: String) = onAllNodesWithText(t).fetchSemanticsNodes().isNotEmpty()

    // owner with an active family → Feed
    onNodeWithText("Continue with Google").performClick()
    waitUntil(timeoutMillis = 5_000L) { seen("Today") }
    // Feed → account → members
    onNodeWithContentDescription("Account").performClick()
    waitUntil(timeoutMillis = 5_000L) { seen("Members & approvals") }
    onNodeWithText("Members & approvals").performClick()
    // pending queue + active roster both load
    waitUntil(timeoutMillis = 5_000L) { seen("Sam Rivera") && seen("Maya Jackson") }
    // approve the pending member → drops from the queue
    onNodeWithTag("approve-u9").performClick()
    waitUntil(timeoutMillis = 5_000L) { onAllNodesWithText("Sam Rivera").fetchSemanticsNodes().isEmpty() }
    // remove an adult member → drops from the roster
    onNodeWithTag("remove-u2").performClick()
    waitUntil(timeoutMillis = 5_000L) { onAllNodesWithText("Maya Jackson").fetchSemanticsNodes().isEmpty() }
  }

  @Test fun owner_opensInviteAndMintsQr() = runComposeUiTest {
    val store = createTestAppStore(AppState(navigation = NavigationState(route = Route.SignIn)), debug = false)
    setContent {
      DayfoldTheme {
        TestFeedApp(
          store,
          onSignIn = {
            store.dispatch(SignInSucceeded(Session("a", "r")))
            store.dispatch(MembershipsLoaded(listOf(FamilyMembership("fam1", "The Jacksons", role = "owner", status = "active"))))
          },
          onLoadApprovals = { store.dispatch(ApprovalsLoaded(emptyList(), emptyList())) },
          // auto-mint on Invite entry fires this → InviteMinted → QR renders
          onMintInvite = { mode -> store.dispatch(InviteMinted(MintedInvite("i", "TOK", "https://x/invite/TOK", "adult", mode, "2099-01-01T00:00:00Z"))) },
        )
      }
    }
    fun seen(t: String) = onAllNodesWithText(t).fetchSemanticsNodes().isNotEmpty()
    fun seenCd(t: String) = onAllNodesWithContentDescription(t).fetchSemanticsNodes().isNotEmpty()

    onNodeWithText("Continue with Google").performClick()
    waitUntil(timeoutMillis = 5_000L) { seen("Today") }
    onNodeWithContentDescription("Account").performClick()
    waitUntil(timeoutMillis = 5_000L) { seen("Members & approvals") }
    onNodeWithText("Members & approvals").performClick()
    // owner-only invite entry (content description — the label text is cleared for a11y)
    waitUntil(timeoutMillis = 5_000L) { seenCd("Invite a member") }
    onNodeWithContentDescription("Invite a member").performClick()
    waitUntil(timeoutMillis = 5_000L) { store.state.route == Route.Invite }
    // auto-mint → InviteMinted → the QR renders
    waitUntil(timeoutMillis = 5_000L) { seenCd("Invite QR code") }
  }

  @Test fun account_revokesConnectedDevice() = runComposeUiTest {
    val store = createTestAppStore(AppState(navigation = NavigationState(route = Route.SignIn)), debug = false)
    setContent {
      DayfoldTheme {
        TestFeedApp(
          store,
          onSignIn = {
            store.dispatch(SignInSucceeded(Session("a", "r")))
            store.dispatch(MembershipsLoaded(listOf(FamilyMembership("fam1", "The Jacksons", role = "owner", status = "active"))))
          },
          onLoadDevices = {
            store.dispatch(DevicesLoaded(listOf(
              DeviceCredential("c1", kind = "app", label = "iPhone", current = true),
              DeviceCredential("c2", kind = "cli", label = "claude-code"),
            )))
          },
          onRevokeDevice = { id -> store.dispatch(DeviceRevoked(id)) },
        )
      }
    }
    fun seen(t: String) = onAllNodesWithText(t).fetchSemanticsNodes().isNotEmpty()

    onNodeWithText("Continue with Google").performClick()
    waitUntil(timeoutMillis = 5_000L) { seen("Today") }
    onNodeWithContentDescription("Account").performClick()
    waitUntil(timeoutMillis = 5_000L) { seen("Connected devices") }
    onNodeWithText("Connected devices").performClick()
    // list loads; the CLI grant is revocable, the current device is not
    waitUntil(timeoutMillis = 5_000L) { seen("claude-code") }
    onNodeWithTag("revoke-c2").performClick()
    waitUntil(timeoutMillis = 5_000L) { onAllNodesWithText("claude-code").fetchSemanticsNodes().isEmpty() }
  }

  // ── ADR 0011 §7: the datacenter anti-phishing warning is a security invariant.
  // Snapshot tests (AuthScreensSnapshotTest) compare pixels; these pin the BEHAVIOR
  // semantically via the device-datacenter-warning testTag, so a benign restyle
  // won't mask a regression that drops the banner (or shows it for home networks).
  private fun authorizeState(originKind: String) = AppState(
    session = SessionState(
      session = Session("a", "r"),
      families = listOf(FamilyMembership("fam1", "The Jacksons", role = "owner", status = "active")),
      activeFamilyId = "fam1",
    ),
    navigation = NavigationState(route = Route.AuthorizeDevice),
    devices = DeviceState(pendingDevice = PendingDevice("WDJF-7K2P", client = "Dayfold CLI", originKind = originKind)),
  )

  @Test fun datacenterOriginShowsTheAntiPhishingWarning() = runComposeUiTest {
    setContent { DayfoldTheme { AuthorizeDeviceScreen(authorizeState("datacenter")) } }
    onNodeWithTag("device-datacenter-warning").assertIsDisplayed()
  }

  @Test fun residentialOriginHidesTheDatacenterWarning() = runComposeUiTest {
    // a home network is the normal case — no scary banner, so the warning stays meaningful
    setContent { DayfoldTheme { AuthorizeDeviceScreen(authorizeState("residential")) } }
    assertTrue(onAllNodesWithTag("device-datacenter-warning").fetchSemanticsNodes().isEmpty())
  }

  // The approve/deny/cancel wiring is the device-grant security action — a regression
  // that swapped the callbacks, granted to the wrong family, or dropped the handler
  // would be serious. These tags (device-approve/deny/cancel) had no behavioral test.
  @Test fun approveGrantsToTheSelectedOwnerFamily() = runComposeUiTest {
    var approvedFid: String? = null
    setContent { DayfoldTheme { AuthorizeDeviceScreen(authorizeState("residential"), onApprove = { fid, _ -> approvedFid = fid }) } }
    onNodeWithTag("device-approve").performClick()
    assertEquals("fam1", approvedFid)   // grants to the selected owner family, not e.g. the user_code
  }

  @Test fun approveDisabledWhileDeviceBusy() = runComposeUiTest {
    // Authorizing a device GRANTS account access — a double-approve (a second tap while the
    // first request is in flight) must be impossible. canApprove = … && !deviceBusy, so an
    // in-flight approval disables the Authorize button. The approve-wiring test above only
    // covers the non-busy case; this pins the double-grant guard (#223 loading states).
    setContent { DayfoldTheme { AuthorizeDeviceScreen(authorizeState("residential").copy(devices = DeviceState(pendingDevice = PendingDevice("WDJF-7K2P", client = "Dayfold CLI", originKind = "residential"), busy = true))) } }
    onNodeWithTag("device-approve").assertIsNotEnabled()
  }

  @Test fun denyInvokesOnDenyForTheSelectedFamily() = runComposeUiTest {
    var deniedFid: String? = null
    setContent { DayfoldTheme { AuthorizeDeviceScreen(authorizeState("residential"), onDeny = { deniedFid = it }) } }
    onNodeWithTag("device-deny").performClick()
    assertEquals("fam1", deniedFid)
  }

  @Test fun cancelInvokesOnCancel() = runComposeUiTest {
    var cancelled = false
    setContent { DayfoldTheme { AuthorizeDeviceScreen(authorizeState("residential"), onCancel = { cancelled = true }) } }
    onNodeWithTag("device-cancel").performClick()
    assertTrue(cancelled)
  }

  // Multi-owner grant routing: the family selector decides WHICH family the device
  // joins. Granting to the wrong family is a real correctness/security bug, and the
  // selector tags (device-family-selector / device-family-<id>) were orphaned.
  @Test fun switchingTheFamilySelectorRoutesTheGrantToTheChosenFamily() = runComposeUiTest {
    var approvedFid: String? = null
    val twoOwner = AppState(
      session = SessionState(
        session = Session("a", "r"),
        families = listOf(
          FamilyMembership("fam1", "The Jacksons", role = "owner", status = "active"),
          FamilyMembership("fam2", "Lake House", role = "owner", status = "active"),
        ),
        activeFamilyId = "fam1",                                // default target
      ),
      navigation = NavigationState(route = Route.AuthorizeDevice),
      devices = DeviceState(pendingDevice = PendingDevice("WDJF-7K2P", client = "Dayfold CLI", originKind = "residential")),
    )
    setContent { DayfoldTheme { AuthorizeDeviceScreen(twoOwner, onApprove = { fid, _ -> approvedFid = fid }) } }
    onNodeWithTag("device-family-selector").performClick()      // open the picker
    onNodeWithTag("device-family-fam2").performClick()          // choose the OTHER family
    onNodeWithTag("device-approve").performClick()
    assertEquals("fam2", approvedFid)                           // grant routes to the chosen family, not the default
  }

  // Per-hub scope picker (ADR 0029 T4): Full access is the default (approve emits a
  // null scope = blanket grant, back-compat with pre-scope clients); switching to
  // "Only these hubs" requires picking ≥1 hub before Approve is even tappable — the
  // API's 400 on an empty hub list is a backstop, not the UX guard (T3 review note).
  private fun authorizeStateWithHubs(hubs: List<Hub>) = AppState(
    session = SessionState(
      session = Session("a", "r"),
      families = listOf(FamilyMembership("fam1", "The Jacksons", role = "owner", status = "active")),
      activeFamilyId = "fam1",
    ),
    navigation = NavigationState(route = Route.AuthorizeDevice),
    devices = DeviceState(pendingDevice = PendingDevice("WDJF-7K2P", client = "Dayfold CLI", originKind = "residential")),
    hubs = HubState(hubs = hubs),
  )

  @Test fun approveDefaultsToFullAccessScope() = runComposeUiTest {
    var scope: List<String>? = listOf("sentinel")   // a no-op onApprove would leave this non-null, not false-pass
    setContent {
      DayfoldTheme { AuthorizeDeviceScreen(authorizeStateWithHubs(listOf(Hub(id = "H1", title = "College"))), onApprove = { _, s -> scope = s }) }
    }
    onNodeWithTag("device-approve").performClick()
    assertEquals(null, scope)   // Full access, untouched → null (blanket), not emptyList()
  }

  @Test fun approveInHubsModeEmitsTheSelectedHub() = runComposeUiTest {
    var scope: List<String>? = null
    setContent {
      DayfoldTheme { AuthorizeDeviceScreen(authorizeStateWithHubs(listOf(Hub(id = "H1", title = "College"))), onApprove = { _, s -> scope = s }) }
    }
    onNodeWithText("Only these hubs").performClick()
    onNodeWithTag("device-scope-hub-H1").performClick()
    onNodeWithTag("device-approve").performClick()
    assertEquals(listOf("H1"), scope)
  }

  @Test fun approveDisabledInHubsModeWithNoHubSelected() = runComposeUiTest {
    setContent { DayfoldTheme { AuthorizeDeviceScreen(authorizeStateWithHubs(listOf(Hub(id = "H1", title = "College")))) } }
    onNodeWithText("Only these hubs").performClick()
    onNodeWithTag("device-approve").assertIsNotEnabled()
  }

  // Manual code entry (the path the scan escapes lead to). device-code-field +
  // device-continue were orphaned, and normalizeDeviceCode/formatUserCode had no test.
  @Test fun enterCodeNormalizesThenSubmitsTheFormattedCode() = runComposeUiTest {
    var looked: String? = null
    setContent { DayfoldTheme { EnterCodeScreen(AppState(navigation = NavigationState(route = Route.EnterCode)), onLookup = { looked = it }) } }
    onNodeWithTag("device-code-field").performTextInput("wdjf-7k2p")   // lowercase + dash, as a human types it
    onNodeWithTag("device-continue").performClick()
    assertEquals("WDJF-7K2P", looked)                            // uppercased, dash dropped then re-formatted XXXX-XXXX
  }

  @Test fun continueDoesNotSubmitAnIncompleteCode() = runComposeUiTest {
    var looked: String? = null
    setContent { DayfoldTheme { EnterCodeScreen(AppState(navigation = NavigationState(route = Route.EnterCode)), onLookup = { looked = it }) } }
    onNodeWithTag("device-code-field").performTextInput("wd2")   // 3 chars — short
    onNodeWithTag("device-continue").performClick()
    assertEquals(null, looked)                                   // submit() guards on 8 chars; button is disabled too
  }

  // Outcome screens differ by recoverability: an EXPIRED request can be retried,
  // a DENIED one is terminal. Pinning that distinction (+ the outcome-done wiring).
  @Test fun expiredOutcomeOffersRetryAndDone() = runComposeUiTest {
    var retried = false; var done = false
    setContent { DayfoldTheme { DeviceExpiredScreen(onRetry = { retried = true }, onDone = { done = true }) } }
    onNodeWithText("Enter a new code").performClick()
    assertTrue(retried)                                          // timeout is recoverable
    onNodeWithTag("outcome-done").performClick()
    assertTrue(done)
  }

  @Test fun deniedOutcomeIsTerminalDoneOnlyNoRetry() = runComposeUiTest {
    var done = false
    setContent { DayfoldTheme { DeviceDeniedScreen(onDone = { done = true }) } }
    // a denial must NOT offer "Enter a new code" — only expiry does
    assertTrue(onAllNodesWithText("Enter a new code").fetchSemanticsNodes().isEmpty())
    onNodeWithTag("outcome-done").performClick()
    assertTrue(done)
  }
}
