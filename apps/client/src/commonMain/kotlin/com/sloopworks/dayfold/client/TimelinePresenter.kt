package com.sloopworks.dayfold.client

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime

// ADR 0045 — hub-timeline presenter (Phase 1: status computation).
// Pure function: injected clock (nowIso) mirrors feedCards/deriveNow pattern.
// No wall-clock, no side effects — snapshot/property-testable.

enum class StopStatus { Done, Next, Upcoming }
enum class TimelineScale { Day, Hub }

private val MONTH_NAMES = arrayOf(
    "JANUARY","FEBRUARY","MARCH","APRIL","MAY","JUNE",
    "JULY","AUGUST","SEPTEMBER","OCTOBER","NOVEMBER","DECEMBER"
)
private val MONTHS_ABBR = arrayOf(
    "Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"
)

/**
 * A stop with its computed status + tz-aware display labels.
 *
 * All labels are derived in the presenter from the parsed [instant] + injected tz — the card
 * and detail render them verbatim (no raw-`at` string-parsing, which mis-renders when the
 * authored offset differs from the timeline's tz, e.g. a UTC-stamped stop in a NY timeline).
 *  - [timeLabel]  : "h:MM AM/PM" for an intraday-timed stop; null for a date-only stop.
 *  - [dateLabel]  : "Mon D" (e.g. "Aug 25").
 *  - [monthUpper] : "AUG" (roadmap next-milestone tile).
 *  - [dayOfMonth] : "24"  (roadmap next-milestone tile).
 */
data class PresentedStop(
    val stop: Stop,
    val status: StopStatus,
    val instant: Instant?,
    val timeLabel: String? = null,
    val dateLabel: String = "",
    val monthUpper: String = "",
    val dayOfMonth: String = "",
)

/** Compute the tz-aware display labels for a stop. */
private fun stopLabels(stop: Stop, instant: Instant?, tz: TimeZone): PresentedStop {
    val base = PresentedStop(stop, StopStatus.Upcoming, instant)
    if (instant == null) return base.copy(dateLabel = stop.at.trim())
    val ldt = instant.toLocalDateTime(tz)
    val monIdx = ldt.month.ordinal
    val timeLabel = if (stop.hasIntradayTime()) {
        val t = ldt.time
        val h12 = (t.hour % 12).let { if (it == 0) 12 else it }
        val amPm = if (t.hour < 12) "AM" else "PM"
        "$h12:${t.minute.toString().padStart(2, '0')} $amPm"
    } else null
    return base.copy(
        timeLabel = timeLabel,
        dateLabel = "${MONTHS_ABBR[monIdx]} ${ldt.dayOfMonth}",
        monthUpper = MONTHS_ABBR[monIdx].uppercase(),
        dayOfMonth = ldt.dayOfMonth.toString(),
    )
}

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
        stopLabels(stop, parsed[i].second, tz).copy(status = statusByOrig[i]!!)
    }
}

/**
 * The focal day's stops, in chronological order, with the first non-done promoted to Next.
 * Day scale is its own schedule: the global Next (first non-done across the *whole* timeline) may
 * be an off-focal roadmap stop, so re-derive "next" within the day; and sort so the NOW line and
 * intra-part-of-day order don't depend on authored order.
 */
private fun scopedDayStops(presented: List<PresentedStop>, focal: LocalDate?, tz: TimeZone): List<PresentedStop> {
    val day = presented
        .filter { it.instant?.toLocalDateTime(tz)?.date == focal }
        .sortedWith(compareBy(nullsLast()) { it.instant })
    var promoted = false
    return day.map { ps ->
        when {
            ps.status == StopStatus.Done -> ps
            !promoted -> { promoted = true; if (ps.status == StopStatus.Next) ps else ps.copy(status = StopStatus.Next) }
            ps.status == StopStatus.Next -> ps.copy(status = StopStatus.Upcoming)   // demote the global next; it isn't first here
            else -> ps
        }
    }
}

// --- Scale selection, focal day, NOW line (ADR 0045 Phase 1) ---

private fun Stop.hasIntradayTime(): Boolean = at.trim().length > 10  // "YYYY-MM-DD" = 10; longer has a time component

/**
 * Returns the date with the most intraday-timed stops.
 * Tie → the date containing [nowIso] if any, else earliest.
 * If no intraday stops exist, returns today.
 */
