package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class LoadingReducerTest {
  @Test fun signInRequestedSetsPendingProvider() {
    val s = rootReducer(AppState(), SignInRequested("google"))
    assertEquals("google", s.session.pendingProvider)
    assertTrue(s.session.authBusy)
  }

  @Test fun signInFailedClearsPendingProvider() {
    val s = rootReducer(AppState(session = SessionState(pendingProvider = "google", authBusy = true)), SignInFailed("nope"))
    assertNull(s.session.pendingProvider)
    assertFalse(s.session.authBusy)
  }

  @Test fun signOutRequestedSetsBusy() {
    assertTrue(rootReducer(AppState(), SignOutRequested).session.signOutBusy)
  }

  @Test fun memberOpRequestedThenResolvedClearsId() {
    val a = rootReducer(AppState(), MemberOpRequested("u1"))
    assertEquals("u1", a.memberOpId)
    val b = rootReducer(a, MemberResolved("u1"))
    assertNull(b.memberOpId)
  }

  @Test fun memberOpClearedOnApprovalsFailed() {
    assertNull(rootReducer(AppState(memberOpId = "u1"), ApprovalsFailed).memberOpId)
  }

  @Test fun rosterRequestedFailedFlow() {
    val a = rootReducer(AppState(), RosterRequested)
    assertTrue(a.rosterBusy)
    val b = rootReducer(a, RosterFailed("x"))
    assertFalse(b.rosterBusy); assertEquals("x", b.rosterError); assertNull(b.memberOpId)
  }

  @Test fun deviceOpRequestedThenRevokedClearsId() {
    val a = rootReducer(AppState(), DeviceOpRequested("c1"))
    assertEquals("c1", a.deviceOpId)
    assertNull(rootReducer(a, DeviceRevoked("c1")).deviceOpId)
  }

  @Test fun devicesRequestedFailedFlow() {
    val a = rootReducer(AppState(), DevicesRequested)
    assertTrue(a.deviceListBusy)
    val b = rootReducer(a, DevicesFailed("x"))
    assertFalse(b.deviceListBusy); assertEquals("x", b.deviceListError)
  }

  @Test fun audienceFailedSetsError() {
    val request = HubRequestKey(HubTenantGeneration(1L, 1L), 1L)
    val state = AppState(hubs = HubState(
      currentHubId = "h1", currentHubRequest = request,
      audienceSheetOpen = true, currentAudienceRequest = request,
    ))
    assertEquals("x", rootReducer(state, AudienceFailed("h1", request, "x")).audienceError)
  }
}
