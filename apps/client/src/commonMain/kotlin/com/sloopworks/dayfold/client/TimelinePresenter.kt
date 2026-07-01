package com.sloopworks.dayfold.client

import kotlin.time.Instant
import kotlinx.datetime.TimeZone

// ADR 0045 — hub-timeline presenter (Phase 1: status computation).
// Pure function: injected clock (nowIso) mirrors feedCards/deriveNow pattern.
// No wall-clock, no side effects — snapshot/property-testable.

enum class StopStatus { Done, Next, Upcoming }
enum class TimelineScale { Day, Hub }

data class PresentedStop(val stop: Stop, val status: StopStatus, val instant: Instant?)

/**
 * Classify each stop relative to [nowIso].
 *
 * Rules (monotonic — once done, always done):
 *  - Done  : stop.done == true OR parseable instant < now
 *  - Next  : first non-done stop in instant-ascending order
 *  - Upcoming : all remaining non-done stops
 *
 * Stops with an unparseable [Stop.at] sort last and default Upcoming (unless author-done).
 * Output order mirrors the original [stops] list order (display order unchanged).
 */
internal fun stopStatuses(stops: List<Stop>, nowIso: String, tz: TimeZone): List<PresentedStop> {
    val now = parseInstantFlexible(nowIso, tz)
    // pair each stop with its parsed instant (null if unparseable)
    val parsed = stops.map { it to parseInstantFlexible(it.at, tz) }

    // sort indices: parseable-by-instant ascending, unparseable last (stable)
    val ordered = parsed.withIndex().sortedWith(
        compareBy({ it.value.second == null }, { it.value.second })
    )

    var nextAssigned = false
    val statusByOrig = HashMap<Int, StopStatus>()
    for ((origIdx, pair) in ordered) {
        val (stop, inst) = pair
        val done = stop.done || (inst != null && now != null && inst < now)
        val status = when {
            done -> StopStatus.Done
            !nextAssigned -> { nextAssigned = true; StopStatus.Next }
            else -> StopStatus.Upcoming
        }
        statusByOrig[origIdx] = status
    }

    return stops.mapIndexed { i, stop ->
        PresentedStop(stop, statusByOrig[i]!!, parsed[i].second)
    }
}
