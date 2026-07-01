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

    @Test fun `now line only on the focal day when it is today`() {
        val day = stopStatuses(intraday().stops, "2026-08-24T10:00:00-04:00", ny)
        assertEquals(1, nowLineIndex(day, "2026-08-24T10:00:00-04:00", ny)) // after stop[0] (08:00), before stop[1] (11:00)
        assertNull(nowLineIndex(day, "2026-09-01T10:00:00-04:00", ny))
    }

    @Test fun `focalDay tie resolved by today`() {
        // Two dates each have 2 intraday stops; one is today → focalDay returns today
        val tl = Timeline(tz = "America/New_York", stops = listOf(
            Stop("2026-08-24T08:00:00-04:00", "a"), Stop("2026-08-24T11:00:00-04:00", "b"),
            Stop("2026-08-25T08:00:00-04:00", "c"), Stop("2026-08-25T11:00:00-04:00", "d")))
        val result = focalDay(tl, "2026-08-24T10:00:00-04:00", ny)
        assertEquals(kotlinx.datetime.LocalDate(2026, 8, 24), result)
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
