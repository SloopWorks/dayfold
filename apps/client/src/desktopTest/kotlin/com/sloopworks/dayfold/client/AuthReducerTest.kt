package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// AUTH-S5 T1 — the route gate is pure. These pin every transition the AuthEngine
// drives, with no I/O.
class AuthReducerTest {
  private val sess = Session(access = "a", refresh = "r", userId = "u1")
  private val active = FamilyMembership("fam1", "The Jacksons", role = "owner", status = "active")
  private val pending = FamilyMembership("fam2", "Riveras", role = "adult", status = "pending")

  @Test fun `routeFor — no session is SignIn`() {
    assertEquals(Route.SignIn, routeFor(null, listOf(active)))
  }

  @Test fun `routeFor — session with an active membership is Feed`() {
    assertEquals(Route.Feed, routeFor(sess, listOf(active)))
  }

  @Test fun `routeFor — session with only pending or none is CreateFamily`() {
    assertEquals(Route.CreateFamily, routeFor(sess, emptyList()))
    assertEquals(Route.CreateFamily, routeFor(sess, listOf(pending)))
  }

  @Test fun `cold-start restore with no session lands on SignIn`() {
    val s = rootReducer(rootReducer(AppState(), AuthRestoring), SessionRestored(null))
    assertEquals(Route.SignIn, s.route)
    assertNull(s.session.session)
  }

  @Test fun `restoring a saved session waits in Loading until whoami`() {
    val s = rootReducer(AppState(), SessionRestored(sess))
    assertEquals(Route.Loading, s.route)
    assertEquals(sess, s.session.session)
  }

  @Test fun `sign-in busy then success then memberships routes to Feed`() {
    var s = rootReducer(AppState(navigation = NavigationState(route = Route.SignIn)), SignInRequested("google"))
    assertTrue(s.session.authBusy); assertNull(s.session.authError)
    s = rootReducer(s, SignInSucceeded(sess))
    assertFalse(s.session.authBusy); assertEquals(Route.Loading, s.route); assertEquals(sess, s.session.session)
    s = rootReducer(s, MembershipsLoaded(listOf(active)))
    assertEquals(Route.Feed, s.route)
    assertEquals("fam1", s.session.activeFamilyId)
  }

  @Test fun `sign-in with no families routes to CreateFamily`() {
    var s = rootReducer(AppState(), SignInSucceeded(sess))
    s = rootReducer(s, MembershipsLoaded(emptyList()))
    assertEquals(Route.CreateFamily, s.route)
    assertNull(s.session.activeFamilyId)
  }

  @Test fun `sign-in failure surfaces the error and clears busy`() {
    var s = rootReducer(AppState(navigation = NavigationState(route = Route.SignIn)), SignInRequested("apple"))
    s = rootReducer(s, SignInFailed("network down"))
    assertFalse(s.session.authBusy); assertEquals("network down", s.session.authError)
    assertEquals(Route.SignIn, s.route)   // failure keeps you on SignIn, doesn't navigate
  }

  @Test fun `create-family success becomes the active owner family and routes to Feed`() {
    var s = rootReducer(AppState(session = SessionState(session = sess), navigation = NavigationState(route = Route.CreateFamily)), CreateFamilyRequested("The Jacksons"))
    assertTrue(s.session.authBusy)
    s = rootReducer(s, FamilyCreated("fam1", "The Jacksons"))
    assertFalse(s.session.authBusy)
    assertEquals(Route.Feed, s.route)
    assertEquals("fam1", s.session.activeFamilyId)
    val m = s.session.families.single()
    assertEquals("owner", m.role); assertEquals("active", m.status); assertEquals("fam1", m.familyId)
  }

  @Test fun `create-family failure keeps you on CreateFamily with an error`() {
    var s = rootReducer(AppState(session = SessionState(session = sess), navigation = NavigationState(route = Route.CreateFamily)), CreateFamilyRequested("X"))
    s = rootReducer(s, AuthOpFailed("name taken"))
    assertFalse(s.session.authBusy); assertEquals("name taken", s.session.authError); assertEquals(Route.CreateFamily, s.route)
  }

