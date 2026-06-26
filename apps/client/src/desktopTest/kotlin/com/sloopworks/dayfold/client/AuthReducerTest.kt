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
    assertNull(s.session)
  }

  @Test fun `restoring a saved session waits in Loading until whoami`() {
    val s = rootReducer(AppState(), SessionRestored(sess))
    assertEquals(Route.Loading, s.route)
    assertEquals(sess, s.session)
  }

  @Test fun `sign-in busy then success then memberships routes to Feed`() {
    var s = rootReducer(AppState(route = Route.SignIn), SignInRequested("google"))
    assertTrue(s.authBusy); assertNull(s.authError)
    s = rootReducer(s, SignInSucceeded(sess))
    assertFalse(s.authBusy); assertEquals(Route.Loading, s.route); assertEquals(sess, s.session)
    s = rootReducer(s, MembershipsLoaded(listOf(active)))
    assertEquals(Route.Feed, s.route)
    assertEquals("fam1", s.activeFamilyId)
  }

  @Test fun `sign-in with no families routes to CreateFamily`() {
    var s = rootReducer(AppState(), SignInSucceeded(sess))
    s = rootReducer(s, MembershipsLoaded(emptyList()))
    assertEquals(Route.CreateFamily, s.route)
    assertNull(s.activeFamilyId)
  }

  @Test fun `sign-in failure surfaces the error and clears busy`() {
    var s = rootReducer(AppState(route = Route.SignIn), SignInRequested("apple"))
    s = rootReducer(s, SignInFailed("network down"))
    assertFalse(s.authBusy); assertEquals("network down", s.authError)
    assertEquals(Route.SignIn, s.route)   // failure keeps you on SignIn, doesn't navigate
  }

  @Test fun `create-family success becomes the active owner family and routes to Feed`() {
    var s = rootReducer(AppState(session = sess, route = Route.CreateFamily), CreateFamilyRequested("The Jacksons"))
    assertTrue(s.authBusy)
    s = rootReducer(s, FamilyCreated("fam1", "The Jacksons"))
    assertFalse(s.authBusy)
    assertEquals(Route.Feed, s.route)
    assertEquals("fam1", s.activeFamilyId)
    val m = s.families.single()
    assertEquals("owner", m.role); assertEquals("active", m.status); assertEquals("fam1", m.familyId)
  }

  @Test fun `create-family failure keeps you on CreateFamily with an error`() {
    var s = rootReducer(AppState(session = sess, route = Route.CreateFamily), CreateFamilyRequested("X"))
    s = rootReducer(s, AuthOpFailed("name taken"))
    assertFalse(s.authBusy); assertEquals("name taken", s.authError); assertEquals(Route.CreateFamily, s.route)
  }

  @Test fun `open then close account overlays the signed-in Feed`() {
    val signedIn = AppState(session = sess, families = listOf(active), activeFamilyId = "fam1", route = Route.Feed)
    val opened = rootReducer(signedIn, OpenAccount)
    assertEquals(Route.Account, opened.route)
    assertEquals(sess, opened.session)                 // session/family untouched
    val closed = rootReducer(opened, CloseAccount)
    assertEquals(Route.Feed, closed.route)             // back through the gate
    assertEquals("fam1", closed.activeFamilyId)
  }

  @Test fun `open join-invite routes there and dismiss returns to the gate`() {
    val opened = rootReducer(AppState(session = sess, route = Route.CreateFamily), OpenJoinInvite)
    assertEquals(Route.JoinInvite, opened.route)
    assertNull(opened.joinOutcome)
    val waiting = rootReducer(opened, InviteRedeemed("Riveras"))
    assertEquals("waiting", waiting.joinOutcome)
    val dismissed = rootReducer(waiting, JoinDismissed)
    assertEquals(Route.CreateFamily, dismissed.route)   // no active family → back to CreateFamily
    assertNull(dismissed.joinOutcome)
  }

  @Test fun `invitee-join outcomes (waiting then dismiss, and a rejection)`() {
    var s = rootReducer(AppState(session = sess), RedeemRequested("tok"))
    assertTrue(s.joinBusy); assertNull(s.joinOutcome)
    s = rootReducer(s, InviteRedeemed("The Jacksons"))
    assertFalse(s.joinBusy); assertEquals("waiting", s.joinOutcome); assertEquals("The Jacksons", s.joinFamilyName)
    val cleared = rootReducer(s, JoinDismissed)
    assertNull(cleared.joinOutcome); assertNull(cleared.joinFamilyName)

    val rejected = rootReducer(rootReducer(AppState(), RedeemRequested("t")), InviteRejected("expired"))
    assertEquals("expired", rejected.joinOutcome); assertFalse(rejected.joinBusy)
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
      route = Route.AuthorizeDevice, pendingDevice = dev, deviceBusy = true,
      deviceError = "x", deviceOutcome = "denied",
    )
    val s = rootReducer(dirty, OpenEnterCode)
    assertEquals(Route.EnterCode, s.route)
    assertNull(s.pendingDevice); assertFalse(s.deviceBusy); assertNull(s.deviceError); assertNull(s.deviceOutcome)
  }

  @Test fun `DeviceLookupRequested sets busy and clears the error`() {
    val s = rootReducer(AppState(route = Route.EnterCode, deviceError = "old"), DeviceLookupRequested)
    assertTrue(s.deviceBusy); assertNull(s.deviceError)
  }

  @Test fun `DevicePendingLoaded routes to AuthorizeDevice with the device`() {
    val s = rootReducer(AppState(route = Route.EnterCode, deviceBusy = true), DevicePendingLoaded(dev))
    assertEquals(Route.AuthorizeDevice, s.route)
    assertEquals(dev, s.pendingDevice); assertFalse(s.deviceBusy); assertNull(s.deviceOutcome)
  }

  @Test fun `DeviceLookupNotFound routes to AuthorizeDevice with the expired outcome`() {
    val s = rootReducer(AppState(route = Route.EnterCode, deviceBusy = true), DeviceLookupNotFound)
    assertEquals(Route.AuthorizeDevice, s.route)
    assertEquals("expired", s.deviceOutcome); assertNull(s.pendingDevice); assertFalse(s.deviceBusy)
  }

  @Test fun `DeviceLookupFailed surfaces the inline error and stays on EnterCode`() {
    val s = rootReducer(AppState(route = Route.EnterCode, deviceBusy = true), DeviceLookupFailed("Too many tries"))
    assertEquals(Route.EnterCode, s.route)            // does NOT navigate
    assertEquals("Too many tries", s.deviceError); assertFalse(s.deviceBusy)
  }

  @Test fun `approve and deny flows set busy then the terminal outcome`() {
    val onScreen = AppState(route = Route.AuthorizeDevice, pendingDevice = dev)
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
    val s = rootReducer(AppState(route = Route.AuthorizeDevice, pendingDevice = dev, deviceBusy = true), DeviceOpFailed("Couldn't approve"))
    assertEquals(Route.AuthorizeDevice, s.route)
    assertEquals("Couldn't approve", s.deviceError); assertFalse(s.deviceBusy); assertNull(s.deviceOutcome)
  }

  @Test fun `CloseDeviceFlow returns to the gate and clears device state`() {
    val onScreen = AppState(
      session = sess, families = listOf(active), activeFamilyId = "fam1",
      route = Route.AuthorizeDevice, pendingDevice = dev, deviceOutcome = "approved", deviceError = "x",
    )
    val s = rootReducer(onScreen, CloseDeviceFlow)
    assertEquals(Route.Feed, s.route)                 // routeFor(session, active family) = Feed
    assertNull(s.pendingDevice); assertNull(s.deviceOutcome); assertNull(s.deviceError); assertFalse(s.deviceBusy)
  }

  @Test fun `deep-link code stashes then is consumed, and sign-out clears it`() {
    var s = rootReducer(AppState(route = Route.SignIn), DeviceLinkStashed("WDJF-7K2P"))
    assertEquals("WDJF-7K2P", s.pendingDeviceLink)
    assertEquals(Route.SignIn, s.route)                 // stashing does not navigate
    s = rootReducer(s, DeviceLinkConsumed)
    assertNull(s.pendingDeviceLink)
    // a stash that survives to a signed-in session is wiped on sign-out (fresh state)
    val signedOut = rootReducer(AppState(session = sess, pendingDeviceLink = "X"), SignedOut)
    assertNull(signedOut.pendingDeviceLink)
  }

  @Test fun `sign-out wipes ALL sensitive state, not just the session`() {
    // a fully-populated signed-in state: session + family roster + cards + hub content
    // + a pending device grant. Sign-out must leave NONE of it (privacy guardrail) —
    // a refactor to state.copy(session=null) would leak family data and this catches it.
    val populated = AppState(
      session = sess,
      families = listOf(FamilyMembership("fam1", "The Jacksons", role = "owner", status = "active")),
      activeFamilyId = "fam1",
      cards = listOf(Card("c1", title = "Soccer 4pm")),
      pendingApprovals = listOf(PendingMember("u9", "Sam")),
      pendingDevice = PendingDevice("WDJF-7K2P", client = "cli"),
      pendingDeviceLink = "X",
      hubs = listOf(Hub(id = "h1", title = "Butler", status = "active", visibility = "family")),
      currentHubId = "h1",
      currentHubTree = HubTree(Hub(id = "h1", title = "Butler", status = "active", visibility = "family"), emptyList(), emptyList()),
      hubFocusBlockId = "b1",
    )
    val out = rootReducer(populated, SignedOut)
    assertEquals(Route.SignIn, out.route)
    assertNull(out.session)
    assertTrue(out.families.isEmpty()); assertNull(out.activeFamilyId)
    assertTrue(out.cards.isEmpty())
    assertTrue(out.pendingApprovals.isEmpty())
    assertNull(out.pendingDevice); assertNull(out.pendingDeviceLink)
    assertTrue(out.hubs.isEmpty()); assertNull(out.currentHubId); assertNull(out.currentHubTree); assertNull(out.hubFocusBlockId)
  }

  @Test fun `scan flow routes — primer, granted to device, denied`() {
    assertEquals(Route.ScanPrimer, rootReducer(AppState(route = Route.EnterCode), OpenScan).route)
    assertEquals(Route.ScanDevice, rootReducer(AppState(route = Route.ScanPrimer), ScanPermissionGranted).route)
    assertEquals(Route.ScanDenied, rootReducer(AppState(route = Route.ScanPrimer), ScanPermissionDenied).route)
  }

  // ── Task 5: HubsLoaded (DB-fed) prunes currentHubId + currentHubTree ──────────

  // (d) Reducer: HubsLoaded prunes currentHubId when the open hub is gone
  @Test fun `HubsLoaded prunes currentHubId when the open hub is gone`() {
    val h1 = Hub("h1", title = "Party")
    val h2 = Hub("h2", title = "Vacation")
    val tree = HubTree(h1, emptyList(), emptyList())
    val withOpenHub = AppState(hubs = listOf(h1, h2), currentHubId = "h1", currentHubTree = tree)
    // Bridge delivers [h2] only — h1 was tombstoned
    val pruned = rootReducer(withOpenHub, HubsLoaded(listOf(h2)))
    assertNull(pruned.currentHubId)
    assertNull(pruned.currentHubTree)
    assertEquals(listOf("h2"), pruned.hubs.map { it.id })
    assertFalse(pruned.hubsBusy)
  }

  @Test fun `HubsLoaded keeps currentHubId when the open hub still exists`() {
    val h1 = Hub("h1", title = "Party")
    val h2 = Hub("h2", title = "Vacation")
    val tree = HubTree(h1, emptyList(), emptyList())
    val withOpenHub = AppState(hubs = listOf(h1, h2), currentHubId = "h1", currentHubTree = tree)
    // Bridge delivers both hubs — h1 still present
    val s = rootReducer(withOpenHub, HubsLoaded(listOf(h1, h2)))
    assertEquals("h1", s.currentHubId)
    assertEquals(tree, s.currentHubTree)
  }

  @Test fun `HubsLoaded with null currentHubId leaves tree null (no-hub-open case)`() {
    val h1 = Hub("h1", title = "Party")
    val s = rootReducer(AppState(hubs = emptyList(), currentHubId = null, currentHubTree = null), HubsLoaded(listOf(h1)))
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
      cards = listOf(Card("c", title = "T")), session = sess,
      families = listOf(active), activeFamilyId = "fam1", route = Route.Feed,
    )
    val s = rootReducer(signedIn, SignedOut)
    assertEquals(Route.SignIn, s.route)
    assertNull(s.session)
    assertTrue(s.families.isEmpty())
    assertTrue(s.cards.isEmpty())
    assertNull(s.activeFamilyId)
  }
}
