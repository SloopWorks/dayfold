package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class FeatureStateResetTest {
  @Test fun `unrelated content transition preserves admin device and profile identities`() {
    val state = AppState(
      familyAdmin = FamilyAdminState(rosterBusy = true),
      devices = DeviceState(busy = true),
      profile = ProfileState(nameOpId = "name-op"),
    )

    val next = rootReducer(state, SyncStarted)

    assertSame(state.familyAdmin, next.familyAdmin)
    assertSame(state.devices, next.devices)
    assertSame(state.profile, next.profile)
  }

  @Test fun `family replacement clears only family owned projections`() {
    val state = AppState(
      session = SessionState(session = Session("access", "refresh", "user"), activeFamilyId = "old"),
      familyAdmin = FamilyAdminState(rosterBusy = true),
      hubs = HubState(filter = "mine"),
      devices = DeviceState(busy = true),
      profile = ProfileState(nameOpId = "name-op"),
    )

    val next = rootReducer(state, MembershipsLoaded(listOf(FamilyMembership("new", "New", "owner", "active"))))

    assertEquals(FamilyAdminState(), next.familyAdmin)
    assertEquals(HubState(), next.hubs)
    assertSame(state.devices, next.devices)
    assertSame(state.profile, next.profile)
  }

  @Test fun `terminal identity reset clears account and family slices`() {
    val state = AppState(
      familyAdmin = FamilyAdminState(rosterBusy = true),
      devices = DeviceState(busy = true),
      profile = ProfileState(nameOpId = "name-op"),
      notifConfig = NotifConfig(),
    )

    val next = rootReducer(state, SignedOut)

    assertEquals(FamilyAdminState(), next.familyAdmin)
    assertEquals(DeviceState(), next.devices)
    assertEquals(ProfileState(), next.profile)
    assertSame(state.notifConfig, next.notifConfig)
  }
}
