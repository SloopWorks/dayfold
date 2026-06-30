package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone

/**
 * ADR 0043 — Slice 4a/6: nowFeed merges the derived + authored lanes through the ONE engine
 * (the render-time selector, clock + location injected, mirroring feedCards(state, nowIso)).
 * Authored cards gain a bounded `importance` the engine ranks (no author-fixed order), and
 * `not_before` is gated on-device for the authored lane (closes OQ-notbefore-gating).
 */
class NowFeedTest {

  private val zone = TimeZone.UTC
  private val now = "2026-06-30T12:00:00Z"

  private fun state(cards: List<Card> = emptyList(), hubs: List<Hub> = emptyList(), content: NowContent = NowContent()) =
    AppState(cards = cards, hubs = hubs, nowContent = content)

  @Test fun `derived and authored render as peers in one ranked feed`() {
    val hubs = listOf(Hub("h1", title = "Soccer", countdownTo = "2026-07-01"))   // +1 day → SOON
    val card = Card(id = "c1", title = "Rain at soccer 4pm", provenance = Provenance("claude"))
    val feed = nowFeed(state(cards = listOf(card), hubs = hubs), now, null, zone)
    val ids = (feed.now + feed.soon + feed.later + feed.overflow).flatMap { listOf(it.item) + it.collapsedWith }.map { it.id }
    assertTrue("derived:countdown:h1" in ids)
    assertTrue("authored:c1" in ids)
  }

  @Test fun `an authored card whose target matches a hub collapses with the derived countdown`() {
    val hubs = listOf(Hub("h1", title = "Party", countdownTo = "2026-07-02"))
    val card = Card(id = "c1", title = "Ordered groceries?", targetHubId = "h1", provenance = Provenance("claude"))
    val feed = nowFeed(state(cards = listOf(card), hubs = hubs), now, null, zone)
    val heads = feed.now + feed.soon + feed.later + feed.overflow
    assertEquals(1, heads.size)                                  // one merged event unit
    val head = heads.single()
    val all = (listOf(head.item) + head.collapsedWith).map { it.id }.toSet()
    assertEquals(setOf("derived:countdown:h1", "authored:c1"), all)
  }

  @Test fun `not_before in the future gates the authored card off-device`() {
    val future = Card(id = "c1", title = "Later", notBefore = "2026-07-05T00:00:00Z", provenance = Provenance("claude"))
    val past = Card(id = "c2", title = "Now", notBefore = "2026-06-29T00:00:00Z", provenance = Provenance("email"))
    val feed = nowFeed(state(cards = listOf(future, past)), now, null, zone)
    val ids = (feed.now + feed.soon + feed.later + feed.overflow).map { it.item.id }
    assertTrue("authored:c2" in ids)
    assertTrue("authored:c1" !in ids)                            // not yet — gated on-device
  }

  @Test fun `authored provenance maps to the chip token`() {
    val cards = listOf(
      Card(id = "c1", title = "W", kind = "weather"),
      Card(id = "c2", title = "E", provenance = Provenance("email")),
      Card(id = "c3", title = "C", provenance = Provenance("claude")),
      Card(id = "c4", title = "X", provenance = Provenance("https://example.com")),
    )
    val feed = nowFeed(state(cards = cards), now, null, zone)
    val byId = (feed.now + feed.soon + feed.later + feed.overflow).associate { it.item.id to it.item.reasonKind }
    assertEquals(ReasonKind.WEATHER, byId["authored:c1"])
    assertEquals(ReasonKind.EMAIL, byId["authored:c2"])
    assertEquals(ReasonKind.CLAUDE, byId["authored:c3"])
    assertEquals(ReasonKind.EXTERNAL, byId["authored:c4"])
  }

  @Test fun `authored importance lifts an item but stays capped (engine ranks it, no author ordinal)`() {
    val low = Card(id = "lo", title = "Low", importance = 0.1, provenance = Provenance("claude"))
    val high = Card(id = "hi", title = "High", importance = 1.0, provenance = Provenance("claude"))
    val feed = nowFeed(state(cards = listOf(low, high)), now, null, zone)
    val order = (feed.now + feed.soon + feed.later + feed.overflow).map { it.item.id }
    assertEquals(listOf("authored:hi", "authored:lo"), order)    // importance orders them
  }

  @Test fun `nowFeed is pure - identical inputs give identical output`() {
    val s = state(
      cards = listOf(Card(id = "c1", title = "T", provenance = Provenance("claude"))),
      hubs = listOf(Hub("h1", title = "Party", countdownTo = "2026-07-02")),
    )
    assertEquals(nowFeed(s, now, null, zone), nowFeed(s, now, null, zone))
  }
}
