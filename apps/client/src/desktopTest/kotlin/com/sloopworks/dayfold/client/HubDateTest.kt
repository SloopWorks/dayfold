package com.sloopworks.dayfold.client

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HubDateTest {
  @Test fun formatsAFriendlyBriefingDate() {
    // 2026-01-01 is a Thursday (verified); checks DOW + month names, capitalization, day
    assertEquals("Thursday, January 1", formatDayLabel(LocalDate(2026, 1, 1)))
    assertEquals("Friday, December 25", formatDayLabel(LocalDate(2026, 12, 25)))
  }

  // DB-shaped timestamptz → ISO the parser accepts
  @Test fun normalizesDbTimestamps() {
    assertEquals("2026-06-24T07:23:51.41-07:00", normalizeTs("2026-06-24 07:23:51.41-07"))
    assertEquals("2026-06-24T07:23:51+05:30", normalizeTs("2026-06-24 07:23:51+0530"))
    assertEquals("2026-06-24T07:23:51Z", normalizeTs("2026-06-24 07:23:51Z"))
    assertNull(normalizeTs(null))
    assertNull(normalizeTs("  "))
  }

  private val now = "2026-06-24T12:00:00Z"
  private val utc = kotlinx.datetime.TimeZone.UTC   // pin tz so the test is deterministic
  @Test fun countdownReadsAcrossTheRange() {
    assertEquals("in 12 days", countdownLabel("2026-07-06 12:00:00Z", now, utc))
    assertEquals("Tomorrow", countdownLabel("2026-06-25 13:00:00Z", now, utc))
    assertEquals("Today", countdownLabel("2026-06-24 18:00:00Z", now, utc))
    assertEquals("Yesterday", countdownLabel("2026-06-23 06:00:00Z", now, utc))
    assertEquals("3 days ago", countdownLabel("2026-06-21 06:00:00Z", now, utc))
    assertNull(countdownLabel(null, now, utc))           // no date → no badge
    assertNull(countdownLabel("not-a-date", now, utc))   // junk → no crash, no badge
  }

  @Test fun hubWhenLabelHandlesSpansAndOverrides() {
    // now = 2026-06-24
    assertEquals("Now", hubWhenLabel(null, "2026-06-22T00:00:00Z", "2026-06-28T00:00:00Z", now, utc))        // in progress
    assertEquals("in 3 days", hubWhenLabel(null, "2026-06-27T00:00:00Z", "2026-06-30T00:00:00Z", now, utc))  // upcoming → to start
    assertEquals("4 days ago", hubWhenLabel(null, "2026-06-18T00:00:00Z", "2026-06-20T00:00:00Z", now, utc)) // ended → from end
    assertEquals("Tomorrow", hubWhenLabel("2026-06-25T09:00:00Z", "2026-06-22T00:00:00Z", "2026-06-28T00:00:00Z", now, utc)) // countdown_to wins
    assertEquals("Tomorrow", hubWhenLabel(null, "2026-06-25T09:00:00Z", null, now, utc))                     // start only
    assertNull(hubWhenLabel(null, null, null, now, utc))                                                     // no dates
  }

  @Test fun countdownUsesCalendarDaysNotElapsedHours() {
    // 8pm; an event at 6am the NEXT calendar day is 10h away — calendar-correct is
    // "Tomorrow" (elapsed-hours math wrongly said "Today").
    assertEquals("Tomorrow", countdownLabel("2026-06-25T06:00:00Z", "2026-06-24T20:00:00Z", utc))
    // 22h apart but the SAME calendar day → still "Today"
    assertEquals("Today", countdownLabel("2026-06-24T23:00:00Z", "2026-06-24T01:00:00Z", utc))
  }
}
