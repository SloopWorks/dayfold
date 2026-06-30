package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone

/**
 * ADR 0043 Phase A — Slice 2: pure on-device deriveNow. Covers all five reason_kinds
 * (countdown, milestone, checklist-due, geo-proximity, time-window/when), each with a
 * computed "why" string. Pure: clock + location injected (mirrors feedCards(state, nowIso));
 * no wall-clock, no randomness. Date-only values (YYYY-MM-DD) must parse (the common case).
 */
class DeriveNowTest {

  private val zone = TimeZone.UTC
  private val now = "2026-06-30T12:00:00Z"

  // ── countdown ──────────────────────────────────────────────────────────────
  @Test fun `countdown emits within window with computed why`() {
    val hubs = listOf(Hub("h1", title = "Maya's party", countdownTo = "2026-07-02"))   // +2 days
    val items = deriveNow(hubs, emptyList(), emptyList(), emptyList(), now, null, zone)
    val it = items.single { it.reasonKind == ReasonKind.COUNTDOWN }
    assertEquals(Origin.DERIVED, it.origin)
    assertEquals("Maya's party in 2 days", it.why)
    assertEquals("hub:h1", it.subjectKey)
    assertEquals("h1", it.target?.hubId)
  }

  @Test fun `countdown why uses today and tomorrow wording`() {
    val today = deriveNow(listOf(Hub("h1", title = "Recital", countdownTo = "2026-06-30")), emptyList(), emptyList(), emptyList(), now, null, zone)
    assertEquals("Recital today", today.single { it.reasonKind == ReasonKind.COUNTDOWN }.why)
    val tomorrow = deriveNow(listOf(Hub("h2", title = "Recital", countdownTo = "2026-07-01")), emptyList(), emptyList(), emptyList(), now, null, zone)
    assertEquals("Recital tomorrow", tomorrow.single { it.reasonKind == ReasonKind.COUNTDOWN }.why)
  }

  @Test fun `countdown does not emit before window or in the past`() {
    val far = deriveNow(listOf(Hub("h1", title = "Trip", countdownTo = "2026-09-01")), emptyList(), emptyList(), emptyList(), now, null, zone)
    assertTrue(far.none { it.reasonKind == ReasonKind.COUNTDOWN })
    val past = deriveNow(listOf(Hub("h2", title = "Done", countdownTo = "2026-06-28")), emptyList(), emptyList(), emptyList(), now, null, zone)
    assertTrue(past.none { it.reasonKind == ReasonKind.COUNTDOWN })
  }

  // ── milestone ──────────────────────────────────────────────────────────────
  @Test fun `milestone emits on an approaching dated block`() {
    val sections = listOf(HubSection("s1", hubId = "h1", title = "Plan"))
    val blocks = listOf(HubBlock("b1", sectionId = "s1", type = "milestone", bodyMd = "Passport renewal", payload = BlockPayload(date = "2026-07-04")))
    val items = deriveNow(listOf(Hub("h1", title = "Trip")), sections, blocks, emptyList(), now, null, zone)
    val it = items.single { it.reasonKind == ReasonKind.MILESTONE }
    assertEquals("Passport renewal in 4 days", it.why)
    assertEquals("hub:h1/sec:s1/blk:b1", it.subjectKey)
    assertEquals("b1", it.target?.blockId)
  }

  // ── checklist-due ────────────────────────────────────────────────────────────
  @Test fun `checklist counts remaining only when the hub event is within window`() {
    val sections = listOf(HubSection("s1", hubId = "h1", title = "To do"))
    val items3 = listOf(ChecklistItem(id = "i1", text = "a", done = true), ChecklistItem(id = "i2", text = "b"), ChecklistItem(id = "i3", text = "c"))
    val blocks = listOf(HubBlock("b1", sectionId = "s1", type = "checklist", payload = BlockPayload(items = items3)))
    // Event in 2 days → within the 7-day checklist window → surfaces "2 left".
    val near = deriveNow(listOf(Hub("h1", title = "Party", countdownTo = "2026-07-02")), sections, blocks, emptyList(), now, null, zone)
    val c = near.single { it.reasonKind == ReasonKind.CHECKLIST }
    assertEquals("2 left before Party", c.why)
    assertEquals("b1", c.target?.blockId)
    // Event 40 days out → no checklist nudge (not noisy).
    val far = deriveNow(listOf(Hub("h1", title = "Party", countdownTo = "2026-08-09")), sections, blocks, emptyList(), now, null, zone)
    assertTrue(far.none { it.reasonKind == ReasonKind.CHECKLIST })
  }

  @Test fun `checklist all done emits nothing`() {
    val sections = listOf(HubSection("s1", hubId = "h1"))
    val blocks = listOf(HubBlock("b1", sectionId = "s1", type = "checklist",
      payload = BlockPayload(items = listOf(ChecklistItem(id = "i1", done = true)))))
    val items = deriveNow(listOf(Hub("h1", title = "Party", countdownTo = "2026-07-02")), sections, blocks, emptyList(), now, null, zone)
    assertTrue(items.none { it.reasonKind == ReasonKind.CHECKLIST })
  }

