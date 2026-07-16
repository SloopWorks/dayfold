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
    assertEquals("u1", a.familyAdmin.memberOpId)
    val b = rootReducer(a, MemberResolved("u1"))
    assertNull(b.familyAdmin.memberOpId)
  }

  @Test fun memberOpClearedOnApprovalsFailed() {
    assertNull(rootReducer(AppState(familyAdmin = FamilyAdminState(memberOpId = "u1")), ApprovalsFailed).familyAdmin.memberOpId)
  }

  @Test fun rosterRequestedFailedFlow() {
    val a = rootReducer(AppState(), RosterRequested)
    assertTrue(a.familyAdmin.rosterBusy)
    val b = rootReducer(a, RosterFailed("x"))
    assertFalse(b.familyAdmin.rosterBusy); assertEquals("x", b.familyAdmin.rosterError); assertNull(b.familyAdmin.memberOpId)
  }

  @Test fun deviceOpRequestedThenRevokedClearsId() {
    val a = rootReducer(AppState(), DeviceOpRequested("c1"))
    assertEquals("c1", a.devices.operationId)
    assertNull(rootReducer(a, DeviceRevoked("c1")).devices.operationId)
  }

  @Test fun devicesRequestedFailedFlow() {
    val a = rootReducer(AppState(), DevicesRequested)
    assertTrue(a.devices.listBusy)
    val b = rootReducer(a, DevicesFailed("x"))
    assertFalse(b.devices.listBusy); assertEquals("x", b.devices.listError)
  }

  @Test fun audienceFailedSetsError() {
    val request = HubRequestKey(HubTenantGeneration(1L, 1L), 1L)
    val state = AppState(hubs = HubState(
      currentHubId = "h1", currentHubRequest = request,
      audienceSheetOpen = true, currentAudienceRequest = request,
    ))
    assertEquals("x", rootReducer(state, AudienceFailed("h1", request, "x")).hubs.audienceError)
  }
}