  @Test fun `open then close account overlays the signed-in Feed`() {
    val signedIn = AppState(session = SessionState(session = sess, families = listOf(active), activeFamilyId = "fam1"), navigation = NavigationState(route = Route.Feed))
    val opened = rootReducer(signedIn, OpenAccount)
    assertEquals(Route.Account, opened.route)
    assertEquals(sess, opened.session.session)                 // session/family untouched
    val closed = rootReducer(opened, CloseAccount)
    assertEquals(Route.Feed, closed.route)             // back through the gate
    assertEquals("fam1", closed.session.activeFamilyId)
  }

  @Test fun `open join-invite routes there and dismiss returns to the gate`() {
    val opened = rootReducer(AppState(session = SessionState(session = sess), navigation = NavigationState(route = Route.CreateFamily)), OpenJoinInvite)
    assertEquals(Route.JoinInvite, opened.route)
    assertNull(opened.session.joinOutcome)
    val waiting = rootReducer(opened, InviteRedeemed("Riveras"))
    assertEquals("waiting", waiting.session.joinOutcome)
    val dismissed = rootReducer(waiting, JoinDismissed)
    assertEquals(Route.CreateFamily, dismissed.route)   // no active family → back to CreateFamily
    assertNull(dismissed.session.joinOutcome)
  }

  @Test fun `invitee-join outcomes (waiting then dismiss, and a rejection)`() {
    var s = rootReducer(AppState(session = SessionState(session = sess)), RedeemRequested("tok"))
    assertTrue(s.session.joinBusy); assertNull(s.session.joinOutcome)
    s = rootReducer(s, InviteRedeemed("The Jacksons"))
    assertFalse(s.session.joinBusy); assertEquals("waiting", s.session.joinOutcome); assertEquals("The Jacksons", s.session.joinFamilyName)
    val cleared = rootReducer(s, JoinDismissed)
    assertNull(cleared.session.joinOutcome); assertNull(cleared.session.joinFamilyName)

    val rejected = rootReducer(rootReducer(AppState(), RedeemRequested("t")), InviteRejected("expired"))
    assertEquals("expired", rejected.session.joinOutcome); assertFalse(rejected.session.joinBusy)
  }

  @Test fun `owner approvals queue loads and resolves`() {
    var s = rootReducer(AppState(), ApprovalsRequested)
    assertTrue(s.approvalsBusy)
    s = rootReducer(s, ApprovalsLoaded(listOf(PendingMember("u9", "Sam"), PendingMember("u8", "Mo"))))
    assertFalse(s.approvalsBusy)
    assertEquals(listOf("u9", "u8"), s.pendingApprovals.map { it.uid })
    s = rootReducer(s, MemberResolved("u9"))
    assertEquals(listOf("u8"), s.pendingApprovals.map { it.uid })   // approved/declined → dropped
  }

  @Test fun `owner invite-mint slice transitions`() {
    val mi = MintedInvite("i", "TOK", "https://x/invite/TOK", "adult", "qr", "2099-01-01T00:00:00Z")
    // open clears any prior mint + routes
    var s = rootReducer(AppState(navigation = NavigationState(route = Route.Members), mintedInvite = mi, mintError = "error"), OpenInvite)
    assertEquals(Route.Invite, s.route); assertNull(s.mintedInvite); assertNull(s.mintError)
    // mint request → busy, error cleared
    s = rootReducer(s.copy(mintError = "error"), MintRequested)
    assertTrue(s.inviteBusy); assertNull(s.mintError)
    // minted → token stored, busy off
    s = rootReducer(s, InviteMinted(mi))
    assertEquals("TOK", s.mintedInvite?.token); assertFalse(s.inviteBusy)
    // a mode switch invalidates the shown code
    s = rootReducer(s, InviteModeSelected("link"))
    assertEquals("link", s.inviteMode); assertNull(s.mintedInvite)
    // failure sets error + clears busy
    s = rootReducer(s.copy(inviteBusy = true), MintFailed("ratelimited"))
    assertEquals("ratelimited", s.mintError); assertFalse(s.inviteBusy)
    // dismiss clears the token + routes back to Members
    s = rootReducer(s.copy(navigation = s.navigation.copy(route = Route.Invite), mintedInvite = mi), InviteDismissed)
    assertEquals(Route.Members, s.route); assertNull(s.mintedInvite)
  }

