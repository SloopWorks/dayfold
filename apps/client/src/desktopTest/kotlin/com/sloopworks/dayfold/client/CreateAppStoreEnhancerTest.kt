package com.sloopworks.dayfold.client

import org.reduxkotlin.StoreEnhancer
import org.reduxkotlin.concurrent.NotificationContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CreateAppStoreEnhancerTest {
  /** Counting enhancer: wraps dispatch, increments per action, innermost-position observable. */
  private var seen = 0
  private val counting: StoreEnhancer<AppState> = { creator ->
    { r, s, e ->
      val store = creator(r, s, e)
      val inner = store.dispatch
      store.dispatch = { a -> seen++; inner(a) }
      store
    }
  }

  @Test fun extra_enhancer_sees_dispatches_in_debug_and_release_modes() {
    for (debug in listOf(true, false)) {
      seen = 0
      val store = createAppStore(
        notificationContext = NotificationContext.Inline,
        debug = debug,
        extraEnhancer = counting,
      )
      store.dispatch(OpenFeed) // data object, Reducer.kt:56 → route = Route.Feed (initial is Route.Loading)
      assertTrue(seen >= 1, "debug=$debug")  // >=1: devtools may re-dispatch internals
      assertEquals(Route.Feed, store.state.navigation.route)
    }
  }

  @Test fun null_extra_enhancer_is_todays_behavior() {
    val store = createAppStore(notificationContext = NotificationContext.Inline, debug = false)
    store.dispatch(OpenFeed)
    assertEquals(Route.Feed, store.state.navigation.route)
  }

  @Test fun supplied_notification_context_is_used_in_debug_and_release_modes() {
    for (debug in listOf(true, false)) {
      var posts = 0
      val context = NotificationContext { block ->
        posts++
        block()
      }
      val store = createAppStore(notificationContext = context, debug = debug)
      store.subscribe {}

      store.dispatch(OpenFeed)

      assertEquals(1, posts, "debug=$debug")
    }
  }
}
