package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
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
    AppState(content = ContentState(cards = cards), hubs = HubState(hubs = hubs), now = NowState(content = content))

  // ── authored card triggers (#299) ────────────────────────────────────────────
  private fun geoCard(id: String, geo: TriggerGeo, vararg extra: BlockTrigger) =
    Card(id = id, kind = "action", title = "F", provenance = Provenance("claude"),
      triggers = listOf(BlockTrigger(geo = geo)) + extra.toList())

  @Test fun `cardToNowItem anchors on the soonest future when-trigger, offset-folded`() {
    val z = TimeZone.currentSystemDefault()
    val card = Card(id = "c1", kind = "action", title = "Firestone", provenance = Provenance("claude"),
      notBefore = "2026-07-08T09:00:00-07:00",
      triggers = listOf(
        BlockTrigger(whenTrigger = TriggerWhen(at = "2026-07-01T10:00:00-07:00")),                       // past → ignored
        BlockTrigger(whenTrigger = TriggerWhen(at = "2026-07-08T10:00:00-07:00", alertOffset = "-PT1H")), // future → 09:00
      ))
    val item = cardToNowItem(card, RankConfig(), "2026-07-08T08:00:00-07:00", z)
    assertEquals(Instant.parse("2026-07-08T09:00:00-07:00"), Instant.parse(item.triggerAtIso!!))
  }

  @Test fun `cardToNowItem falls back to not_before when there is no future when-trigger`() {
    val z = TimeZone.currentSystemDefault()
    val card = Card(id = "c1", kind = "info", title = "X", provenance = Provenance("user"),
      notBefore = "2026-07-08T09:00:00-07:00")
    assertEquals("2026-07-08T09:00:00-07:00", cardToNowItem(card, RankConfig(), "2026-07-08T08:00:00-07:00", z).triggerAtIso)
  }

  @Test fun `authoredGeoItems emits a distinct-id NOW item inside an inline-coord radius`() {
    val card = geoCard("c1", TriggerGeo(lat = 47.7601, lng = -122.6610, radiusM = 800, label = "Firestone Poulsbo"))
    val items = authoredGeoItems(listOf(card), emptyList(), DeviceLocation(47.7605, -122.6612), DeriveConfig(), placeRefOnly = false)
    assertEquals(1, items.size)
    assertEquals("authored:geo:c1:0", items[0].id)
    assertEquals("card:c1", items[0].subjectKey)
    assertTrue(items[0].geoActive)
  }

  @Test fun `authoredGeoItems emits nothing outside radius, null location, or null coords`() {
    val far = geoCard("c1", TriggerGeo(lat = 47.7601, lng = -122.6610, radiusM = 100))
    assertTrue(authoredGeoItems(listOf(far), emptyList(), DeviceLocation(48.0, -122.0), DeriveConfig(), false).isEmpty())
    assertTrue(authoredGeoItems(listOf(far), emptyList(), null, DeriveConfig(), false).isEmpty())
    val noCoords = geoCard("c2", TriggerGeo(label = "nowhere"))
    assertTrue(authoredGeoItems(listOf(noCoords), emptyList(), DeviceLocation(47.76, -122.66), DeriveConfig(), false).isEmpty())
  }

  @Test fun `authoredGeoItems null radiusM falls back to the config default`() {
    val card = geoCard("c1", TriggerGeo(lat = 47.7601, lng = -122.6610))
    assertEquals(1, authoredGeoItems(listOf(card), emptyList(), DeviceLocation(47.7605, -122.6610), DeriveConfig(), false).size)
  }

  @Test fun `authoredGeoItems resolves place_ref coordinates`() {
    val card = geoCard("c1", TriggerGeo(placeRef = "p1", label = "Safeway"))
    val place = Place(id = "p1", kind = "store", label = "Safeway", lat = 47.7601, lng = -122.6610, radiusM = 800)
    assertEquals(1, authoredGeoItems(listOf(card), listOf(place), DeviceLocation(47.7605, -122.6612), DeriveConfig(), false).size)
  }

  @Test fun `placeRefOnly gate (background) drops coord-only geo, keeps place_ref`() {
    val coordOnly = geoCard("c1", TriggerGeo(lat = 47.7601, lng = -122.6610, radiusM = 800))
    val viaPlace = geoCard("c2", TriggerGeo(placeRef = "p1", radiusM = 800))
    val place = Place(id = "p1", kind = "store", label = "Safeway", lat = 47.7601, lng = -122.6610, radiusM = 800)
    val here = DeviceLocation(47.7605, -122.6612)
    assertEquals(listOf("authored:geo:c2:0"), authoredGeoItems(listOf(coordOnly, viaPlace), listOf(place), here, DeriveConfig(), placeRefOnly = true).map { it.id })
    assertEquals(2, authoredGeoItems(listOf(coordOnly, viaPlace), listOf(place), here, DeriveConfig(), placeRefOnly = false).size)
  }

  @Test fun `a card with both when and geo dedups to one NOW subject`() {
    val z = TimeZone.currentSystemDefault(); val n = "2026-07-08T08:00:00-07:00"
    val card = Card(id = "c1", kind = "action", title = "Firestone", provenance = Provenance("claude"),
      triggers = listOf(
        BlockTrigger(whenTrigger = TriggerWhen(at = "2026-07-08T10:00:00-07:00", alertOffset = "-PT1H")),
        BlockTrigger(geo = TriggerGeo(lat = 47.7601, lng = -122.6610, radiusM = 800)),
      ))
    val here = DeviceLocation(47.7605, -122.6612)
    val time = cardToNowItem(card, RankConfig(), n, z)
    val geo = authoredGeoItems(listOf(card), emptyList(), here, DeriveConfig(), false)
    assertEquals(time.subjectKey, geo.single().subjectKey)
    val feed = rank(listOf(time) + geo, n, here, emptyMap(), z, RankConfig())
    assertEquals(1, (feed.now + feed.soon + feed.later + feed.overflow).count { it.item.subjectKey == "card:c1" })
  }

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

  @Test fun `visibleSubjectKeys covers the prominent bands but not the collapsed overflow`() {
    // 7 distinct authored cards > visibleBudget(6) → the lowest-importance one falls to overflow.
    val cards = (1..7).map { Card(id = "c$it", title = "T$it", importance = it / 10.0, provenance = Provenance("claude")) }
    val feed = nowFeed(state(cards = cards), now, null, zone)
    assertTrue(feed.overflow.isNotEmpty())                       // there IS a collapsed tail
    val visible = feed.visibleSubjectKeys()
    (feed.now + feed.soon + feed.later).forEach { assertTrue(it.item.subjectKey in visible) }  // every head covered
    feed.overflow.forEach { assertTrue(it.item.subjectKey !in visible) }                        // tail excluded ("More")
  }

  @Test fun `visibleSubjectKeys includes the dedup peers rendered inset under a head`() {
    val hubs = listOf(Hub("h1", title = "Party", countdownTo = "2026-07-02"))
    val card = Card(id = "c1", title = "Ordered groceries?", targetHubId = "h1", provenance = Provenance("claude"))
    val feed = nowFeed(state(cards = listOf(card), hubs = hubs), now, null, zone)
    val visible = feed.visibleSubjectKeys()
    assertTrue("hub:h1" in visible)                              // the head + its collapsed peer share the key
  }

  @Test fun `nowFeed is pure - identical inputs give identical output`() {
    val s = state(
      cards = listOf(Card(id = "c1", title = "T", provenance = Provenance("claude"))),
      hubs = listOf(Hub("h1", title = "Party", countdownTo = "2026-07-02")),
    )
    assertEquals(nowFeed(s, now, null, zone), nowFeed(s, now, null, zone))
  }
}