  @Test fun `ApprovalsLoaded feeds pending queue and outstanding invites then revoke drops a row`() {
    var s = rootReducer(AppState(), ApprovalsLoaded(
      listOf(PendingMember("u9", "Sam")),
      listOf(Invite(id = "inv1", mode = "link", expiresAt = "z"), Invite(id = "inv2", mode = "qr", expiresAt = "z")),
    ))
    assertEquals(listOf("u9"), s.pendingApprovals.map { it.uid })
    assertEquals(listOf("inv1", "inv2"), s.outstandingInvites.map { it.id })
    s = rootReducer(s, InviteRevokeRequested("inv1"))
    assertEquals("inv1", s.inviteOpId)
    s = rootReducer(s, InviteRevoked("inv1"))
    assertEquals(listOf("inv2"), s.outstandingInvites.map { it.id }); assertNull(s.inviteOpId)
  }

  @Test fun `roster loads then a member is removed`() {
    var s = rootReducer(AppState(), RosterLoaded(listOf(FamilyMember("u1", "Pat", role = "owner"), FamilyMember("u2", "Maya"))))
    assertEquals(listOf("u1", "u2"), s.members.map { it.uid })
    s = rootReducer(s, MemberRemoved("u2"))
    assertEquals(listOf("u1"), s.members.map { it.uid })
  }

  @Test fun `devices load then a credential is revoked`() {
    var s = rootReducer(AppState(), DevicesLoaded(listOf(DeviceCredential("c1", current = true), DeviceCredential("c2", kind = "cli"))))
    assertEquals(listOf("c1", "c2"), s.devices.map { it.id })
    s = rootReducer(s, DeviceRevoked("c2"))
    assertEquals(listOf("c1"), s.devices.map { it.id })
  }

  // ── CLI/device approval (S6-D) ──
  private val ownerFam = FamilyMembership("fam1", "The Jacksons", role = "owner", status = "active")
  private val memberFam = FamilyMembership("fam2", "Riveras", role = "adult", status = "active")
  private val dev = PendingDevice("WDJF-7K2P", client = "dayfold-cli", originKind = "datacenter")

  @Test fun `ownerFamiliesFor keeps only active owner families`() {
    val out = ownerFamiliesFor(listOf(ownerFam, memberFam, FamilyMembership("fam3", role = "owner", status = "pending")))
    assertEquals(listOf("fam1"), out.map { it.familyId })
  }

  @Test fun `OpenEnterCode routes to EnterCode and clears device fields`() {
    val dirty = AppState(
      navigation = NavigationState(route = Route.AuthorizeDevice), pendingDevice = dev, deviceBusy = true,
      deviceError = "x", deviceOutcome = "denied",
    )
    val s = rootReducer(dirty, OpenEnterCode)
    assertEquals(Route.EnterCode, s.route)
    assertNull(s.pendingDevice); assertFalse(s.deviceBusy); assertNull(s.deviceError); assertNull(s.deviceOutcome)
  }

  @Test fun `DeviceLookupRequested sets busy and clears the error`() {
    val s = rootReducer(AppState(navigation = NavigationState(route = Route.EnterCode), deviceError = "old"), DeviceLookupRequested)
    assertTrue(s.deviceBusy); assertNull(s.deviceError)
  }

