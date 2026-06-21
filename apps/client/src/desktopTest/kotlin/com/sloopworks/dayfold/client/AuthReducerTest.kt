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
