package com.sloopworks.dayfold.client

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
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
  @Test fun signIn_createFamily_feed_account_signOut() = runComposeUiTest {
    val store = createAppStore(AppState(route = Route.SignIn), debug = false)
    setContent {
      DayfoldTheme {
        FeedApp(
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
    val store = createAppStore(AppState(route = Route.SignIn), debug = false)
    setContent {
      DayfoldTheme {
        FeedApp(
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
    val store = createAppStore(AppState(route = Route.SignIn), debug = false)
    setContent {
      DayfoldTheme {
        FeedApp(
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

  @Test fun account_revokesConnectedDevice() = runComposeUiTest {
    val store = createAppStore(AppState(route = Route.SignIn), debug = false)
    setContent {
      DayfoldTheme {
        FeedApp(
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
    session = Session("a", "r"),
    families = listOf(FamilyMembership("fam1", "The Jacksons", role = "owner", status = "active")),
    activeFamilyId = "fam1",
    route = Route.AuthorizeDevice,
    pendingDevice = PendingDevice("WDJF-7K2P", client = "Dayfold CLI", originKind = originKind),
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
}
