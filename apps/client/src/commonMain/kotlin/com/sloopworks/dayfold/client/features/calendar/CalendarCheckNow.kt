package com.sloopworks.dayfold.client

// ADR 0063 §5 — surfaces the reconciler's results as ONE calm aggregate Now unit, never a per-
// event card and never an interruption. Pure: reads only the already-computed CalendarCheckState
// (CalendarCheckEngine owns every DB/port effect that produced it, ADR 0058) — no I/O, no clock.
//
// Mutually exclusive with [calendarCheckFooter] by construction: both read the same
// `check.results` unresolved-item count, so exactly one of {aggregate NowItem, all-clear/stale
// footer} is non-null for a given CalendarCheckState.

// The synthetic, device-local subject key for the single aggregate unit. Deliberately outside the
// card:/hub:/kind:/source: grammar (SubjectRef.kt) — this subject never crosses the wire and must
// never collide with a real content or rule subject.
const val CALENDAR_CHECK_SUBJECT_KEY = "calendar-check"

// The fixed preview budget (ADR 0063 §5 "up to 3 preview rows").
private const val CALENDAR_CHECK_PREVIEW_LIMIT = 3

/**
 * At most one aggregate "Calendar check" NowItem when [check] has unresolved review items — null
 * when there is nothing to review (see [calendarCheckFooter] for that case instead). Total count
 * covers every unresolved bucket; only the first [CALENDAR_CHECK_PREVIEW_LIMIT] become preview
 * rows, in a fixed, deterministic bucket order.
 */
fun deriveCalendarCheckNow(check: CalendarCheckState, config: DeriveConfig = DeriveConfig()): NowItem? {
  val previews = calendarCheckPreviews(check.results)
  if (previews.isEmpty()) return null
  return NowItem(
    id = "derived:calendar_check:aggregate",
    origin = Origin.DERIVED,
    reasonKind = ReasonKind.CALENDAR_CHECK,
    title = "Calendar check",
    why = "${previews.size} to review",
    subjectKey = CALENDAR_CHECK_SUBJECT_KEY,
    target = null,
    triggerAtIso = null,   // no urgency anchor — never NOW/SOON-banded by a deadline
    weight = config.calendarCheckWeight,
    calendarCheckCount = previews.size,
    calendarCheckPreviews = previews.take(CALENDAR_CHECK_PREVIEW_LIMIT),
  )
}

private fun calendarCheckPreviews(results: ReconcileResult): List<CalendarCheckPreview> = buildList {
  results.dayfoldOnly.forEach { add(CalendarCheckPreview(it.title, CalendarGapKind.DAYFOLD_ONLY)) }
  results.calendarOnly.forEach { add(CalendarCheckPreview(it.title, CalendarGapKind.CALENDAR_ONLY)) }
  results.suggested.forEach { add(CalendarCheckPreview(it.candidate.title, CalendarGapKind.SUGGESTED)) }
  results.ambiguous.forEach { add(CalendarCheckPreview(it.candidate.title, CalendarGapKind.AMBIGUOUS)) }
  results.differs.forEach { add(CalendarCheckPreview(it.candidate.title, CalendarGapKind.DIFFERS)) }
  results.recurringNotices.forEach { add(CalendarCheckPreview(it.candidate.title, CalendarGapKind.RECURRING)) }
}

// The quiet footer datum shown instead of a card (ADR 0063 §5). [stale] means the current state
// reflects a short-circuited pass (permission/feature/no-calendars) rather than a fresh compare —
// [lastSuccessfulCheckAtIso] is the last REAL comparison's timestamp either way, never the stale
// attempt's own clock tick, so an offline footer never claims a fresh all-clear.
data class CalendarCheckFooter(
  val allClear: Boolean,
  val lastSuccessfulCheckAtIso: String?,
  val stale: Boolean,
)

/**
 * The footer datum, or null when the aggregate card already covers the state (unresolved items
 * present) or the feature has never completed a real comparison ([lastSuccessfulCheckAtIso] null).
 * [lastSuccessfulCheckAtIso] must be the DB-persisted CalendarSettings.lastCheckAt — that field is
 * written only on a real granted/compared pass (CalendarCheckEngine), unlike
 * CalendarCheckState.lastCheckAt, which also advances on a short-circuited stale attempt.
 */
fun calendarCheckFooter(check: CalendarCheckState, lastSuccessfulCheckAtIso: String?): CalendarCheckFooter? {
  if (lastSuccessfulCheckAtIso == null) return null
  if (calendarCheckPreviews(check.results).isNotEmpty()) return null
  return CalendarCheckFooter(
    allClear = !check.stale,
    lastSuccessfulCheckAtIso = lastSuccessfulCheckAtIso,
    stale = check.stale,
  )
}
