package com.sloopworks.dayfold.client

import com.sloopworks.dayfold.client.cards.CardAction
import com.sloopworks.dayfold.client.cards.hubLinkTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CardHubNavTest {
  @Test fun `hubLinkTarget prefers target_hub_id, falls back to hub_ref, carries the focus block`() {
    assertEquals("h1" to "b1", hubLinkTarget(Card("c", title = "X", targetHubId = "h1", targetBlockId = "b1")))
    assertEquals("hr" to null, hubLinkTarget(Card("c", title = "X", hubRef = "hr")))            // no target_hub_id → hub_ref
    assertEquals("h1" to "b9", hubLinkTarget(Card("c", title = "X", targetHubId = "h1", hubRef = "hr", targetBlockId = "b9"))) // target_hub_id wins
  }

  @Test fun `hubLinkTarget is null when there's no hub to cross to (no link shown)`() {
    assertNull(hubLinkTarget(Card("c", title = "X")))                       // neither target_hub_id nor hub_ref
    assertNull(hubLinkTarget(Card("c", title = "X", targetHubId = "  ")))   // blank id → no deep-link
  }

  @Test fun `OpenHub routes to the Hubs surface + triggers the hub load with the focus block`() {
    val store = createTestAppStore(
      AppState(route = Route.Feed, activeFamilyId = "family-1"),
      debug = false,
    )
    var loadedHub: String? = null; var loadedFocus: String? = "UNSET"
    val base = StableDayfoldCommands(DayfoldCommands.navigationOnly(store))
    val commands = object : StableDayfoldCommands by base {
      override fun openHub(
        familyId: String,
        hubId: String,
        focusBlockId: String?,
        returnDestination: HubReturnDestination,
      ) {
        store.dispatch(OpenHubs(returnDestination))
        loadedHub = hubId
        loadedFocus = focusBlockId
      }
    }
    routeCardAction(
      store,
      commands,
      StablePlatformActions.noOp(),
      CardAction.OpenHub("h_party", "blk_chk"),
    )
    assertEquals(Route.Hubs, store.state.route)   // cross-surface nav (OpenHubs dispatched)
    assertEquals("h_party", loadedHub)            // engine load triggered with the hub id
    assertEquals("blk_chk", loadedFocus)          // + the deep-link focus block (arrival highlight)
  }

  @Test fun `OpenDetail still routes to the card detail stack (unchanged)`() {
    val store = createTestAppStore(AppState(cards = listOf(Card("c1", title = "X"))), debug = false)
    routeCardAction(
      store,
      StableDayfoldCommands(DayfoldCommands.navigationOnly(store)),
      StablePlatformActions.noOp(),
      CardAction.OpenDetail("c1"),
    )
    assertEquals(listOf("c1"), store.state.detailStack)
  }

  @Test fun `command-backed card hub navigation carries the detail return atomically`() {
    val store = createTestAppStore(
      AppState(
        route = Route.Feed,
        cards = listOf(Card("c1", title = "X")),
        detailStack = listOf("c1"),
        activeFamilyId = "family-1",
      ),
      debug = false,
    )

    routeCardAction(
      store = store,
      platformActions = StablePlatformActions.noOp(),
      action = CardAction.OpenHub("hub-1", "block-1"),
      commands = StableDayfoldCommands(DayfoldCommands.navigationOnly(store)),
    )

    assertEquals(Route.Hubs, store.state.route)
    assertEquals(true, store.state.hubFromDetail)
  }
}
