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

/**
 * Day if ≥1 intraday-timed stop on the focal day;
 * Hub if stops span >14 days OR ≥3 date-only stops;
 * else Day.
 */
internal fun selectScale(tl: Timeline, nowIso: String, tz: TimeZone): TimelineScale {
    val focal = focalDay(tl, nowIso, tz)
    val intradayOnFocal = tl.stops.any { it.hasIntradayTime() &&
        parseInstantFlexible(it.at, tz)?.toLocalDateTime(tz)?.date == focal }
    if (intradayOnFocal) return TimelineScale.Day
    val dates = tl.stops.mapNotNull { parseInstantFlexible(it.at, tz)?.toLocalDateTime(tz)?.date }.sorted()
    val spanDays = if (dates.size >= 2) dates.first().daysUntil(dates.last()) else 0
    val dateOnlyCount = tl.stops.count { !it.hasIntradayTime() }
    return if (spanDays > 14 || dateOnlyCount >= 3) TimelineScale.Hub else TimelineScale.Day
}

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
data class PresentedTimeline(val scale: TimelineScale, val groups: List<TimelineGroup>, val nowIndex: Int?, val nowTimeLabel: String?)
data class SpineNode(val label: String, val status: StopStatus)
data class TimelineCardModel(
    val scale: TimelineScale,
    val doneCount: Int,
    val nowTimeLabel: String?,
    val window: List<PresentedStop>,
    val tailCount: Int,
    val spine: List<SpineNode>? = null,
    val nextCallout: PresentedStop? = null,
    val moreCount: Int = 0,
)

// ── Card windowing ──────────────────────────────────────────────────────────

/**
 * Returns a [TimelineCardModel] for the given timeline, or null if [tl.stops] is empty.
 *
 * Day card:
 *  - doneCount = count of Done stops
 *  - window = up to 3 non-Done stops from the Next stop onward
 *  - tailCount = remaining non-Done stops after the window
 *  - nowTimeLabel = clockTime(now, tz) iff the focal day is today; else null
 *
 * Hub/roadmap card:
 *  - spine = ≤6 month-nodes; status = dominant status of that month's stops
 *  - moreCount = overflow beyond 6 months
 *  - nextCallout = first non-Done stop
 *  - nowTimeLabel always null (roadmap is date-only)
 */
fun presentTimelineCard(tl: Timeline, nowIso: String, tz: TimeZone): TimelineCardModel? {
    if (tl.stops.isEmpty()) return null
    val scale = selectScale(tl, nowIso, tz)
    val presented = stopStatuses(tl.stops, nowIso, tz)

    return when (scale) {
        TimelineScale.Day -> {
            val doneCount = presented.count { it.status == StopStatus.Done }
            val nonDone = presented.filter { it.status != StopStatus.Done }
            val window = nonDone.take(3)
            val tailCount = nonDone.size - window.size

            // nowTimeLabel only when focal day is today
            val now = parseInstantFlexible(nowIso, tz)
            val today = now?.toLocalDateTime(tz)?.date
            val focalIsToday = presented.any { it.instant?.toLocalDateTime(tz)?.date == today }
            val nowTimeLabel = if (focalIsToday && now != null) clockTime(now, tz) else null

            TimelineCardModel(
                scale = TimelineScale.Day,
                doneCount = doneCount,
                nowTimeLabel = nowTimeLabel,
                window = window,
                tailCount = tailCount,
            )
        }

        TimelineScale.Hub -> {
            // Group stops by year-month label (e.g. "AUGUST", with year if different)
            val now = parseInstantFlexible(nowIso, tz)
            val todayYear = now?.toLocalDateTime(tz)?.year
            val monthGroups = buildMonthGroups(presented, tz, todayYear)

            val cappedNodes = monthGroups.take(6).map { (label, stops) ->
                val dominantStatus = when {
                    stops.any { it.status == StopStatus.Next } -> StopStatus.Next
                    stops.any { it.status == StopStatus.Upcoming } -> StopStatus.Upcoming
                    else -> StopStatus.Done
                }
                SpineNode(label = label, status = dominantStatus)
            }
            val moreCount = (monthGroups.size - 6).coerceAtLeast(0)
            val nextCallout = presented.firstOrNull { it.status != StopStatus.Done }

            TimelineCardModel(
                scale = TimelineScale.Hub,
                doneCount = presented.count { it.status == StopStatus.Done },
                nowTimeLabel = null,
                window = emptyList(),
                tailCount = 0,
                spine = cappedNodes,
                nextCallout = nextCallout,
                moreCount = moreCount,
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
            val morning = presented.filter { (it.instant?.toLocalDateTime(tz)?.hour ?: 0) < 12 }
            val afternoon = presented.filter { val h = it.instant?.toLocalDateTime(tz)?.hour ?: 0; h in 12..16 }
            val evening = presented.filter { (it.instant?.toLocalDateTime(tz)?.hour ?: 0) >= 17 }

            val groups = buildList {
                if (morning.isNotEmpty()) add(TimelineGroup("MORNING", morning))
                if (afternoon.isNotEmpty()) add(TimelineGroup("AFTERNOON", afternoon))
                if (evening.isNotEmpty()) add(TimelineGroup("EVENING", evening))
            }

            val nowIdx = nowLineIndex(presented, nowIso, tz)

            val now = parseInstantFlexible(nowIso, tz)
            val today = now?.toLocalDateTime(tz)?.date
            val focalIsToday = presented.any { it.instant?.toLocalDateTime(tz)?.date == today }
            val nowTimeLabel = if (focalIsToday && now != null) clockTime(now, tz) else null

            PresentedTimeline(scale = TimelineScale.Day, groups = groups, nowIndex = nowIdx, nowTimeLabel = nowTimeLabel)
        }

        TimelineScale.Hub -> {
            val now = parseInstantFlexible(nowIso, tz)
            val todayYear = now?.toLocalDateTime(tz)?.year
            val monthGroupsList = buildMonthGroups(presented, tz, todayYear)
            val groups = monthGroupsList.map { (label, stops) -> TimelineGroup(label, stops) }

            // nowIndex = index of the current month group
            val nowMonthLabel = if (now != null) {
                val ldt = now.toLocalDateTime(tz)
                val base = MONTH_NAMES[ldt.month.ordinal]
                if (todayYear != null && ldt.year != todayYear) "$base ${ldt.year}" else base
            } else null
            val nowIndex = nowMonthLabel?.let { label -> groups.indexOfFirst { it.label == label }.takeIf { it >= 0 } }

            PresentedTimeline(scale = TimelineScale.Hub, groups = groups, nowIndex = nowIndex, nowTimeLabel = null)
        }
    }
}
