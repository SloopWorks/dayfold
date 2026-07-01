package com.sloopworks.dayfold.client

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

// ADR 0046 — the client-derived timeline fallback (deriveTimeline over a hub's already-dated blocks).
class DeriveTimelineTest {
    private val ny = TimeZone.of("America/New_York")

    private fun block(id: String, type: String, payload: BlockPayload? = null,
                      bodyMd: String? = null, triggers: List<BlockTrigger>? = null) =
        HubBlock(id = id, sectionId = "s", type = type, payload = payload, bodyMd = bodyMd, triggers = triggers)

    private fun richTree() = HubTree(
        hub = Hub(id = "h", title = "Maya starts college", countdownTo = "2026-08-24", startAt = "2026-08-01"),
        blocks = listOf(
            block("m1", "milestone", BlockPayload(date = "2026-05-01", label = "Enrollment deposit")),
            block("c1", "checklist", BlockPayload(items = listOf(
                ChecklistItem(id = "i1", text = "Housing application", due = "2026-06-12", done = true, assignee = "Maya"),
                ChecklistItem(id = "i2", text = "Buy dorm supplies", due = "2026-08-20"),
                ChecklistItem(id = "i3", text = "No-date task"),   // no due → not a stop
            ))),
            block("loc1", "location", BlockPayload(label = "Move-in desk"),
                triggers = listOf(BlockTrigger(whenTrigger = TriggerWhen(at = "2026-08-24T09:50:00-04:00")))),
            block("t1", "text", bodyMd = "just a note"),   // no date → ignored
        ),
    )

    @Test fun `collects all four dated sources with source tags`() {
        val stops = collectDerivedStops(richTree(), ny)
        val byTitle = stops.associateBy { it.title }
        assertEquals("hubdate", byTitle["Maya starts college"]?.source)   // countdown_to → major hub date
        assertTrue(byTitle["Maya starts college"]?.major == true)
        assertEquals("hubdate", byTitle["Starts"]?.source)                 // start_at
        assertEquals("milestone", byTitle["Enrollment deposit"]?.source)
        assertEquals("checklist", byTitle["Housing application"]?.source)
        assertEquals(true, byTitle["Housing application"]?.done)           // item done carries through
        assertEquals("Maya", byTitle["Housing application"]?.assignee)
        assertEquals("checklist", byTitle["Buy dorm supplies"]?.source)
        assertEquals("pickup", byTitle["Move-in desk"]?.source)           // location + when-trigger
        assertNull(byTitle["No-date task"])                                // no due → excluded
        assertNull(byTitle["just a note"])                                 // undated text block → excluded
    }

    @Test fun `deriveTimeline flags derived and titles from the hub`() {
        val tl = deriveTimeline(richTree(), ny)!!
        assertTrue(tl.derived)
        assertEquals("Maya starts college", tl.title)
        assertEquals("America/New_York", tl.tz)
        // renders through the same presenter — the intraday pickup on Aug 24 makes Aug 24 the
        // focal day with an intraday stop → Day scale (the roadmap is a toggle away).
        val card = presentTimelineCard(tl, "2026-07-01T10:00:00-04:00", ny)!!
        assertEquals(TimelineScale.Day, card.scale)
    }

    @Test fun `fewer than two dated stops does not derive but nudges at one`() {
        // Only the hub countdown is dated → one stop → no timeline, but a nudge.
        val one = HubTree(hub = Hub(id = "h", title = "Trip", countdownTo = "2026-09-01"))
        assertNull(deriveTimeline(one, ny))
        assertTrue(hubHasSingleDate(one, ny))
        // No dated content at all → neither.
        val none = HubTree(hub = Hub(id = "h", title = "Empty"))
        assertNull(deriveTimeline(none, ny))
        assertFalse(hubHasSingleDate(none, ny))
    }

    @Test fun `duplicate date+title collapses`() {
        // countdown_to and a milestone share a date+title → one stop, not two.
        val tree = HubTree(
            hub = Hub(id = "h", title = "Move-in day", countdownTo = "2026-08-24"),
            blocks = listOf(
                block("m", "milestone", BlockPayload(date = "2026-08-24", label = "Move-in day")),
                block("c", "checklist", BlockPayload(items = listOf(
                    ChecklistItem(id = "i", text = "Pack", due = "2026-08-20")))),
            ),
        )
        val titles = collectDerivedStops(tree, ny).filter { it.title == "Move-in day" }
        assertEquals(1, titles.size)
    }
}
