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
    val store = createAppStore(AppState(route = Route.Feed), debug = false)
    var loadedHub: String? = null; var loadedFocus: String? = "UNSET"
    routeCardAction(store, onPlatformAction = {}, CardAction.OpenHub("h_party", "blk_chk"),
      onOpenHub = { id, focus -> loadedHub = id; loadedFocus = focus })
    assertEquals(Route.Hubs, store.state.route)   // cross-surface nav (OpenHubs dispatched)
    assertEquals("h_party", loadedHub)            // engine load triggered with the hub id
    assertEquals("blk_chk", loadedFocus)          // + the deep-link focus block (arrival highlight)
  }

  @Test fun `OpenDetail still routes to the card detail stack (unchanged)`() {
    val store = createAppStore(AppState(cards = listOf(Card("c1", title = "X"))), debug = false)
    routeCardAction(store, onPlatformAction = {}, CardAction.OpenDetail("c1"))
    assertEquals(listOf("c1"), store.state.detailStack)
  }
}