  @Test fun `DevicePendingLoaded routes to AuthorizeDevice with the device`() {
    val s = rootReducer(AppState(navigation = NavigationState(route = Route.EnterCode), deviceBusy = true), DevicePendingLoaded(dev))
    assertEquals(Route.AuthorizeDevice, s.route)
    assertEquals(dev, s.pendingDevice); assertFalse(s.deviceBusy); assertNull(s.deviceOutcome)
  }

  @Test fun `DeviceLookupNotFound routes to AuthorizeDevice with the expired outcome`() {
    val s = rootReducer(AppState(navigation = NavigationState(route = Route.EnterCode), deviceBusy = true), DeviceLookupNotFound)
    assertEquals(Route.AuthorizeDevice, s.route)
    assertEquals("expired", s.deviceOutcome); assertNull(s.pendingDevice); assertFalse(s.deviceBusy)
  }

  @Test fun `DeviceLookupFailed surfaces the inline error and stays on EnterCode`() {
    val s = rootReducer(AppState(navigation = NavigationState(route = Route.EnterCode), deviceBusy = true), DeviceLookupFailed("Too many tries"))
    assertEquals(Route.EnterCode, s.route)            // does NOT navigate
    assertEquals("Too many tries", s.deviceError); assertFalse(s.deviceBusy)
  }

  @Test fun `approve and deny flows set busy then the terminal outcome`() {
    val onScreen = AppState(navigation = NavigationState(route = Route.AuthorizeDevice), pendingDevice = dev)
    var s = rootReducer(onScreen, ApproveDeviceRequested)
    assertTrue(s.deviceBusy)
    s = rootReducer(s, DeviceApproved)
    assertFalse(s.deviceBusy); assertEquals("approved", s.deviceOutcome)

    s = rootReducer(rootReducer(onScreen, DenyDeviceRequested), DeviceDenied)
    assertEquals("denied", s.deviceOutcome); assertFalse(s.deviceBusy)

    s = rootReducer(rootReducer(onScreen, ApproveDeviceRequested), DeviceApproveExpired)
    assertEquals("expired", s.deviceOutcome); assertFalse(s.deviceBusy)
  }

  @Test fun `DeviceOpFailed surfaces an inline error and clears busy (stays on AuthorizeDevice)`() {
    val s = rootReducer(AppState(navigation = NavigationState(route = Route.AuthorizeDevice), pendingDevice = dev, deviceBusy = true), DeviceOpFailed("Couldn't approve"))
    assertEquals(Route.AuthorizeDevice, s.route)
    assertEquals("Couldn't approve", s.deviceError); assertFalse(s.deviceBusy); assertNull(s.deviceOutcome)
  }

  @Test fun `CloseDeviceFlow returns to the gate and clears device state`() {
    val onScreen = AppState(
      session = SessionState(session = sess, families = listOf(active), activeFamilyId = "fam1"),
      navigation = NavigationState(route = Route.AuthorizeDevice), pendingDevice = dev, deviceOutcome = "approved", deviceError = "x",
    )
    val s = rootReducer(onScreen, CloseDeviceFlow)
    assertEquals(Route.Feed, s.route)                 // routeFor(session, active family) = Feed
    assertNull(s.pendingDevice); assertNull(s.deviceOutcome); assertNull(s.deviceError); assertFalse(s.deviceBusy)
  }

  @Test fun `deep-link code stashes then is consumed, and sign-out clears it`() {
    var s = rootReducer(AppState(navigation = NavigationState(route = Route.SignIn)), DeviceLinkStashed("WDJF-7K2P"))
    assertEquals("WDJF-7K2P", s.pendingDeviceLink)
    assertEquals(Route.SignIn, s.route)                 // stashing does not navigate
    s = rootReducer(s, DeviceLinkConsumed)
    assertNull(s.pendingDeviceLink)
    // a stash that survives to a signed-in session is wiped on sign-out (fresh state)
    val signedOut = rootReducer(AppState(session = SessionState(session = sess), pendingDeviceLink = "X"), SignedOut)
    assertNull(signedOut.pendingDeviceLink)
  }

