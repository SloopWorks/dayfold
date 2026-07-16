package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimelineNowLineIndexTest {
    // The helper reads only group/stop counts (not instants), so dummy stops suffice.
    private fun ps() = PresentedStop(Stop(at = "2026-01-01", title = "x"), StopStatus.Upcoming, null)
    private fun grp(vararg n: Int) = n.map { count -> TimelineGroup("g", List(count) { ps() }) }

    // groups [A:2 stops, B:1 stop] → items: A-hdr(0) a0(1) a1(2) B-hdr(3) b0(4) [end]
    private val groups = grp(2, 1)

    @Test fun nowAtFirstStop() {
        // nowIndex 0 → NOW before a0, i.e. right after A's header at item 1
        assertEquals(1, nowLineItemIndex(groups, 0))
    }

    @Test fun nowMidFirstGroup() {
        // nowIndex 1 → NOW before a1 at item 2
        assertEquals(2, nowLineItemIndex(groups, 1))
    }

    @Test fun nowAtSecondGroupStart() {
        // nowIndex 2 → NOW before b0, right after B's header at item 4
        assertEquals(4, nowLineItemIndex(groups, 2))
    }

    @Test fun nowAfterAllStops() {
        // nowIndex 3 (== total stop count) → trailing NOW line at item 5 (before provenance)
        assertEquals(5, nowLineItemIndex(groups, 3))
    }

    @Test fun nullAndOutOfRangeYieldNull() {
        assertNull(nowLineItemIndex(groups, null))
        assertNull(nowLineItemIndex(groups, 4)) // beyond total → null (no scroll)
    }
}
