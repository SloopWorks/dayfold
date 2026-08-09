package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR 0063 §5 — the ONE calm aggregate Now unit + the all-clear/stale footer datum. Both read
 * only the already-computed CalendarCheckState (CalendarCheckEngine owns the reconciliation
 * effects, ADR 0058) — no I/O, no clock, pure.
 */
class CalendarCheckNowTest {

  private fun candidate(subjectKey: String, title: String) =
    DayfoldEventCandidate(
      subjectKey = subjectKey, title = title, startAt = "2026-08-10T09:00:00Z", endAt = null,
      allDay = false, timezone = "UTC", location = null, sourceVersion = "v1", deepLink = null,
    )

  private fun observation(id: String, title: String) =
    CalendarEventObservation(
      platformEventId = id, calendarId = "cal-1", title = title, startAt = "2026-08-11T09:00:00Z",
      endAt = null, allDay = false, timezone = "UTC", location = null, isRecurring = false,
    )

  // ── aggregate unit ──────────────────────────────────────────────────────────

  @Test fun `five unresolved items across buckets collapse into one unit with total count and 3 previews`() {
    val results = ReconcileResult(
      dayfoldOnly = listOf(candidate("hub:h1", "Dentist"), candidate("hub:h2", "Recital")),
      calendarOnly = listOf(observation("evt-1", "Book club")),
      suggested = listOf(SuggestedMatch(candidate("hub:h3", "Soccer"), observation("evt-2", "Soccer practice"), emptyList())),
      ambiguous = listOf(AmbiguousMatch(candidate("hub:h4", "Board meeting"), listOf(observation("evt-3", "Board meeting")))),
    )
    val check = CalendarCheckState(results = results)

    val item = deriveCalendarCheckNow(check)

    assertEquals(5, item?.calendarCheckCount)
    assertEquals(3, item?.calendarCheckPreviews?.size)
    assertEquals(ReasonKind.CALENDAR_CHECK, item?.reasonKind)
    assertEquals(Origin.DERIVED, item?.origin)
  }

  @Test fun `never carries a trigger or urgency boost`() {
    val check = CalendarCheckState(results = ReconcileResult(dayfoldOnly = listOf(candidate("hub:h1", "Dentist"))))
    val item = deriveCalendarCheckNow(check, DeriveConfig(calendarCheckWeight = 0.35))
    assertNull(item?.triggerAtIso)
    assertEquals(0.35, item?.weight)
    assertEquals(false, item?.geoActive)
  }

  @Test fun `zero unresolved items produce no aggregate unit`() {
    val check = CalendarCheckState(results = ReconcileResult())
    assertNull(deriveCalendarCheckNow(check))
  }

  @Test fun `preview rows carry title and gap kind, in a fixed bucket order`() {
    val results = ReconcileResult(
      calendarOnly = listOf(observation("evt-1", "Book club")),
      dayfoldOnly = listOf(candidate("hub:h1", "Dentist")),
    )
    val previews = deriveCalendarCheckNow(CalendarCheckState(results = results))?.calendarCheckPreviews
    assertEquals(
      listOf(
        CalendarCheckPreview("Dentist", CalendarGapKind.DAYFOLD_ONLY),
        CalendarCheckPreview("Book club", CalendarGapKind.CALENDAR_ONLY),
      ),
      previews,
    )
  }

  // ── all-clear / stale footer ────────────────────────────────────────────────

  @Test fun `zero unresolved items after a real check produce an all-clear footer`() {
    val check = CalendarCheckState(results = ReconcileResult(), stale = false)
    val footer = calendarCheckFooter(check, lastSuccessfulCheckAtIso = "2026-08-09T09:00:00Z")
    assertEquals(CalendarCheckFooter(allClear = true, lastSuccessfulCheckAtIso = "2026-08-09T09:00:00Z", stale = false), footer)
  }

  @Test fun `a stale short-circuited pass footers the last successful check, never a fresh all-clear`() {
    // The pass just short-circuited (offline/permission/feature-off) — results stay empty, but
    // the state's OWN lastCheckAt is the stale attempt's clock, not a real compare. The caller
    // must pass the DB-persisted CalendarSettings.lastCheckAt (the last REAL comparison) instead.
    val check = CalendarCheckState(results = ReconcileResult(), stale = true, lastCheckAt = "2026-08-09T12:00:00Z")
    val footer = calendarCheckFooter(check, lastSuccessfulCheckAtIso = "2026-08-05T09:00:00Z")

    assertEquals(false, footer?.allClear)
    assertEquals(true, footer?.stale)
    assertEquals("2026-08-05T09:00:00Z", footer?.lastSuccessfulCheckAtIso)
  }

  @Test fun `never run at all produces no footer`() {
    val check = CalendarCheckState(results = ReconcileResult(), stale = true)
    assertNull(calendarCheckFooter(check, lastSuccessfulCheckAtIso = null))
  }

  @Test fun `unresolved items suppress the footer - the aggregate card covers that state instead`() {
    val check = CalendarCheckState(results = ReconcileResult(dayfoldOnly = listOf(candidate("hub:h1", "Dentist"))))
    assertNull(calendarCheckFooter(check, lastSuccessfulCheckAtIso = "2026-08-09T09:00:00Z"))
  }

  @Test fun `aggregate unit and footer are mutually exclusive for the same state`() {
    val withGap = CalendarCheckState(results = ReconcileResult(dayfoldOnly = listOf(candidate("hub:h1", "Dentist"))))
    assertTrue(deriveCalendarCheckNow(withGap) != null)
    assertNull(calendarCheckFooter(withGap, "2026-08-09T09:00:00Z"))

    val clear = CalendarCheckState(results = ReconcileResult())
    assertNull(deriveCalendarCheckNow(clear))
    assertTrue(calendarCheckFooter(clear, "2026-08-09T09:00:00Z") != null)
  }
}
