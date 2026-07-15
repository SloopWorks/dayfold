package com.sloopworks.dayfold.swip

import kotlin.test.Test
import kotlin.test.assertEquals
import org.reduxkotlin.createStore

class ReplaceableStoreSubscriptionTest {
  private data class State(val route: String)
  private data class Navigate(val route: String)

  private fun store(route: String) = createStore(
    reducer = { state: State, action: Any ->
      if (action is Navigate) State(action.route) else state
    },
    preloadedState = State(route),
  )

  @Test fun `rebind is idempotent and releases the previous store listener`() {
    val screens = mutableListOf<String>()
    val observer = ReplaceableStoreSubscription<State, String>({ it.route }, screens::add)
    val first = store("Feed")
    val replacement = store("Feed")

    observer.bind(first)
    observer.bind(first)
    assertEquals(listOf("Feed"), screens)

    first.dispatch(Navigate("Account"))
    assertEquals(listOf("Feed", "Account"), screens)

    observer.bind(replacement)
    assertEquals(listOf("Feed", "Account", "Feed"), screens)

    first.dispatch(Navigate("Members"))
    assertEquals(
      listOf("Feed", "Account", "Feed"),
      screens,
      "the recreated host must detach the listener that retained its previous store",
    )

    replacement.dispatch(Navigate("Hubs"))
    assertEquals(listOf("Feed", "Account", "Feed", "Hubs"), screens)
  }

  @Test fun `dispose is idempotent and stops delivery`() {
    val screens = mutableListOf<String>()
    val observer = ReplaceableStoreSubscription<State, String>({ it.route }, screens::add)
    val store = store("Feed")

    observer.bind(store)
    observer.dispose()
    observer.dispose()
    store.dispatch(Navigate("Account"))

    assertEquals(listOf("Feed"), screens)
  }
}