  // a fully-populated signed-in state: session + family roster + cards + hub content
  // + a pending device grant — the data that must NEVER survive a session invalidation.
  private fun populatedSignedIn(): AppState {
    val hubRequest = HubRequestKey(HubTenantGeneration(1L, 2L), 3L)
    val audienceRequest = HubRequestKey(hubRequest.generation, 4L)
    return AppState(
      session = SessionState(
        session = sess,
        families = listOf(FamilyMembership("fam1", "The Jacksons", role = "owner", status = "active")),
        activeFamilyId = "fam1",
      ),
      cards = listOf(Card("c1", title = "Soccer 4pm")),
      pendingApprovals = listOf(PendingMember("u9", "Sam")),
      pendingDevice = PendingDevice("WDJF-7K2P", client = "cli"),
      pendingDeviceLink = "X",
      hubs = HubState(
        hubs = listOf(Hub(id = "h1", title = "Butler", status = "active", visibility = "family")),
        currentHubId = "h1", currentHubTree = HubTree(Hub(id = "h1", title = "Butler", status = "active", visibility = "family"), emptyList(), emptyList()),
        currentHubRequest = hubRequest, focusBlockId = "b1", audienceSheetOpen = true,
        currentAudience = HubAudience("family"), currentAudienceRequest = audienceRequest,
      ),
    )
  }

  private fun assertNoSensitiveStateSurvives(out: AppState) {
    assertNull(out.session.session)
    assertTrue(out.session.families.isEmpty()); assertNull(out.session.activeFamilyId)
    assertTrue(out.cards.isEmpty())
    assertTrue(out.pendingApprovals.isEmpty())
    assertNull(out.pendingDevice); assertNull(out.pendingDeviceLink)
    assertTrue(out.hubs.isEmpty()); assertNull(out.currentHubId); assertNull(out.currentHubTree); assertNull(out.currentHubRequest); assertNull(out.hubFocusBlockId)
    assertFalse(out.audienceSheetOpen); assertNull(out.currentHubAudience); assertNull(out.currentHubAudienceRequest)
  }

  @Test fun `sign-out wipes ALL sensitive state, not just the session`() {
    // explicit logout — a refactor to state.copy(session=null) would leak family data.
    val out = rootReducer(populatedSignedIn(), SignedOut)
    assertEquals(Route.SignIn, out.route)
    assertNoSensitiveStateSurvives(out)
  }

  @Test fun `session-expiry (auto-logout on token failure) wipes the same sensitive state`() {
    // SessionExpired is the involuntary path (refresh failed / token expired mid-session);
    // it must wipe family data just like an explicit sign-out, and surface the reason.
    val out = rootReducer(populatedSignedIn(), SessionExpired)
    assertEquals(Route.SignIn, out.route)
    assertNoSensitiveStateSurvives(out)
    assertEquals("Your session expired — please sign in again.", out.session.authError)
  }

  @Test fun `scan flow routes — primer, granted to device, denied`() {
    assertEquals(Route.ScanPrimer, rootReducer(AppState(navigation = NavigationState(route = Route.EnterCode)), OpenScan).route)
    assertEquals(Route.ScanDevice, rootReducer(AppState(navigation = NavigationState(route = Route.ScanPrimer)), ScanPermissionGranted).route)
    assertEquals(Route.ScanDenied, rootReducer(AppState(navigation = NavigationState(route = Route.ScanPrimer)), ScanPermissionDenied).route)
  }

  // ── Task 5: HubsLoaded (DB-fed) prunes currentHubId + currentHubTree ──────────

