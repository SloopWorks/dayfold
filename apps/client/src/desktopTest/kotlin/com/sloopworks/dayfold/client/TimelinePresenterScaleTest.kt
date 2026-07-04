package com.sloopworks.dayfold.client
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimelinePresenterScaleTest {
    private val ny = TimeZone.of("America/New_York")

    private fun intraday() = Timeline(tz = "America/New_York", stops = listOf(
        Stop("2026-08-24T08:00:00-04:00", "a"), Stop("2026-08-24T11:00:00-04:00", "b")))

    private fun roadmap() = Timeline(tz = "America/New_York", stops = listOf(
        Stop("2026-05-01", "a"), Stop("2026-06-12", "b"), Stop("2026-07-20", "c"), Stop("2026-09-19", "d")))

    @Test fun `intraday-today selects Day`() {
        assertEquals(TimelineScale.Day, selectScale(intraday(), "2026-08-24T10:00:00-04:00", ny))
    }

    @Test fun `multi-month date-only selects Hub`() {
        assertEquals(TimelineScale.Hub, selectScale(roadmap(), "2026-08-24T10:00:00-04:00", ny))
    }

    // A hub whose "today" is move-in day: an intraday schedule for today AND a multi-month roadmap.
    private fun bothScales() = Timeline(tz = "America/New_York", stops = listOf(
        Stop("2026-05-01", "planning"), Stop("2026-07-01", "deposit"),
        Stop("2026-08-24T08:00:00-04:00", "load"), Stop("2026-08-24T11:00:00-04:00", "elevator"),
        Stop("2026-09-19", "orientation")))

    @Test fun `hasBothScales true when a today-schedule and a multi-month roadmap coexist`() {
        assertEquals(true, hasBothScales(bothScales(), "2026-08-24T10:00:00-04:00", ny))
        // auto-selection still prefers Day when today has intraday stops
        assertEquals(TimelineScale.Day, selectScale(bothScales(), "2026-08-24T10:00:00-04:00", ny))
    }

    @Test fun `hasBothScales false for a pure intraday day and a pure roadmap`() {
        assertEquals(false, hasBothScales(intraday(), "2026-08-24T10:00:00-04:00", ny))
        assertEquals(false, hasBothScales(roadmap(), "2026-08-24T10:00:00-04:00", ny))
    }

    @Test fun `now line only on the focal day when it is today`() {
        val day = stopStatuses(intraday().stops, "2026-08-24T10:00:00-04:00", ny)
        assertEquals(1, nowLineIndex(day, "2026-08-24T10:00:00-04:00", ny)) // after stop[0] (08:00), before stop[1] (11:00)
        assertNull(nowLineIndex(day, "2026-09-01T10:00:00-04:00", ny))
    }

    @Test fun `focalDay is today (the This day scale means today)`() {
        // Even with more intraday stops on a later day, the Day scale is about TODAY.
        val tl = Timeline(tz = "America/New_York", stops = listOf(
            Stop("2026-08-24T08:00:00-04:00", "a"),
            Stop("2026-08-25T08:00:00-04:00", "c"), Stop("2026-08-25T11:00:00-04:00", "d")))
        assertEquals(kotlinx.datetime.LocalDate(2026, 8, 24), focalDay("2026-08-24T10:00:00-04:00", ny))
    }

    // The webinar bug: today has NO intraday-timed stop, but a FUTURE day does. The Day scale
    // must NOT surface that future day under a "This day" label — fall to the roadmap instead.
    private fun futureTimedOnly() = Timeline(tz = "America/New_York", stops = listOf(
        Stop("2026-07-06", "housing"), Stop("2026-07-08", "e-bill"),
        Stop("2026-07-14T19:00:00-04:00", "Living On Campus webinar"),   // future, timed
        Stop("2026-08-01", "deadline cluster")))

    @Test fun `a future timed day does not make today's scale Day`() {
        val today = "2026-07-03T10:00:00-04:00"                          // nothing timed today
        assertEquals(false, dayScaleAvailable(futureTimedOnly(), today, ny))
        assertEquals(TimelineScale.Hub, selectScale(futureTimedOnly(), today, ny))
    }

    @Test fun `Day scale returns the moment today itself has a timed stop`() {
        val timedToday = "2026-07-14T09:00:00-04:00"                     // now IS the webinar day
        assertEquals(true, dayScaleAvailable(futureTimedOnly(), timedToday, ny))
        assertEquals(TimelineScale.Day, selectScale(futureTimedOnly(), timedToday, ny))
    }

    @Test fun `cross-tz injected tz drives day-boundary classification`() {
        // Proves tz-injection: the SAME stops + SAME absolute nowIso yield DIFFERENT
        // nowLineIndex results when different tz values are injected.
        //
        // Stop at 2026-08-25T06:00:00Z:
        //   UTC  → local date Aug 25  (stop is tomorrow when now=Aug 24 UTC)
        //   LA   → local date Aug 24 at 23:00 PDT (stop is tonight, i.e. today, when now=Aug 24 LA)
        //
        // nowIso 2026-08-24T20:00:00Z:
        //   UTC  → today = Aug 24; stop's local date Aug 25 ≠ today → focalMatchesToday = false → null
        //   LA   → today = Aug 24; stop's local date Aug 24 = today  → focalMatchesToday = true  → 0
        //
        // If tz-injection were broken (currentSystemDefault() used instead), both calls would
        // produce the same result and assertNotEquals would fail deterministically.
        val la  = TimeZone.of("America/Los_Angeles")
        val utc = TimeZone.UTC
        val stops  = listOf(Stop("2026-08-25T06:00:00Z", "boundary-stop"))
        val nowIso = "2026-08-24T20:00:00Z"

        val statusesUtc = stopStatuses(stops, nowIso, utc)
        val statusesLa  = stopStatuses(stops, nowIso, la)

        assertNull(nowLineIndex(statusesUtc, nowIso, utc))   // stop is Aug 25 UTC — not today
        assertEquals(0, nowLineIndex(statusesLa,  nowIso, la))  // stop is Aug 24 LA — is today
    }
}
