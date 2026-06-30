package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone

/**
 * ADR 0043 §2b — Slice 3: the pure Priority & Ordering Engine. score (urgency/proximity/
 * importance/decay) → dedup/collapse by subjectKey → calm budget (now/soon/later bands +
 * overflow that never drops) → deterministic stable order + hysteresis. Local-only surfacing
 * state (last-shown/dismissed) drives anti-nag decay. Pure: clock + location injected.
 */
class NowRankTest {

  private val zone = TimeZone.UTC
  private val now = "2026-06-30T12:00:00Z"

  private fun item(
    id: String, subjectKey: String, origin: Origin = Origin.DERIVED, kind: String = ReasonKind.COUNTDOWN,
    triggerAt: String? = null, weight: Double = 0.5, geoActive: Boolean = false, distanceM: Double? = null,
  ) = NowItem(id = id, origin = origin, reasonKind = kind, title = id, why = id,
    subjectKey = subjectKey, target = null, triggerAtIso = triggerAt, weight = weight,
    geoActive = geoActive, distanceM = distanceM)

  private fun allSurfaced(f: RankedFeed): List<NowItem> =
    (f.now + f.soon + f.later + f.overflow).flatMap { listOf(it.item) + it.collapsedWith }

  @Test fun `dedup collapses items sharing a subjectKey into one head`() {
    val a = item("derived:countdown:h1", "hub:h1", triggerAt = "2026-07-01T12:00:00Z")
    val b = item("authored:c1", "hub:h1", origin = Origin.AUTHORED, weight = 0.4)
    val f = rank(listOf(a, b), now, null, emptyMap(), zone)
    val heads = (f.now + f.soon + f.later + f.overflow)
    assertEquals(1, heads.count { it.item.subjectKey == "hub:h1" })
    assertEquals(1, heads.single().collapsedWith.size)
    // Both lanes still represented (the head + the collapsed peer).
    assertEquals(setOf("derived:countdown:h1", "authored:c1"), allSurfaced(f).map { it.id }.toSet())
  }

  @Test fun `dedup is exact-key only - a hub countdown and a block-level item stay separate`() {
    // Exact-key dedup (not prefix-containment): a hub-level countdown must NOT swallow every block
    // under the hub. The countdown ("hub:h1") and the checklist ("hub:h1/sec:s1/blk:b1") are
    // distinct subjects → two distinct rows.
    val countdown = item("derived:countdown:h1", "hub:h1", triggerAt = "2026-07-01T12:00:00Z")
    val checklist = item("derived:checklist:b1", "hub:h1/sec:s1/blk:b1", kind = ReasonKind.CHECKLIST, triggerAt = "2026-07-01T12:00:00Z")
    val f = rank(listOf(countdown, checklist), now, null, emptyMap(), zone)
    assertEquals(2, (f.now + f.soon + f.later + f.overflow).size)
  }

  @Test fun `an authored item past not_before is calm horizon (LATER), never pinned to NOW`() {
    // not_before is the authored item's trigger; once it has fired (hours ago) the item is live but
    // NOT urgent — it must fall to the horizon, otherwise it pollutes NOW forever and caught-up
    // can never be true.
    val stale = item("authored:c1", "card:c1", origin = Origin.AUTHORED, triggerAt = "2026-06-20T12:00:00Z")
    val f = rank(listOf(stale), now, null, emptyMap(), zone)
    assertTrue(f.now.isEmpty())
    assertTrue(f.caughtUp)
    assertEquals(1, f.soon.size + f.later.size + f.overflow.size)
  }

  @Test fun `two sibling blocks do NOT merge (neither key is a prefix of the other)`() {
    val b1 = item("derived:milestone:b1", "hub:h1/sec:s1/blk:b1", kind = ReasonKind.MILESTONE, triggerAt = "2026-07-01T12:00:00Z")
    val b2 = item("derived:milestone:b2", "hub:h1/sec:s1/blk:b2", kind = ReasonKind.MILESTONE, triggerAt = "2026-07-01T12:00:00Z")
    val f = rank(listOf(b1, b2), now, null, emptyMap(), zone)
    assertEquals(2, (f.now + f.soon + f.later + f.overflow).size)
  }

  @Test fun `geo-active item is emphasized and banded NOW even with no triggerAt`() {
    val geo = item("derived:geo:b1", "hub:h1/sec:s1/blk:b1", kind = ReasonKind.GEO, geoActive = true, distanceM = 30.0)
    val f = rank(listOf(geo), now, DeviceLocation(1.0, 2.0), emptyMap(), zone)
    assertEquals(1, f.now.size)
    assertTrue(f.now.single().emphasized)
    assertFalse(f.caughtUp)
  }

