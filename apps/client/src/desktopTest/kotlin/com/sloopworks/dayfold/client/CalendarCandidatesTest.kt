package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone

/**
 * ADR 0063 §2 — the calendar-candidate projection. deriveEventCandidates derives
 * DayfoldEventCandidates ONLY from typed fields (hub start/end, block/card `when.at`
 * triggers, dated milestone blocks). Never parses bodyMd/prose. countdown_to is relevance
 * metadata only — never a second candidate for a hub already covered by start_at.
 */
class CalendarCandidatesTest {

  private val zone = TimeZone.UTC

  // ── source 1: hub start_at/end_at ───────────────────────────────────────────
  @Test fun `hub start_at and end_at become a candidate`() {
    val hubs = listOf(Hub("h1", title = "Ski trip", startAt = "2026-07-10T09:00:00Z", endAt = "2026-07-12T17:00:00Z"))
    val out = deriveEventCandidates(hubs, emptyList(), emptyList(), emptyList(), zone)
    val c = out.single()
    assertEquals("hub:h1", c.subjectKey)
    assertEquals("Ski trip", c.title)
    assertEquals("2026-07-10T09:00:00Z", c.startAt)
    assertEquals("2026-07-12T17:00:00Z", c.endAt)
    assertEquals("h1", c.deepLink?.hubId)
  }

  @Test fun `hub with no start_at produces no candidate`() {
    val hubs = listOf(Hub("h1", title = "Someday", countdownTo = "2026-07-10"))
    assertTrue(deriveEventCandidates(hubs, emptyList(), emptyList(), emptyList(), zone).isEmpty())
  }

  // ── countdown_to is relevance metadata only ─────────────────────────────────
  @Test fun `countdown_to never produces a second candidate for the same hub`() {
    val hubs = listOf(Hub("h1", title = "Party", startAt = "2026-07-10T09:00:00Z", countdownTo = "2026-07-10"))
    val out = deriveEventCandidates(hubs, emptyList(), emptyList(), emptyList(), zone)
    assertEquals(1, out.count { it.subjectKey == "hub:h1" })
  }

  // ── source 2: explicitly dated milestone blocks ─────────────────────────────
  @Test fun `dated milestone block becomes a candidate from typed fields only`() {
    val sections = listOf(HubSection("s1", hubId = "h1"))
    val blocks = listOf(
      HubBlock(
        "b1", sectionId = "s1", type = "milestone",
        bodyMd = "2026-01-01 would be a great date but this is prose",
        payload = BlockPayload(date = "2026-07-04", label = "Passport renewal"),
      ),
    )
    val out = deriveEventCandidates(emptyList(), sections, blocks, emptyList(), zone)
    val c = out.single()
    assertEquals("hub:h1/section:s1/block:b1", c.subjectKey)
    assertEquals("Passport renewal", c.title)
    assertEquals("2026-07-04", c.startAt)
    assertNull(c.endAt)
  }

  @Test fun `milestone block without a typed date produces no candidate`() {
    val sections = listOf(HubSection("s1", hubId = "h1"))
    val blocks = listOf(
      HubBlock("b1", sectionId = "s1", type = "milestone", bodyMd = "Renew passport by 2026-07-04", payload = null),
    )
    assertTrue(deriveEventCandidates(emptyList(), sections, blocks, emptyList(), zone).isEmpty())
  }

  // ── prose is never a source ──────────────────────────────────────────────────
  @Test fun `a prose date in body_md is never extracted as a candidate`() {
    val sections = listOf(HubSection("s1", hubId = "h1"))
    val blocks = listOf(
      HubBlock("b1", sectionId = "s1", type = "text", bodyMd = "Let's meet 2026-07-04 at 3pm at the park"),
    )
    assertTrue(deriveEventCandidates(emptyList(), sections, blocks, emptyList(), zone).isEmpty())
  }

  // ── source 3: block when.at triggers ─────────────────────────────────────────
  @Test fun `a block when-at trigger becomes a candidate`() {
    val sections = listOf(HubSection("s1", hubId = "h1"))
    val blocks = listOf(
      HubBlock(
        "b1", sectionId = "s1", type = "location", bodyMd = "Pickup notes",
        payload = BlockPayload(label = "School gate", address = "123 Main St", lat = 37.77, lng = -122.41),
        triggers = listOf(BlockTrigger(whenTrigger = TriggerWhen(at = "2026-07-04T15:00:00Z"))),
      ),
    )
    val out = deriveEventCandidates(emptyList(), sections, blocks, emptyList(), zone)
    val c = out.single()
    assertEquals("hub:h1/section:s1/block:b1", c.subjectKey)
    assertEquals("School gate", c.title)
    assertEquals("2026-07-04T15:00:00Z", c.startAt)
    assertNull(c.endAt)
    assertEquals("123 Main St", c.location?.address)
    assertEquals(37.77, c.location?.lat)
  }

  @Test fun `a block with no section is skipped (no resolvable hub)`() {
    val blocks = listOf(
      HubBlock("b1", sectionId = null, type = "text", triggers = listOf(BlockTrigger(whenTrigger = TriggerWhen(at = "2026-07-04T15:00:00Z")))),
    )
    assertTrue(deriveEventCandidates(emptyList(), emptyList(), blocks, emptyList(), zone).isEmpty())
  }

