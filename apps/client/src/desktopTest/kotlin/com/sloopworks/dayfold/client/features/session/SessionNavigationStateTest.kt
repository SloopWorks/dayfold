package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SessionNavigationStateTest {
  @Test fun `unrelated content transition preserves session and navigation identities`() {
    val state = AppState(
      session = SessionState(authBusy = true),
      navigation = NavigationState(route = Route.Feed, detailStack = listOf("card_1")),
    )

    val next = rootReducer(state, SyncStarted)

    assertSame(state.session, next.session)
    assertSame(state.navigation, next.navigation)
  }

  @Test fun `account reset clears session and navigation together`() {
    val state = AppState(
      session = SessionState(session = Session("access", "refresh", "user"), authBusy = true),
      navigation = NavigationState(route = Route.Hubs, detailStack = listOf("card_1")),
    )

    assertEquals(SessionState(), rootReducer(state, SignedOut).session)
    assertEquals(NavigationState(route = Route.SignIn), rootReducer(state, SignedOut).navigation)
    assertEquals("Your session expired — please sign in again.", rootReducer(state, SessionExpired).session.authError)
  }
}
