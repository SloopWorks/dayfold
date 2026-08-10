package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * CAL-8 (ADR 0063 §1/§3) — the pure fns behind AndroidCalendarPort: cursor-row→observation
 * mapping, account-label masking, RRULE→isRecurring, and the ACTION_INSERT extras builder. No
 * android.database.Cursor/Intent involved — see CalendarProviderMapping.kt's file kdoc for why.
 */
class CalendarProviderMappingTest {

  // ── isRecurringRrule ──

  @Test fun `blank or null rrule is not recurring`() {
    assertFalse(isRecurringRrule(null))
    assertFalse(isRecurringRrule(""))
    assertFalse(isRecurringRrule("   "))
  }

  @Test fun `a present rrule is recurring`() {
    assertTrue(isRecurringRrule("FREQ=WEEKLY;COUNT=10"))
  }

  // ── maskAccountLabel ──

  @Test fun `email-shaped account label keeps first char and domain`() {
    assertEquals("p•••@gmail.com", maskAccountLabel("pat@gmail.com"))
  }

  @Test fun `non-email account label keeps only its first char`() {
    assertEquals("W•••", maskAccountLabel("Work"))
  }

  @Test fun `blank account label is returned unchanged`() {
    assertEquals("", maskAccountLabel(""))
  }

  // ── epochMillisToObservationIso ──

  @Test fun `an all-day millis formats as a bare UTC date`() {
    val millis = Instant.parse("2026-06-27T00:00:00Z").toEpochMilliseconds()
    assertEquals("2026-06-27", epochMillisToObservationIso(millis, allDay = true))
  }

  @Test fun `a timed millis formats as a full instant string`() {
    val millis = Instant.parse("2026-06-27T22:00:00Z").toEpochMilliseconds()
    assertEquals("2026-06-27T22:00:00Z", epochMillisToObservationIso(millis, allDay = false))
  }

  // ── mapCalendarInstanceRow ──

  @Test fun `a non-recurring row maps with a null recurrenceId`() {
    val row = CalendarInstanceRow(
      instanceId = "inst-1", eventId = "evt-1", calendarId = "cal-a", title = "Ski trip",
      beginMillis = Instant.parse("2026-07-10T09:00:00Z").toEpochMilliseconds(),
      endMillis = Instant.parse("2026-07-10T11:00:00Z").toEpochMilliseconds(),
      allDay = false, eventTimezone = "America/Los_Angeles", eventLocation = "Tahoe", rrule = null,
    )
    val obs = mapCalendarInstanceRow(row)
    assertEquals("inst-1", obs.platformEventId)
    assertEquals("cal-a", obs.calendarId)
    assertEquals("Ski trip", obs.title)
    assertEquals("2026-07-10T09:00:00Z", obs.startAt)
    assertEquals("2026-07-10T11:00:00Z", obs.endAt)
    assertFalse(obs.allDay)
    assertEquals("America/Los_Angeles", obs.timezone)
    assertEquals("Tahoe", obs.location?.address)
    assertFalse(obs.isRecurring)
    assertNull(obs.recurrenceId)
  }

  @Test fun `a recurring row carries the event id as its recurrenceId`() {
    val row = CalendarInstanceRow(
      instanceId = "inst-2", eventId = "evt-2", calendarId = "cal-a", title = "Standup",
      beginMillis = Instant.parse("2026-07-10T16:00:00Z").toEpochMilliseconds(), endMillis = null,
      allDay = false, eventTimezone = "UTC", eventLocation = null, rrule = "FREQ=DAILY",
    )
    val obs = mapCalendarInstanceRow(row)
    assertTrue(obs.isRecurring)
    assertEquals("evt-2", obs.recurrenceId)
    assertNull(obs.endAt)
    assertNull(obs.location)
  }

  @Test fun `an all-day row formats start and end as bare dates and forces UTC timezone`() {
    val row = CalendarInstanceRow(
      instanceId = "inst-3", eventId = "evt-3", calendarId = "cal-a", title = "Birthday",
      beginMillis = Instant.parse("2026-08-15T00:00:00Z").toEpochMilliseconds(),
      endMillis = Instant.parse("2026-08-16T00:00:00Z").toEpochMilliseconds(),
      allDay = true, eventTimezone = "America/Los_Angeles", eventLocation = null, rrule = null,
    )
    val obs = mapCalendarInstanceRow(row)
    assertEquals("2026-08-15", obs.startAt)
    assertEquals("2026-08-16", obs.endAt)
    assertEquals("UTC", obs.timezone)
  }

  @Test fun `a blank event location does not produce a location`() {
    val row = CalendarInstanceRow(
      instanceId = "inst-4", eventId = "evt-4", calendarId = "cal-a", title = "Call",
      beginMillis = Instant.parse("2026-07-10T09:00:00Z").toEpochMilliseconds(), endMillis = null,
      allDay = false, eventTimezone = "UTC", eventLocation = "   ", rrule = null,
    )
    assertNull(mapCalendarInstanceRow(row).location)
  }

  // ── eventEditorIntentSpec ──

  @Test fun `a timed prefill with no end and a deep link builds the expected extras`() {
    val prefill = EventPrefill(
      title = "Maya's dance recital",
      startAt = "2026-06-27T15:00:00-07:00",
      endAt = null,
      allDay = false,
      timezone = "America/Los_Angeles",
      location = CandidateLocation(address = "410 Alder St"),
      deepLink = DeepLinkTarget("hub-1"),
    )
    val spec = eventEditorIntentSpec(prefill)
    assertEquals(EVENT_EDITOR_ACTION_INSERT, spec.action)
    assertEquals(EVENT_EDITOR_EVENTS_URI, spec.dataUri)
    assertEquals("Maya's dance recital", spec.extras["title"])
    assertEquals(
      Instant.parse("2026-06-27T22:00:00Z").toEpochMilliseconds(),
      spec.extras["beginTime"],
    )
    assertFalse("endTime" in spec.extras, "no endAt on the prefill must mean no EXTRA_EVENT_END_TIME")
    assertEquals(false, spec.extras["allDay"])
    assertEquals("America/Los_Angeles", spec.extras["eventTimezone"])
    assertEquals("410 Alder St", spec.extras["eventLocation"])
    val description = spec.extras["description"] as String
    assertTrue("Added from Dayfold." in description)
    assertTrue("family-ai-dashboard.vercel.app/h/hub-1" in description)
  }

  @Test fun `an all-day prefill with no location or deep link omits those extras`() {
    val prefill = EventPrefill(
      title = "Birthday", startAt = "2026-08-15", endAt = "2026-08-16",
      allDay = true, timezone = "UTC", location = null, deepLink = null,
    )
    val spec = eventEditorIntentSpec(prefill)
    assertEquals(true, spec.extras["allDay"])
    assertFalse("eventLocation" in spec.extras)
    assertEquals("Added from Dayfold.", spec.extras["description"])
    assertTrue("beginTime" in spec.extras)
    assertTrue("endTime" in spec.extras)
  }

  @Test fun `never carries an attendee or reminder extra`() {
    val prefill = EventPrefill("x", "2026-08-15", null, true, "UTC")
    val spec = eventEditorIntentSpec(prefill)
    assertTrue(spec.extras.keys.none { it.contains("attendee", ignoreCase = true) || it.contains("reminder", ignoreCase = true) })
  }
}
