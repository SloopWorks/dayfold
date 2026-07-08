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

  @Test fun formatsAuthoredTimestampsAsWallClock() {
    // shows the AUTHORED local wall-clock (its own offset), not the viewer's tz
    assertEquals("Jul 8, 9:25 AM", formatMetaWhen("2026-07-08T09:25:00-07:00"))
    assertEquals("Jun 21, 3:00 PM", formatMetaWhen("2026-06-21T15:00:00-07:00"))
    assertEquals("Jul 8, 12:00 AM", formatMetaWhen("2026-07-08T00:00:00Z"))   // midnight → 12 AM
    assertEquals("Jul 8, 12:05 PM", formatMetaWhen("2026-07-08T12:05:00Z"))   // noon → 12 PM
    assertEquals("Jun 18", formatMetaWhen("2026-06-18"))                       // date-only → no time
    assertEquals("Jun 24, 7:23 AM", formatMetaWhen("2026-06-24 07:23:51.41-07")) // DB shape + fractional
  }

  @Test fun formatMetaWhenPassesThroughNullAndJunk() {
    assertNull(formatMetaWhen(null))
    assertNull(formatMetaWhen("   "))
    assertEquals("someday", formatMetaWhen("someday"))   // unparseable → unchanged, never blanked
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

  @Test fun handlesProdPostgresCountdownFormat() {
    // the EXACT shape prod (Neon timestamptz) returns for hub.countdown_to: space
    // separator + a bare "+00" offset + (sometimes) microseconds. Regression-locks
    // the live countdown — a normalizeTs tweak that broke "+00" would silently drop it.
    assertEquals("2026-08-01T00:00:00+00:00", normalizeTs("2026-08-01 00:00:00+00"))
    assertEquals("2026-06-26T03:13:30.158911+00:00", normalizeTs("2026-06-26 03:13:30.158911+00"))
    // end-to-end through countdownLabel / hubWhenLabel on the real format
    assertEquals("in 2 days", countdownLabel("2026-06-26 12:00:00+00", now, utc))
    assertEquals("in 38 days", hubWhenLabel("2026-08-01 00:00:00+00", null, null, now, utc)) // "Lillian → Butler"
  }

  @Test fun countdownUsesCalendarDaysNotElapsedHours() {
    // 8pm; an event at 6am the NEXT calendar day is 10h away — calendar-correct is
    // "Tomorrow" (elapsed-hours math wrongly said "Today").
    assertEquals("Tomorrow", countdownLabel("2026-06-25T06:00:00Z", "2026-06-24T20:00:00Z", utc))
    // 22h apart but the SAME calendar day → still "Today"
    assertEquals("Today", countdownLabel("2026-06-24T23:00:00Z", "2026-06-24T01:00:00Z", utc))
  }

  @Test fun labelUsesTheViewersLocalCalendarDateNotUtc() {
    // The SAME two instants must label differently by timezone, because the badge
    // compares LOCAL dates (the whole point of the calendar-day design). Every other
    // test pins UTC, so this is the only guard a tz regression would trip.
    val target = "2026-06-25T20:00:00Z"; val nowInstant = "2026-06-25T05:00:00Z"
    // UTC: now=06-25, target=06-25 → same day → "Today".
    assertEquals("Today", countdownLabel(target, nowInstant, utc))
    // America/Los_Angeles (PDT −07): now→06-24 22:00, target→06-25 13:00 → "Tomorrow".
    val pacific = kotlinx.datetime.TimeZone.of("America/Los_Angeles")
    assertEquals("Tomorrow", countdownLabel(target, nowInstant, pacific))
  }

  @Test fun exactMidnightIsTheCleanCalendarBoundary() {
    // one second before midnight vs midnight itself = different calendar days → "Tomorrow"
    assertEquals("Tomorrow", countdownLabel("2026-06-25T00:00:00Z", "2026-06-24T23:59:59Z", utc))
    // a midnight target earlier the same day → "Today" (not "Yesterday")
    assertEquals("Today", countdownLabel("2026-06-24T00:00:00Z", "2026-06-24T23:59:59Z", utc))
  }

  @Test fun normalizeTsIsLenientAndFailsOpenOnNonsenseOffsets() {
    // The regex reshapes "+2500" → "+25:00" (it doesn't range-check); Instant.parse
    // then rejects the out-of-range offset, so the label degrades to null (no badge,
    // no crash) instead of showing something bogus from a malformed DB row.
    assertEquals("2026-06-24T12:00:00+25:00", normalizeTs("2026-06-24T12:00:00+2500"))
    assertNull(countdownLabel("2026-06-24T12:00:00+2500", now, utc))   // un-parseable → null
    assertNull(countdownLabel("2026-06-24T12:00:00+", now, utc))       // offset sign, no digits → null
  }
}