  // (d) Reducer: HubsLoaded prunes currentHubId when the open hub is gone
  @Test fun `HubsLoaded prunes currentHubId when the open hub is gone`() {
    val h1 = Hub("h1", title = "Party")
    val h2 = Hub("h2", title = "Vacation")
    val tree = HubTree(h1, emptyList(), emptyList())
    val request = HubRequestKey(HubTenantGeneration(1L, 2L), 3L)
    val withOpenHub = AppState(hubs = HubState(
      hubs = listOf(h1, h2), currentHubId = "h1", currentHubTree = tree,
      currentHubRequest = request, audienceSheetOpen = true,
      currentAudience = HubAudience("family"), currentAudienceRequest = HubRequestKey(request.generation, 4L),
    ))
    // Bridge delivers [h2] only — h1 was tombstoned
    val pruned = rootReducer(withOpenHub, HubsLoaded(listOf(h2)))
    assertNull(pruned.currentHubId)
    assertNull(pruned.currentHubTree)
    assertNull(pruned.currentHubRequest)
    assertFalse(pruned.audienceSheetOpen)
    assertNull(pruned.currentHubAudienceRequest)
    assertEquals(listOf("h2"), pruned.hubs.map { it.id })
    assertFalse(pruned.hubsBusy)
  }

  @Test fun `HubsLoaded keeps currentHubId when the open hub still exists`() {
    val h1 = Hub("h1", title = "Party")
    val h2 = Hub("h2", title = "Vacation")
    val tree = HubTree(h1, emptyList(), emptyList())
    val withOpenHub = AppState(hubs = HubState(hubs = listOf(h1, h2), currentHubId = "h1", currentHubTree = tree))
    // Bridge delivers both hubs — h1 still present
    val s = rootReducer(withOpenHub, HubsLoaded(listOf(h1, h2)))
    assertEquals("h1", s.currentHubId)
    assertEquals(tree, s.currentHubTree)
  }

  @Test fun `HubsLoaded with null currentHubId leaves tree null (no-hub-open case)`() {
    val h1 = Hub("h1", title = "Party")
    val s = rootReducer(AppState(hubs = HubState(hubs = emptyList(), currentHubId = null, currentHubTree = null)), HubsLoaded(listOf(h1)))
    assertEquals(listOf("h1"), s.hubs.map { it.id })
    assertNull(s.currentHubId)
    assertNull(s.currentHubTree)
  }

  @Test fun `deep-link resume sets the finishing flag, cleared when the lookup resolves`() {
    var s = rootReducer(AppState(pendingDeviceLink = "WDJF-7K2P"), DeviceLinkConsumed)
    assertNull(s.pendingDeviceLink); assertTrue(s.deviceResuming)              // → Finishing beat
    s = rootReducer(s, DevicePendingLoaded(dev))
    assertFalse(s.deviceResuming); assertEquals(Route.AuthorizeDevice, s.route)
    // a resume lookup that 404s also clears the flag (no wedge on Finishing)
    assertFalse(rootReducer(rootReducer(AppState(), DeviceLinkConsumed), DeviceLookupNotFound).deviceResuming)
  }

  @Test fun `sign-out clears session and feed back to SignIn`() {
    val signedIn = AppState(
      cards = listOf(Card("c", title = "T")),
      session = SessionState(session = sess, families = listOf(active), activeFamilyId = "fam1"),
      navigation = NavigationState(route = Route.Feed),
    )
    val s = rootReducer(signedIn, SignedOut)
    assertEquals(Route.SignIn, s.route)
    assertNull(s.session.session)
    assertTrue(s.session.families.isEmpty())
    assertTrue(s.cards.isEmpty())
    assertNull(s.session.activeFamilyId)
  }

  @Test fun `invite revoke failure clears only the matching busy row and preserves data`() {
    val invites = listOf(Invite(id = "i1", mode = "link", expiresAt = "z"))
    val busy = AppState(outstandingInvites = invites, inviteOpId = "i1")
    val failed = rootReducer(busy, InviteRevokeFailed("i1"))
    assertNull(failed.inviteOpId)
    assertEquals(invites, failed.outstandingInvites)

    val stale = rootReducer(busy, InviteRevokeFailed("other"))
    assertEquals("i1", stale.inviteOpId)
    assertEquals(invites, stale.outstandingInvites)
  }
}