internal fun focalDay(tl: Timeline, nowIso: String, tz: TimeZone): LocalDate? {
    val today = parseInstantFlexible(nowIso, tz)?.toLocalDateTime(tz)?.date
    val byDay = tl.stops.filter { it.hasIntradayTime() }
        .mapNotNull { parseInstantFlexible(it.at, tz)?.toLocalDateTime(tz)?.date }
        .groupingBy { it }.eachCount()
    if (byDay.isEmpty()) return today
    val max = byDay.values.max()
    val tied = byDay.filterValues { it == max }.keys
    return tied.firstOrNull { it == today } ?: tied.minOrNull()
}

/** True when the timeline has ≥1 intraday-timed stop on the focal day (the day view is meaningful). */
internal fun dayScaleAvailable(tl: Timeline, nowIso: String, tz: TimeZone): Boolean {
    val focal = focalDay(tl, nowIso, tz)
    return tl.stops.any { it.hasIntradayTime() &&
        parseInstantFlexible(it.at, tz)?.toLocalDateTime(tz)?.date == focal }
}

/** True when the stops span >14 days, or ≥3 date-only stops, or >1 distinct month (the roadmap is meaningful). */
internal fun hubScaleAvailable(tl: Timeline, tz: TimeZone): Boolean {
    val dates = tl.stops.mapNotNull { parseInstantFlexible(it.at, tz)?.toLocalDateTime(tz)?.date }.sorted()
    val spanDays = if (dates.size >= 2) dates.first().daysUntil(dates.last()) else 0
    val dateOnlyCount = tl.stops.count { !it.hasIntradayTime() }
    val distinctMonths = dates.map { it.year * 12 + it.month.ordinal }.distinct().size
    return spanDays > 14 || dateOnlyCount >= 3 || distinctMonths > 1
}

/**
 * Day if ≥1 intraday-timed stop on the focal day;
 * Hub if stops span >14 days OR ≥3 date-only stops;
 * else Day.
 */
internal fun selectScale(tl: Timeline, nowIso: String, tz: TimeZone): TimelineScale {
    if (dayScaleAvailable(tl, nowIso, tz)) return TimelineScale.Day
    return if (hubScaleAvailable(tl, tz)) TimelineScale.Hub else TimelineScale.Day
}

/**
 * Both scales are meaningful → the detail offers the day↔hub scope toggle (spec §5).
 * The hub shows one auto-selected card; the second scale is reachable only via this toggle.
 */
fun hasBothScales(tl: Timeline, nowIso: String, tz: TimeZone): Boolean =
    dayScaleAvailable(tl, nowIso, tz) && hubScaleAvailable(tl, tz)

/**
 * Index AFTER which the NOW line sits in [day] (0 = before all stops).
 * Returns null when the focal day is not today.
 */
internal fun nowLineIndex(day: List<PresentedStop>, nowIso: String, tz: TimeZone): Int? {
    val now = parseInstantFlexible(nowIso, tz) ?: return null
    val today = now.toLocalDateTime(tz).date
    val focalMatchesToday = day.any { it.instant?.toLocalDateTime(tz)?.date == today }
    if (!focalMatchesToday) return null
    val lastPast = day.indexOfLast { it.instant != null && it.instant <= now }
    return lastPast + 1   // 0 = before all; day.size = after all
}

// ── Shared card/detail types (ADR 0045 Phase 1) ──────────────────────────────

data class TimelineGroup(val label: String, val stops: List<PresentedStop>)
data class PresentedTimeline(val scale: TimelineScale, val groups: List<TimelineGroup>, val nowIndex: Int?, val nowTimeLabel: String?, val derived: Boolean = false)
/** A roadmap spine node. [collapsedCount] non-null → a "✓N" node standing in for N collapsed done months. */
data class SpineNode(val label: String, val status: StopStatus, val collapsedCount: Int? = null)
data class TimelineCardModel(
    val scale: TimelineScale,
    val doneCount: Int,
    val nowTimeLabel: String?,
    val window: List<PresentedStop>,
    val tailCount: Int,
    val spine: List<SpineNode>? = null,
    val nextCallout: PresentedStop? = null,
    val moreCount: Int = 0,   // roadmap: months beyond the ≤6-node spine cap (trailing "+M")
    val derived: Boolean = false,   // ADR 0046 — synthesized by deriveTimeline → honest derived provenance
)

// ── Card windowing ──────────────────────────────────────────────────────────

