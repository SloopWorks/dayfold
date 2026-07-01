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

    @Test fun `roadmap more than 6 months caps spine and sets moreCount`() {
        // 8 distinct months (date-only stops → Hub scale)
        val stops = listOf(
            Stop("2026-01-01", "jan"), Stop("2026-02-01", "feb"),
            Stop("2026-03-01", "mar"), Stop("2026-04-01", "apr"),
            Stop("2026-05-01", "may"), Stop("2026-06-01", "jun"),
            Stop("2026-07-01", "jul"), Stop("2026-08-01", "aug"),
        )
        val tl = Timeline(tz = "America/New_York", stops = stops)
        val c = presentTimelineCard(tl, "2026-08-24T10:00:00-04:00", ny)!!
        assertEquals(TimelineScale.Hub, c.scale)
        assertEquals(6, c.spine?.size)
        assertEquals(2, c.moreCount) // 8 months - 6 = 2
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
