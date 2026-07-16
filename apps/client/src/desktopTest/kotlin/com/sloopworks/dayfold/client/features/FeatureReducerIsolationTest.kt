package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertSame

/** Flat-state PR2 contract: a reducer keeps the exact input instance for irrelevant actions. */
class FeatureReducerIsolationTest {
  private val state = AppState()

  @Test fun `feature reducers preserve identity for unrelated actions`() {
    assertSame(state, reduceContent(state, OpenAccount))
    assertSame(state, reduceSession(state, SetHubFilter("active")))
    assertSame(state, reduceNavigation(state, SyncStarted))
    assertSame(state, reduceHubs(state, SignInRequested("google")))
    assertSame(state, reduceNow(state, OpenMembers))
    assertSame(state, reduceNotifications(state, HubsLoaded(emptyList())))
    assertSame(state, reduceFamilyAdmin(state, OpenEnterCode))
    assertSame(state, reduceDevices(state, ProfileLoaded(MeProfile("u", "name", "blue", null))))
    assertSame(state, reduceProfile(state, OpenHubs()))
  }
}
