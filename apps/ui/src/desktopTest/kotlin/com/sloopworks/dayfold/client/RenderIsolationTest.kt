package com.sloopworks.dayfold.client

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.reduxkotlin.Store
import org.reduxkotlin.StoreSubscriber
import org.reduxkotlin.StoreSubscription
import org.reduxkotlin.compose.rememberSelectorStore
import org.reduxkotlin.compose.selectorState

@OptIn(ExperimentalTestApi::class)
class RenderIsolationTest {

  @Test
  fun memoizedHubFilterOnlyRunsForHubOrFilterInputs() {
    var filterInvocations = 0
    val select = memoizedHubListViewState { filterInvocations++ }
    val initial = AppState(
      hubs = HubState(hubs = listOf(
        Hub(id = "active", title = "Active", status = "active"),
        Hub(id = "planning", title = "Planning", status = "planning"),
      ), filter = "active"),
    )

    assertEquals(listOf("active"), select(initial).shownHubs.map(Hub::id))
    assertEquals(1, filterInvocations)

    select(initial.copy(navigation = initial.navigation.copy(route = Route.Hubs), notifications = initial.notifications.copy(notificationPermission = NotificationPermission.Granted)))
    select(initial.copy(hubs = initial.hubs.copy(busy = true, error = "network")))
    assertEquals(1, filterInvocations, "unrelated AppState changes must reuse the hub projection")

    assertEquals(listOf("planning"), select(initial.copy(hubs = initial.hubs.copy(filter = "planning"))).shownHubs.map(Hub::id))
    assertEquals(2, filterInvocations)

    select(initial.copy(hubs = initial.hubs.copy(hubs = initial.hubs.hubs + Hub(id = "next", title = "Next", status = "active"))))
    assertEquals(3, filterInvocations, "a new hubs list must recalculate the projection")
  }

  @Test
  fun activeSelectorStoreSkipsWorstCaseHubFilteringForUnrelatedDispatches() = runComposeUiTest {
    var filterInvocations = 0
    val select = memoizedHubListViewState { filterInvocations++ }
    val hubs = List(1_000) { index ->
      Hub(id = "hub-$index", title = "Hub $index", status = if (index % 2 == 0) "active" else "planning")
    }
    val countingStore = CountingStore(createTestAppStore(AppState(hubs = HubState(hubs = hubs, filter = "active"))))
    lateinit var shown: HubListViewState

    setContent {
      val selectorStore = rememberSelectorStore(countingStore)
      val selected by selectorStore.selectorState(select)
      shown = selected
      Text(shown.shownHubs.size.toString())
    }
    waitForIdle()
    assertEquals(500, shown.shownHubs.size)
    assertEquals(1, filterInvocations)
    assertEquals(1, countingStore.activeSubscribers)

    countingStore.dispatch(NotifConfigLoaded(NotifConfig(enabled = true)))
    countingStore.dispatch(NameUpdated("Unrelated profile update"))
    waitForIdle()

    assertEquals(1, filterInvocations, "active selectors must not re-filter on unrelated notifications")
    assertEquals(1, countingStore.activeSubscribers)

    countingStore.dispatch(SetHubFilter("planning"))
    waitForIdle()
    assertEquals(2, filterInvocations)
    assertEquals(500, shown.shownHubs.size)
  }

  @Test
  fun unrelatedFeatureChangesDoNotRecomposeShellOrHubRoute() = runComposeUiTest {
    val countingStore = CountingStore(createTestAppStore(AppState(navigation = NavigationState(route = Route.Hubs))))
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
    val countingStore = CountingStore(createTestAppStore(AppState(navigation = NavigationState(route = Route.Hubs))))

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

  @Test
  fun keyedSelectorUpdatesItsCapturedParameterWithoutStaleResults() = runComposeUiTest {
    val store = createTestAppStore(
      AppState(
        hubs = HubState(hubs = listOf(
          Hub(id = "active", title = "Active", status = "active"),
          Hub(id = "planning", title = "Planning", status = "planning"),
        )),
      ),
    )
    var filter by mutableStateOf("active")
    lateinit var selectedIds: List<String>

    setContent {
      val selectorStore = rememberSelectorStore(store)
      val ids by selectorStore.selectorState(filter) { state ->
        state.hubs.hubs.filter { it.status == filter }.map(Hub::id)
      }
      selectedIds = ids
      Text(ids.joinToString())
    }
    waitForIdle()
    assertEquals(listOf("active"), selectedIds)

    filter = "planning"
    waitForIdle()
    assertEquals(listOf("planning"), selectedIds)
  }

  @Test
  fun unrelatedActionDoesNotRecomposeAnInactiveRoute() = runComposeUiTest {
    val store = createTestAppStore(AppState(navigation = NavigationState(route = Route.SignIn)))
    lateinit var counts: MutableMap<String, Int>

    setContent {
      counts = remember { mutableMapOf() }
      RouteProbe(rememberSelectorStore(store), counts)
    }
    waitForIdle()
    assertEquals(1, counts["sign-in"])

    store.dispatch(OpenHubs())
    waitForIdle()
    val signInBeforeUnrelatedAction = counts["sign-in"]
    assertEquals(1, counts["hubs"])

    store.dispatch(NotifConfigLoaded(NotifConfig(enabled = true)))
    waitForIdle()
    assertEquals(signInBeforeUnrelatedAction, counts["sign-in"], "inactive SignIn must stay unsubscribed")
    assertEquals(1, counts["hubs"], "unrelated state must not recompose Hubs")
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

@Composable
private fun RouteProbe(store: org.reduxkotlin.compose.SelectorStore<AppState>, counts: MutableMap<String, Int>) {
  val shell = rememberAppShellState(store)
  if (shell.route == Route.SignIn) {
    val state by store.selectorState(::signInViewState)
    counts["sign-in"] = (counts["sign-in"] ?: 0) + 1
    Text(state.error.orEmpty())
  } else if (shell.route == Route.Hubs) {
    val hubs = rememberHubListViewState(store)
    counts["hubs"] = (counts["hubs"] ?: 0) + 1
    Text(hubs.filter)
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
