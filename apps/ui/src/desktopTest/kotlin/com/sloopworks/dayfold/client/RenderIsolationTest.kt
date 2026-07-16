package com.sloopworks.dayfold.client

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.reduxkotlin.Store
import org.reduxkotlin.StoreSubscriber
import org.reduxkotlin.StoreSubscription
import org.reduxkotlin.compose.rememberSelectorStore

@OptIn(ExperimentalTestApi::class)
class RenderIsolationTest {

  @Test
  fun unrelatedFeatureChangesDoNotRecomposeShellOrHubRoute() = runComposeUiTest {
    val countingStore = CountingStore(createTestAppStore(AppState(route = Route.Hubs)))
    lateinit var counts: MutableMap<String, Int>

    setContent {
      counts = remember { mutableMapOf() }
      IsolationProbe(countingStore, counts)
    }
    waitForIdle()
    val baseline = counts.toMap()
    assertEquals(1, baseline["shell"])
    assertEquals(1, baseline["hubs"])

    countingStore.dispatch(NotifConfigLoaded(NotifConfig(enabled = true)))
    countingStore.dispatch(NameUpdated("A profile change outside Hubs"))
    waitForIdle()

    assertEquals(baseline["shell"], counts["shell"], "shell excludes config and profile state")
    assertEquals(baseline["hubs"], counts["hubs"], "Hubs excludes config and profile state")
  }

  @Test
  fun routeSubscriptionsReturnToBaselineAcrossNavigation() = runComposeUiTest {
    val countingStore = CountingStore(createTestAppStore(AppState(route = Route.Hubs)))

    setContent {
      val counts = remember { mutableMapOf<String, Int>() }
      IsolationProbe(countingStore, counts)
    }
    waitForIdle()
    assertEquals(1, countingStore.activeSubscribers, "root SelectorStore shares one store subscription")

    repeat(5) {
      countingStore.dispatch(OpenFeed)
      waitForIdle()
      assertEquals(1, countingStore.activeSubscribers, "root SelectorStore remains subscribed")

      countingStore.dispatch(OpenHubs())
      waitForIdle()
      assertEquals(1, countingStore.activeSubscribers, "active selectors still share one subscription")
    }
  }
}

@Composable
private fun IsolationProbe(store: Store<AppState>, counts: MutableMap<String, Int>) {
  val selectorStore = rememberSelectorStore(store)
  val shell = rememberAppShellState(selectorStore)
  counts["shell"] = (counts["shell"] ?: 0) + 1
  Text(shell.route.name)
  if (shell.route == Route.Hubs) {
    val hubs = rememberHubListViewState(selectorStore)
    counts["hubs"] = (counts["hubs"] ?: 0) + 1
    Text("${hubs.filter}:${hubs.shownHubs.size}")
  }
}

private class CountingStore(private val delegate: Store<AppState>) : Store<AppState> by delegate {
  var activeSubscribers: Int = 0
    private set

  override val subscribe: (StoreSubscriber) -> StoreSubscription = { subscriber ->
    activeSubscribers += 1
    val unsubscribe = delegate.subscribe(subscriber)
    var active = true
    {
      if (active) {
        active = false
        activeSubscribers -= 1
        unsubscribe()
      }
    }
  }
}