  @Test fun `dismissed subject is omitted entirely`() {
    val a = item("derived:countdown:h1", "hub:h1", triggerAt = "2026-07-01T12:00:00Z")
    val f = rank(listOf(a), now, null, mapOf("hub:h1" to SurfacingRecord("hub:h1", dismissedAtIso = "2026-06-30T11:00:00Z")), zone)
    assertTrue(allSurfaced(f).isEmpty())
    assertTrue(f.caughtUp)
  }

  @Test fun `shown-but-not-acted item softens over time`() {
    val a = item("derived:countdown:h1", "hub:h1", triggerAt = "2026-07-03T12:00:00Z")
    val fresh = rank(listOf(a), now, null, emptyMap(), zone)
    assertFalse(fresh.run { now + soon + later + overflow }.single().softened)
    // shown 2 days ago, never acted → softened
    val stale = rank(listOf(a), now, null, mapOf("hub:h1" to SurfacingRecord("hub:h1", lastShownAtIso = "2026-06-28T12:00:00Z")), zone)
    assertTrue(stale.run { now + soon + later + overflow }.single().softened)
  }

  @Test fun `calm budget overflows the tail but never drops an item`() {
    val items = (1..10).map { item("derived:countdown:h$it", "hub:h$it", triggerAt = "2026-07-0${(it % 5) + 1}T12:00:00Z") }
    val cfg = RankConfig(visibleBudget = 3)
    val f = rank(items, now, null, emptyMap(), zone, cfg)
    val visibleCount = f.now.size + f.soon.size + f.later.size
    assertTrue(visibleCount <= 3, "visible respects budget, was $visibleCount")
    // nothing dropped: every input surfaces somewhere (visible bands or overflow)
    assertEquals(items.map { it.id }.toSet(), allSurfaced(f).map { it.id }.toSet())
  }

  @Test fun `caught-up when nothing is in NOW but the horizon still shows`() {
    val soon = item("derived:countdown:h1", "hub:h1", triggerAt = "2026-07-01T12:00:00Z")   // +24h → SOON
    val f = rank(listOf(soon), now, null, emptyMap(), zone)
    assertTrue(f.now.isEmpty())
    assertTrue(f.caughtUp)
    assertEquals(1, f.soon.size + f.later.size + f.overflow.size)   // horizon preserved, not emptied
  }

  @Test fun `hysteresis - a sub-epsilon score change does not reorder`() {
    // Two items whose scores differ by less than epsilon must keep a stable (id) order.
    val a = item("derived:countdown:hA", "hub:hA", triggerAt = "2026-07-02T12:00:00Z", weight = 0.50)
    val b = item("derived:countdown:hB", "hub:hB", triggerAt = "2026-07-02T12:00:00Z", weight = 0.50 + 0.001)
    val f1 = rank(listOf(a, b), now, null, emptyMap(), zone)
    val f2 = rank(listOf(b, a), now, null, emptyMap(), zone)   // input order swapped
    val order1 = (f1.now + f1.soon + f1.later + f1.overflow).map { it.item.id }
    val order2 = (f2.now + f2.soon + f2.later + f2.overflow).map { it.item.id }
    assertEquals(order1, order2)                               // deterministic regardless of input order
  }

  @Test fun `importance is capped - an authored item cannot pin itself above an urgent one`() {
    val urgent = item("derived:when:b1", "hub:h1/sec:s1/blk:b1", kind = ReasonKind.WHEN, triggerAt = "2026-06-30T12:30:00Z", weight = 0.65)
    val spam = item("authored:c1", "card:c1", origin = Origin.AUTHORED, weight = 100.0)   // attempt to pin to top
    val f = rank(listOf(urgent, spam), now, null, emptyMap(), zone)
    val order = (f.now + f.soon + f.later + f.overflow).map { it.item.id }
    assertEquals("derived:when:b1", order.first())            // urgency wins; importance is clamped
  }

  @Test fun `rank is pure - identical inputs give identical output`() {
    val items = listOf(
      item("derived:countdown:h1", "hub:h1", triggerAt = "2026-07-01T12:00:00Z"),
      item("derived:geo:b1", "hub:h2/sec:s1/blk:b1", kind = ReasonKind.GEO, geoActive = true, distanceM = 50.0),
    )
    assertEquals(rank(items, now, null, emptyMap(), zone), rank(items, now, null, emptyMap(), zone))
  }
}