  // ── geo-proximity ────────────────────────────────────────────────────────────
  @Test fun `geo emits only when location within radius`() {
    val sections = listOf(HubSection("s1", hubId = "h1"))
    val blocks = listOf(HubBlock("b1", sectionId = "s1", type = "location",
      triggers = listOf(BlockTrigger(geo = TriggerGeo(label = "Safeway", placeRef = "p1", radiusM = 180)))))
    val places = listOf(Place("p1", kind = "store", label = "Safeway", lat = 37.7700, lng = -122.4100, radiusM = 180))
    val hubs = listOf(Hub("h1", title = "Errand"))
    // Standing essentially at the place → within radius → emits.
    val near = deriveNow(hubs, sections, blocks, places, now, DeviceLocation(37.7701, -122.4101), zone)
    val g = near.single { it.reasonKind == ReasonKind.GEO }
    assertEquals("You're near Safeway", g.why)
    assertTrue(g.geoActive)
    assertNotNull(g.distanceM)
    assertEquals("b1", g.target?.blockId)
    // A few km away → omitted.
    val far = deriveNow(hubs, sections, blocks, places, now, DeviceLocation(37.80, -122.45), zone)
    assertTrue(far.none { it.reasonKind == ReasonKind.GEO })
  }

  @Test fun `geo emits nothing when location is unknown (foreground, no permission)`() {
    val sections = listOf(HubSection("s1", hubId = "h1"))
    val blocks = listOf(HubBlock("b1", sectionId = "s1", type = "location",
      triggers = listOf(BlockTrigger(geo = TriggerGeo(label = "Safeway", lat = 37.77, lng = -122.41, radiusM = 180)))))
    val items = deriveNow(listOf(Hub("h1", title = "Errand")), sections, blocks, emptyList(), now, null, zone)
    assertTrue(items.none { it.reasonKind == ReasonKind.GEO })
  }

  // ── time-window (when) ─────────────────────────────────────────────────────────
  @Test fun `when emits within the minute window with a clock time why`() {
    val sections = listOf(HubSection("s1", hubId = "h1"))
    val blocks = listOf(HubBlock("b1", sectionId = "s1", type = "milestone", bodyMd = "Pickup",
      triggers = listOf(BlockTrigger(whenTrigger = TriggerWhen(at = "2026-06-30T13:30:00Z")))))   // +90 min
    val items = deriveNow(listOf(Hub("h1", title = "School")), sections, blocks, emptyList(), now, null, zone)
    val w = items.single { it.reasonKind == ReasonKind.WHEN }
    assertEquals("Pickup at 1:30", w.why)
    assertEquals("b1", w.target?.blockId)
    // 5 hours out → beyond the 120-min window → no item.
    val late = deriveNow(listOf(Hub("h1", title = "School")), sections,
      listOf(HubBlock("b1", sectionId = "s1", type = "milestone", bodyMd = "Pickup",
        triggers = listOf(BlockTrigger(whenTrigger = TriggerWhen(at = "2026-06-30T17:00:00Z"))))),
      emptyList(), now, null, zone)
    assertTrue(late.none { it.reasonKind == ReasonKind.WHEN })
  }

  // ── purity + invariants ────────────────────────────────────────────────────────
  @Test fun `deriveNow is pure - identical inputs give identical output`() {
    val hubs = listOf(Hub("h1", title = "Party", countdownTo = "2026-07-02"))
    val a = deriveNow(hubs, emptyList(), emptyList(), emptyList(), now, DeviceLocation(1.0, 2.0), zone)
    val b = deriveNow(hubs, emptyList(), emptyList(), emptyList(), now, DeviceLocation(1.0, 2.0), zone)
    assertEquals(a, b)
  }

  @Test fun `every item has a stable id and at most one item per source node`() {
    val sections = listOf(HubSection("s1", hubId = "h1"))
    val blocks = listOf(
      HubBlock("b1", sectionId = "s1", type = "milestone", bodyMd = "M", payload = BlockPayload(date = "2026-07-03")),
      HubBlock("b2", sectionId = "s1", type = "checklist", payload = BlockPayload(items = listOf(ChecklistItem(id = "i", text = "x")))),
    )
    val items = deriveNow(listOf(Hub("h1", title = "Party", countdownTo = "2026-07-02")), sections, blocks, emptyList(), now, null, zone)
    assertEquals(items.map { it.id }.toSet().size, items.size)            // unique ids
    items.forEach { assertTrue(it.id.startsWith("derived:")) }
    // one countdown (h1), one milestone (b1), one checklist (b2)
    assertEquals(setOf("hub:h1", "hub:h1/sec:s1/blk:b1", "hub:h1/sec:s1/blk:b2"), items.map { it.subjectKey }.toSet())
  }

  @Test fun `date-only and full-instant countdown both parse`() {
    val dateOnly = deriveNow(listOf(Hub("h1", title = "A", countdownTo = "2026-07-01")), emptyList(), emptyList(), emptyList(), now, null, zone)
    val instant = deriveNow(listOf(Hub("h2", title = "B", countdownTo = "2026-07-01T09:00:00Z")), emptyList(), emptyList(), emptyList(), now, null, zone)
    assertNotNull(dateOnly.singleOrNull { it.reasonKind == ReasonKind.COUNTDOWN })
    assertNotNull(instant.singleOrNull { it.reasonKind == ReasonKind.COUNTDOWN })
  }
}
