package com.sloopworks.dayfold.client

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import com.sloopworks.dayfold.client.cards.CardAction
import com.sloopworks.dayfold.client.cards.hubLinkTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.reduxkotlin.compose.SelectorStore
import org.reduxkotlin.compose.rememberSelectorStore

@OptIn(ExperimentalTestApi::class)
class CardHubNavTest {
  @Test fun `hubLinkTarget prefers target_hub_id, falls back to hub_ref, carries the focus block`() {
    assertEquals("h1" to HubArrival(HubArrivalLevel.BLOCK, "b1", HubArrivalSource.BRIEFING), hubLinkTarget(Card("c", title = "X", targetHubId = "h1", targetBlockId = "b1")))
    assertEquals("hr" to null, hubLinkTarget(Card("c", title = "X", hubRef = "hr")))            // no target_hub_id → hub_ref
    assertEquals("h1" to HubArrival(HubArrivalLevel.BLOCK, "b9", HubArrivalSource.BRIEFING), hubLinkTarget(Card("c", title = "X", targetHubId = "h1", hubRef = "hr", targetBlockId = "b9"))) // target_hub_id wins
    assertEquals("h1" to HubArrival(HubArrivalLevel.SECTION, "s1", HubArrivalSource.BRIEFING), hubLinkTarget(Card("c", title = "X", targetHubId = "h1", targetSectionId = "s1")))
  }

  @Test fun `hubLinkTarget is null when there's no hub to cross to (no link shown)`() {
    assertNull(hubLinkTarget(Card("c", title = "X")))                       // neither target_hub_id nor hub_ref
    assertNull(hubLinkTarget(Card("c", title = "X", targetHubId = "  ")))   // blank id → no deep-link
  }

  @Test fun `OpenHub routes to the Hubs surface + triggers the hub load with the focus block`() = runComposeUiTest {
    val store = createTestAppStore(
      AppState(session = SessionState(activeFamilyId = "family-1"), navigation = NavigationState(route = Route.Feed)),
      debug = false,
    )
    var loadedHub: String? = null; var loadedArrival: HubArrival? = null
    val base = DayfoldCommands.navigationOnly(store)
    val commands = object : DayfoldCommandPort by base {
      override fun openHub(
        familyId: String,
        hubId: String,
        arrival: HubArrival?,
        returnDestination: HubReturnDestination,
      ) {
        store.dispatch(OpenHubs(returnDestination))
        loadedHub = hubId
        loadedArrival = arrival
      }
    }
    lateinit var selectorStore: SelectorStore<AppState>
    setContent { selectorStore = rememberSelectorStore(store) }
    waitForIdle()
    routeCardAction(
      selectorStore,
      commands,
      StablePlatformActions.noOp(),
      activeFamilyId = "family-1",
      fromFeedDetail = false,
      action = CardAction.OpenHub("h_party", HubArrival(HubArrivalLevel.BLOCK, "blk_chk", HubArrivalSource.BRIEFING)),
    )
    assertEquals(Route.Hubs, store.state.navigation.route)   // cross-surface nav (OpenHubs dispatched)
    assertEquals("h_party", loadedHub)            // engine load triggered with the hub id
    assertEquals(HubArrival(HubArrivalLevel.BLOCK, "blk_chk", HubArrivalSource.BRIEFING), loadedArrival)
  }

  @Test fun `OpenDetail still routes to the card detail stack (unchanged)`() = runComposeUiTest {
    val store = createTestAppStore(AppState(content = ContentState(cards = listOf(Card("c1", title = "X")))), debug = false)
    lateinit var selectorStore: SelectorStore<AppState>
    setContent { selectorStore = rememberSelectorStore(store) }
    waitForIdle()
    routeCardAction(
      selectorStore,
      DayfoldCommands.navigationOnly(store),
      StablePlatformActions.noOp(),
      activeFamilyId = null,
      fromFeedDetail = false,
      action = CardAction.OpenDetail("c1"),
    )
    assertEquals(listOf("c1"), store.state.navigation.detailStack)
  }

  @Test fun `command-backed card hub navigation carries the detail return atomically`() = runComposeUiTest {
    val store = createTestAppStore(
      AppState(
        navigation = NavigationState(route = Route.Feed, detailStack = listOf("c1")),
        content = ContentState(cards = listOf(Card("c1", title = "X"))),
        session = SessionState(activeFamilyId = "family-1"),
      ),
      debug = false,
    )

    lateinit var selectorStore: SelectorStore<AppState>
    setContent { selectorStore = rememberSelectorStore(store) }
    waitForIdle()
    routeCardAction(
      store = selectorStore,
      platformActions = StablePlatformActions.noOp(),
      action = CardAction.OpenHub("hub-1", HubArrival(HubArrivalLevel.BLOCK, "block-1", HubArrivalSource.BRIEFING)),
      commands = DayfoldCommands.navigationOnly(store),
      activeFamilyId = "family-1",
      fromFeedDetail = true,
    )

    assertEquals(Route.Hubs, store.state.navigation.route)
    assertEquals(HubReturnDestination.FEED_DETAIL, store.state.hubs.returnDestination)
  }
}