/**
 * Returns a [TimelineCardModel] for the given timeline, or null if [tl.stops] is empty.
 *
 * Day card (scoped to the focal day; roadmap-only stops are excluded and shown in the Hub scale):
 *  - doneCount = count of Done stops on the focal day
 *  - window = up to 3 non-Done focal-day stops from the Next stop onward
 *  - tailCount = remaining non-Done focal-day stops after the window
 *  - nowTimeLabel = clockTime(now, tz) iff the focal day is today; else null
 *
 * Hub/roadmap card:
 *  - spine = one node per month; status = dominant status of that month's stops.
 *            Past ~6 nodes, a leading Done-run of >2 collapses into one "✓N" node.
 *  - nextCallout = first non-Done stop
 *  - nowTimeLabel always null (roadmap is date-only)
 */
fun presentTimelineCard(tl: Timeline, nowIso: String, tz: TimeZone): TimelineCardModel? {
    if (tl.stops.isEmpty()) return null
    val scale = selectScale(tl, nowIso, tz)
    val presented = stopStatuses(tl.stops, nowIso, tz)

    return when (scale) {
        TimelineScale.Day -> {
            // Day scale is the focal day's schedule — scope to that date (a timeline may also
            // carry multi-month roadmap stops, reachable via the detail's "Whole hub" toggle).
            val focal = focalDay(tl, nowIso, tz)
            val dayStops = scopedDayStops(presented, focal, tz)
            val doneCount = dayStops.count { it.status == StopStatus.Done }
            val nonDone = dayStops.filter { it.status != StopStatus.Done }
            val window = nonDone.take(3)
            val tailCount = nonDone.size - window.size

            // nowTimeLabel only when the focal day is today
            val now = parseInstantFlexible(nowIso, tz)
            val today = now?.toLocalDateTime(tz)?.date
            val nowTimeLabel = if (focal != null && focal == today && now != null) clockTime(now, tz) else null

            TimelineCardModel(
                scale = TimelineScale.Day,
                doneCount = doneCount,
                nowTimeLabel = nowTimeLabel,
                window = window,
                tailCount = tailCount,
                derived = tl.derived,
            )
        }

        TimelineScale.Hub -> {
            // Group stops by year-month label (e.g. "AUGUST", with year if different), chronological.
            val now = parseInstantFlexible(nowIso, tz)
            val todayYear = now?.toLocalDateTime(tz)?.year
            val sorted = presented.sortedWith(compareBy(nullsLast()) { it.instant })
            val monthGroups = buildMonthGroups(sorted, tz, todayYear)

            val allNodes = monthGroups.map { (label, stops) ->
                val dominantStatus = when {
                    stops.any { it.status == StopStatus.Next } -> StopStatus.Next
                    stops.any { it.status == StopStatus.Upcoming } -> StopStatus.Upcoming
                    else -> StopStatus.Done
                }
                SpineNode(label = label, status = dominantStatus)
            }
            val nextCallout = presented.firstOrNull { it.status != StopStatus.Done }
            val (spine, moreCount) = condenseSpine(allNodes)

            TimelineCardModel(
                scale = TimelineScale.Hub,
                doneCount = presented.count { it.status == StopStatus.Done },
                nowTimeLabel = null,
                window = emptyList(),
                tailCount = 0,
                spine = spine,
                nextCallout = nextCallout,
                moreCount = moreCount,
                derived = tl.derived,
            )
        }
    }
}

/** Group presented stops into month-labeled buckets, preserving stop order. */
private fun buildMonthGroups(
    stops: List<PresentedStop>,
    tz: TimeZone,
    todayYear: Int?,
): List<Pair<String, List<PresentedStop>>> {
    val grouped = LinkedHashMap<String, MutableList<PresentedStop>>()
    for (ps in stops) {
        val ldt = ps.instant?.toLocalDateTime(tz)
        val label = if (ldt != null) {
            val base = MONTH_NAMES[ldt.month.ordinal]
            if (todayYear != null && ldt.year != todayYear) "$base ${ldt.year}" else base
        } else "UPCOMING"
        grouped.getOrPut(label) { mutableListOf() }.add(ps)
    }
    return grouped.entries.map { it.key to it.value }
}

/**
 * Roadmap spine condense rule (Timeline-Card.dc.html): once a roadmap runs past ~6 month-nodes,
 * fold a *leading* run of >2 consecutive Done months into a single "✓N" node, keeping the rest.
 * Below 7 nodes (or a leading Done-run of ≤2) the spine renders every node.
 */
internal fun collapseLeadingDoneRun(nodes: List<SpineNode>): List<SpineNode> {
    if (nodes.size <= 6) return nodes
    var run = 0
    while (run < nodes.size && nodes[run].status == StopStatus.Done) run++
    if (run <= 2) return nodes
    return listOf(SpineNode(label = "✓$run", status = StopStatus.Done, collapsedCount = run)) + nodes.drop(run)
}

