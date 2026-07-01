package com.sloopworks.dayfold.client

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TimelinePresenterWindowTest {
    private val ny = TimeZone.of("America/New_York")

    private fun day() = Timeline(
        tz = "America/New_York",
        stops = listOf(
            Stop("2026-08-24T07:30:00-04:00", "load"),
            Stop("2026-08-24T08:00:00-04:00", "left"),
            Stop("2026-08-24T09:50:00-04:00", "checkin"),
            Stop("2026-08-24T11:00:00-04:00", "elevator"),
            Stop("2026-08-24T12:30:00-04:00", "lunch"),
            Stop("2026-08-24T14:00:00-04:00", "bookstore"),
            Stop("2026-08-24T16:30:00-04:00", "goodbye"),
        )
    )

    private fun hubMonths6() = Timeline(
        tz = "America/New_York",
        stops = listOf(
            Stop("2026-05-01", "may"), Stop("2026-06-01", "jun"), Stop("2026-07-01", "jul"),
            Stop("2026-09-01", "sep"), Stop("2026-10-01", "oct"), Stop("2026-11-01", "nov"),
        ),
    )

    @Test fun `day card windows done-cap + next3 + tail`() {
        val c = presentTimelineCard(day(), "2026-08-24T10:40:00-04:00", ny)!!
        assertEquals(TimelineScale.Day, c.scale)
        assertEquals(3, c.doneCount)   // 07:30, 08:00, 09:50
        assertEquals(3, c.window.size) // 11:00, 12:30, 14:00
        assertEquals(1, c.tailCount)   // 16:30
    }

    @Test fun `empty timeline returns null card`() {
        assertNull(presentTimelineCard(Timeline(tz = "UTC", stops = emptyList()), "2026-08-24T10:40:00-04:00", ny))
    }

    @Test fun `roadmap over 6 months collapses the leading done-run into one checkmark-N node`() {
        // 8 distinct months. Jan–Apr are past (Done), Sep–Dec are future (Upcoming/Next).
        val stops = listOf(
            Stop("2026-01-01", "jan"), Stop("2026-02-01", "feb"),
            Stop("2026-03-01", "mar"), Stop("2026-04-01", "apr"),
            Stop("2026-09-01", "sep"), Stop("2026-10-01", "oct"),
            Stop("2026-11-01", "nov"), Stop("2026-12-01", "dec"),
        )
        val tl = Timeline(tz = "America/New_York", stops = stops)
        val c = presentTimelineCard(tl, "2026-08-24T10:00:00-04:00", ny)!!
        assertEquals(TimelineScale.Hub, c.scale)
        // leading Done-run of 4 (>2) collapses → [✓4, SEP, OCT, NOV, DEC]
        assertEquals(5, c.spine?.size)
        assertEquals(4, c.spine?.first()?.collapsedCount)
        assertEquals("✓4", c.spine?.first()?.label)
        assertEquals(StopStatus.Next, c.spine?.get(1)?.status) // SEP is the next
    }

    @Test fun `roadmap over 6 months with short leading done-run does not collapse`() {
        // 7 months, only the first 2 Done → run of 2 is NOT > 2, so no collapse.
        val stops = listOf(
            Stop("2026-06-01", "jun"), Stop("2026-07-01", "jul"),   // done
            Stop("2026-09-01", "sep"), Stop("2026-10-01", "oct"),
            Stop("2026-11-01", "nov"), Stop("2026-12-01", "dec"),
            Stop("2027-01-01", "jan"),
        )
        val tl = Timeline(tz = "America/New_York", stops = stops)
        val c = presentTimelineCard(tl, "2026-08-24T10:00:00-04:00", ny)!!
        assertEquals(7, c.spine?.size)
        assertNull(c.spine?.first()?.collapsedCount)
    }

    @Test fun `roadmap of 6 or fewer months never collapses`() {
        val c = presentTimelineCard(hubMonths6(), "2026-08-24T10:00:00-04:00", ny)!!
        assertEquals(6, c.spine?.size)
        assertTrue(c.spine!!.all { it.collapsedCount == null })
    }

    @Test fun `all-done day timeline - doneCount all, window empty, tailCount 0, nowTimeLabel null`() {
        // All stops are in the past; focal day is not today (today is 2026-08-24 per nowIso but stops are in June)
        val stops = listOf(
            Stop("2026-06-10T08:00:00-04:00", "a"),
            Stop("2026-06-10T09:00:00-04:00", "b"),
            Stop("2026-06-10T10:00:00-04:00", "c"),
        )
        val tl = Timeline(tz = "America/New_York", stops = stops)
        val c = presentTimelineCard(tl, "2026-08-24T10:00:00-04:00", ny)!!
        assertEquals(TimelineScale.Day, c.scale)
        assertEquals(3, c.doneCount)
        assertEquals(0, c.window.size)
        assertEquals(0, c.tailCount)
        assertNull(c.nowTimeLabel) // focal day not today
    }

    @Test fun `single-scale date-only stops returns Hub card`() {
        val stops = listOf(
            Stop("2026-09-01", "a"),
            Stop("2026-10-01", "b"),
            Stop("2026-11-01", "c"),
        )
        val tl = Timeline(tz = "America/New_York", stops = stops)
        val c = presentTimelineCard(tl, "2026-08-24T10:00:00-04:00", ny)!!
        assertEquals(TimelineScale.Hub, c.scale)
        assertNotNull(c.spine)
    }

    @Test fun `only intraday stops today returns Day card`() {
        val stops = listOf(
            Stop("2026-08-24T09:00:00-04:00", "a"),
            Stop("2026-08-24T11:00:00-04:00", "b"),
        )
        val tl = Timeline(tz = "America/New_York", stops = stops)
        val c = presentTimelineCard(tl, "2026-08-24T10:00:00-04:00", ny)!!
        assertEquals(TimelineScale.Day, c.scale)
        assertNotNull(c.nowTimeLabel) // focal day IS today
    }

    @Test fun `roadmap nextCallout is first non-Done stop`() {
        val stops = listOf(
            Stop("2026-01-01", "done1", done = true),
            Stop("2026-02-01", "done2", done = true),
            Stop("2026-09-01", "future"),
        )
        val tl = Timeline(tz = "America/New_York", stops = stops)
        val c = presentTimelineCard(tl, "2026-08-24T10:00:00-04:00", ny)!!
        assertEquals(TimelineScale.Hub, c.scale)
        assertEquals("future", c.nextCallout?.stop?.title)
    }

    @Test fun `presentTimelineDetail day groups by part of day`() {
        val result = presentTimelineDetail(day(), TimelineScale.Day, "2026-08-24T10:40:00-04:00", ny)
        assertEquals(TimelineScale.Day, result.scale)
        // MORNING: 07:30, 08:00, 09:50, 11:00 — all before noon
        // AFTERNOON: 12:30, 14:00 — 12–17
        // EVENING: 16:30 — but 16 < 17, so it's AFTERNOON too; 16:30 hour=16
        // Actually: MORNING <12 → 07:30,08:00,09:50; AFTERNOON 12-16 → 11:00 is hour=11 so MORNING
        // Let me recalculate: 11:00 hour=11 → MORNING; 12:30 hour=12 → AFTERNOON; 14:00 hour=14 → AFTERNOON; 16:30 hour=16 → AFTERNOON
        val labels = result.groups.map { it.label }
        assertTrue(labels.contains("MORNING"))
        assertTrue(labels.contains("AFTERNOON"))
    }

    @Test fun `detail-day nowIndex is robust to non-chronological authored order`() {
        // Authored out of order: an afternoon (future) stop listed BEFORE the morning (past) ones.
        // The NOW line must land in grouped render order (morning→afternoon), not authored order.
        val tl = Timeline(
            tz = "America/New_York",
            stops = listOf(
                Stop("2026-08-24T14:00:00-04:00", "pm"),   // afternoon, future
                Stop("2026-08-24T08:00:00-04:00", "am1"),  // morning, past
                Stop("2026-08-24T09:50:00-04:00", "am2"),  // morning, past
            ),
        )
        val result = presentTimelineDetail(tl, TimelineScale.Day, "2026-08-24T10:40:00-04:00", ny)
        // render order = [am1, am2, pm]; last past = am2 (idx 1) → NOW before pm (idx 2)
        assertEquals(2, result.nowIndex)
    }

    @Test fun `presentTimelineDetail hub groups by month`() {
        val stops = listOf(
            Stop("2026-08-01", "aug1"),
            Stop("2026-08-15", "aug2"),
            Stop("2026-09-01", "sep1"),
        )
        val tl = Timeline(tz = "America/New_York", stops = stops)
        val result = presentTimelineDetail(tl, TimelineScale.Hub, "2026-08-24T10:00:00-04:00", ny)
        assertEquals(TimelineScale.Hub, result.scale)
        assertEquals(listOf("AUGUST", "SEPTEMBER"), result.groups.map { it.label })
        assertEquals(0, result.nowIndex) // current month is AUGUST at index 0
    }
}