  // ── source 4: card when.at triggers ──────────────────────────────────────────
  @Test fun `a card when-at trigger becomes a candidate`() {
    val cards = listOf(
      Card(
        "c1", title = "RSVP reminder", bodyMd = "prose the deriver must ignore",
        targetHubId = "h1", targetSectionId = "s1", targetBlockId = "b1",
        triggers = listOf(BlockTrigger(whenTrigger = TriggerWhen(at = "2026-07-05T18:00:00Z"))),
      ),
    )
    val out = deriveEventCandidates(emptyList(), emptyList(), emptyList(), cards, zone)
    val c = out.single()
    assertEquals("hub:h1/section:s1/block:b1", c.subjectKey)
    assertEquals("RSVP reminder", c.title)
    assertEquals("2026-07-05T18:00:00Z", c.startAt)
  }

  @Test fun `a card with no when-at trigger produces no candidate`() {
    val cards = listOf(Card("c1", title = "Just a card"))
    assertTrue(deriveEventCandidates(emptyList(), emptyList(), emptyList(), cards, zone).isEmpty())
  }

  @Test fun `a card with no hub target has a null deep-link`() {
    val cards = listOf(
      Card("c1", title = "Loose reminder", triggers = listOf(BlockTrigger(whenTrigger = TriggerWhen(at = "2026-07-05T18:00:00Z")))),
    )
    val c = deriveEventCandidates(emptyList(), emptyList(), emptyList(), cards, zone).single()
    assertEquals("card:c1", c.subjectKey)
    assertNull(c.deepLink)
  }

  // ── no attendee/reminder concept ──────────────────────────────────────────────
  // (compile-time invariant: DayfoldEventCandidate has no attendee/reminder field —
  // covered implicitly by the data class shape used throughout this file.)

  // ── allDay / timezone / null end ─────────────────────────────────────────────
  @Test fun `a date-only start_at is treated as all-day, a timestamp is not`() {
    val allDay = deriveEventCandidates(listOf(Hub("h1", title = "Trip", startAt = "2026-07-10")), emptyList(), emptyList(), emptyList(), zone).single()
    assertTrue(allDay.allDay)
    val timed = deriveEventCandidates(listOf(Hub("h2", title = "Meeting", startAt = "2026-07-10T09:00:00Z")), emptyList(), emptyList(), emptyList(), zone).single()
    assertTrue(!timed.allDay)
  }

  @Test fun `timezone is carried through from the injected zone`() {
    val ny = TimeZone.of("America/New_York")
    val c = deriveEventCandidates(listOf(Hub("h1", title = "Trip", startAt = "2026-07-10T09:00:00Z")), emptyList(), emptyList(), emptyList(), ny).single()
    assertEquals("America/New_York", c.timezone)
  }

  @Test fun `missing end time stays null, never invented`() {
    val c = deriveEventCandidates(listOf(Hub("h1", title = "Trip", startAt = "2026-07-10T09:00:00Z")), emptyList(), emptyList(), emptyList(), zone).single()
    assertNull(c.endAt)
  }

  // ── sourceVersion fingerprint ─────────────────────────────────────────────────
  @Test fun `sourceVersion is stable across a body-only edit`() {
    val sections = listOf(HubSection("s1", hubId = "h1"))
    val base = HubBlock("b1", sectionId = "s1", type = "milestone", bodyMd = "original notes", payload = BlockPayload(date = "2026-07-04", label = "Passport"))
    val edited = base.copy(bodyMd = "totally different notes, unrelated to the date")
    val v1 = deriveEventCandidates(emptyList(), sections, listOf(base), emptyList(), zone).single().sourceVersion
    val v2 = deriveEventCandidates(emptyList(), sections, listOf(edited), emptyList(), zone).single().sourceVersion
    assertEquals(v1, v2)
  }

  @Test fun `sourceVersion changes when start_at (a typed input) changes`() {
    val v1 = deriveEventCandidates(listOf(Hub("h1", title = "Trip", startAt = "2026-07-10T09:00:00Z")), emptyList(), emptyList(), emptyList(), zone).single().sourceVersion
    val v2 = deriveEventCandidates(listOf(Hub("h1", title = "Trip", startAt = "2026-07-11T09:00:00Z")), emptyList(), emptyList(), emptyList(), zone).single().sourceVersion
    assertNotEquals(v1, v2)
  }

  // ── determinism ────────────────────────────────────────────────────────────────
  @Test fun `output order is deterministic regardless of input order`() {
    val hubs = listOf(
      Hub("h2", title = "B", startAt = "2026-07-10T09:00:00Z"),
      Hub("h1", title = "A", startAt = "2026-07-09T09:00:00Z"),
    )
    val a = deriveEventCandidates(hubs, emptyList(), emptyList(), emptyList(), zone)
    val b = deriveEventCandidates(hubs.reversed(), emptyList(), emptyList(), emptyList(), zone)
    assertEquals(a, b)
    assertEquals(listOf("hub:h1", "hub:h2"), a.map { it.subjectKey })
  }

  @Test fun `deriveEventCandidates is pure - identical inputs give identical output`() {
    val hubs = listOf(Hub("h1", title = "Trip", startAt = "2026-07-10T09:00:00Z"))
    val a = deriveEventCandidates(hubs, emptyList(), emptyList(), emptyList(), zone)
    val b = deriveEventCandidates(hubs, emptyList(), emptyList(), emptyList(), zone)
    assertEquals(a, b)
  }
}