/**
 * Condense a roadmap spine to a hard ≤6-node cap (spec §4): first apply the leading-done ✓N
 * collapse, then — for a still-long spine (e.g. a forward-heavy roadmap with no leading done-run)
 * — keep the first 5 nodes and fold the rest into a trailing "+M" (returned as the moreCount).
 * The fixed-width spine Row can't scroll, so an uncapped spine would clip its labels.
 */
internal fun condenseSpine(nodes: List<SpineNode>): Pair<List<SpineNode>, Int> {
    val collapsed = collapseLeadingDoneRun(nodes)
    if (collapsed.size <= 6) return collapsed to 0
    return collapsed.take(5) to (collapsed.size - 5)
}

// ── Detail grouped feed ─────────────────────────────────────────────────────

/**
 * Full grouped feed for the timeline detail view.
 *
 * Day scale:  groups by part-of-day (MORNING <12, AFTERNOON 12–17, EVENING ≥17) using the stop's
 *             local hour in [tz]. nowIndex = nowLineIndex result (absolute stop index in groups).
 *             nowTimeLabel = clockTime(now, tz) iff focal day is today.
 *
 * Hub scale:  groups by month ("AUGUST", etc.). nowIndex = index of the current-month group.
 *             nowTimeLabel = null.
 */
fun presentTimelineDetail(tl: Timeline, scale: TimelineScale, nowIso: String, tz: TimeZone): PresentedTimeline {
    val presented = stopStatuses(tl.stops, nowIso, tz)

    return when (scale) {
        TimelineScale.Day -> {
            // Scope to the focal day (the roadmap stops live in the Hub scale), chronological.
            val focal = focalDay(tl, nowIso, tz)
            val dayStops = scopedDayStops(presented, focal, tz)
            val morning = dayStops.filter { (it.instant?.toLocalDateTime(tz)?.hour ?: 0) < 12 }
            val afternoon = dayStops.filter { val h = it.instant?.toLocalDateTime(tz)?.hour ?: 0; h in 12..16 }
            val evening = dayStops.filter { (it.instant?.toLocalDateTime(tz)?.hour ?: 0) >= 17 }

            val groups = buildList {
                if (morning.isNotEmpty()) add(TimelineGroup("MORNING", morning))
                if (afternoon.isNotEmpty()) add(TimelineGroup("AFTERNOON", afternoon))
                if (evening.isNotEmpty()) add(TimelineGroup("EVENING", evening))
            }

            // NOW index is relative to the grouped render order (morning→afternoon→evening);
            // dayStops is chronological, so the concat is chronological and the line lands right.
            val nowIdx = nowLineIndex(morning + afternoon + evening, nowIso, tz)

            val now = parseInstantFlexible(nowIso, tz)
            val today = now?.toLocalDateTime(tz)?.date
            val nowTimeLabel = if (focal != null && focal == today && now != null) clockTime(now, tz) else null

            PresentedTimeline(scale = TimelineScale.Day, groups = groups, nowIndex = nowIdx, nowTimeLabel = nowTimeLabel, derived = tl.derived)
        }

        TimelineScale.Hub -> {
            val now = parseInstantFlexible(nowIso, tz)
            val todayYear = now?.toLocalDateTime(tz)?.year
            // Chronological so month groups (and the NOW band placement) don't depend on authored order.
            val sorted = presented.sortedWith(compareBy(nullsLast()) { it.instant })
            val monthGroupsList = buildMonthGroups(sorted, tz, todayYear)
            val groups = monthGroupsList.map { (label, stops) -> TimelineGroup(label, stops) }

            // NOW band sits above the first month group that is on-or-after the current month —
            // the current-month group when it exists, else the next future month (so a roadmap that
            // skips the current month still shows a NOW line). Null when every month is already past.
            val nowKey = now?.toLocalDateTime(tz)?.let { it.year * 12 + it.month.ordinal }
            val nowIndex = nowKey?.let {
                groups.indexOfFirst { g ->
                    val k = g.stops.firstOrNull()?.instant?.toLocalDateTime(tz)?.let { d -> d.year * 12 + d.month.ordinal }
                    k != null && k >= nowKey
                }.takeIf { idx -> idx >= 0 }
            }

            PresentedTimeline(scale = TimelineScale.Hub, groups = groups, nowIndex = nowIndex, nowTimeLabel = null, derived = tl.derived)
        }
    }
}
