package com.sloopworks.dayfold.client

// ADR 0063 §5 — surfaces the reconciler's results as ONE calm aggregate Now unit, never a per-
// event card and never an interruption. Pure: reads only the already-computed CalendarCheckState
// (CalendarCheckEngine owns every DB/port effect that produced it, ADR 0058) — no I/O, no clock.
//
// [item] and [footer] are mutually exclusive by construction — [calendarCheckDisplay] computes
// the unresolved-item previews exactly once and branches on that single result, so there is no
// way for a card and the all-clear/stale footer to disagree about whether there is something to
// review.

// The synthetic, device-local subject key PREFIX for the aggregate unit. Deliberately outside the
// card:/hub:/kind:/source: grammar (SubjectRef.kt) — this subject never crosses the wire and must
// never collide with a real content or rule subject.
//
// The full subjectKey is this prefix plus a fingerprint over the current unresolved item set
// ([aggregateSubjectKey]), not a bare constant — a fixed key would make a local dismiss/mute
// (ADR 0043's per-subjectKey dismissedAtIso / a future ResponseRules mute) stick forever, silently
// hiding every LATER, unrelated batch of unresolved items too. Fingerprinting means a dismiss only
// ever covers the exact batch the member saw; a changed unresolved set is a new subject.
const val CALENDAR_CHECK_SUBJECT_PREFIX = "calendar-check"

// The fixed preview budget (ADR 0063 §5 "up to 3 preview rows").
private const val CALENDAR_CHECK_PREVIEW_LIMIT = 3

data class CalendarCheckDisplay(val item: NowItem?, val footer: CalendarCheckFooter?)

// The quiet footer datum shown instead of a card (ADR 0063 §5). [stale] means the current state
// reflects a short-circuited pass (permission/feature/no-calendars) rather than a fresh compare —
// [lastSuccessfulCheckAtIso] is the last REAL comparison's timestamp either way, never the stale
// attempt's own clock tick, so an offline footer never claims a fresh all-clear. [allClear] is
// simply `!stale` — a computed property, not a second field to keep in sync.
data class CalendarCheckFooter(val lastSuccessfulCheckAtIso: String, val stale: Boolean) {
  val allClear: Boolean get() = !stale
}

/**
 * The complete Calendar Check Now display for [check]: an aggregate NowItem (total count + up to
 * [CALENDAR_CHECK_PREVIEW_LIMIT] preview rows, in a fixed deterministic bucket order) when there
 * are unresolved review items, otherwise the all-clear/stale footer — or neither when the feature
 * has never completed a real comparison ([lastSuccessfulCheckAtIso] null).
 *
 * [lastSuccessfulCheckAtIso] must be the DB-persisted CalendarSettings.lastCheckAt — that field is
 * written only on a real granted/compared pass (CalendarCheckEngine), unlike
 * CalendarCheckState.lastCheckAt, which also advances on a short-circuited stale attempt.
 */
fun calendarCheckDisplay(
  check: CalendarCheckState,
  lastSuccessfulCheckAtIso: String?,
  config: DeriveConfig = DeriveConfig(),
): CalendarCheckDisplay {
  val previews = calendarCheckPreviews(check.results)
  if (previews.isNotEmpty()) {
    val item = NowItem(
      id = "derived:calendar_check:aggregate",
      origin = Origin.DERIVED,
      reasonKind = ReasonKind.CALENDAR_CHECK,
      title = "Calendar check",
      why = "${previews.size} to review",
      subjectKey = aggregateSubjectKey(check.results),
      target = null,
      triggerAtIso = null,   // no urgency anchor — never NOW/SOON-banded by a deadline
      weight = config.calendarCheckWeight,
      calendarCheckCount = previews.size,
      calendarCheckPreviews = previews.take(CALENDAR_CHECK_PREVIEW_LIMIT),
    )
    return CalendarCheckDisplay(item = item, footer = null)
  }
  val footer = lastSuccessfulCheckAtIso?.let { CalendarCheckFooter(lastSuccessfulCheckAtIso = it, stale = check.stale) }
  return CalendarCheckDisplay(item = null, footer = footer)
}

private fun calendarCheckPreviews(results: ReconcileResult): List<CalendarCheckPreview> = buildList {
  results.dayfoldOnly.forEach { add(CalendarCheckPreview(it.title, CalendarGapKind.DAYFOLD_ONLY)) }
  results.calendarOnly.forEach { add(CalendarCheckPreview(it.title, CalendarGapKind.CALENDAR_ONLY)) }
  results.suggested.forEach { add(CalendarCheckPreview(it.candidate.title, CalendarGapKind.SUGGESTED)) }
  results.ambiguous.forEach { add(CalendarCheckPreview(it.candidate.title, CalendarGapKind.AMBIGUOUS)) }
  results.differs.forEach { add(CalendarCheckPreview(it.candidate.title, CalendarGapKind.DIFFERS)) }
  results.recurringNotices.forEach { add(CalendarCheckPreview(it.candidate.title, CalendarGapKind.RECURRING)) }
}

// A fingerprint over the STABLE identifiers of every unresolved item (never a title — mirrors the
// codebase's title-never-a-key rule) — reuses CalendarCandidates.kt's stableFingerprint. Sorted so
// the fingerprint is order-independent; bucket-prefixed so the same subject appearing under a
// different gap kind across passes still counts as a change.
private fun aggregateSubjectKey(results: ReconcileResult): String {
  val parts = buildList {
    results.dayfoldOnly.forEach { add("dayfoldOnly:${it.subjectKey}") }
    results.calendarOnly.forEach { add("calendarOnly:${it.platformEventId}") }
    results.suggested.forEach { add("suggested:${it.candidate.subjectKey}") }
    results.ambiguous.forEach { add("ambiguous:${it.candidate.subjectKey}") }
    results.differs.forEach { add("differs:${it.subjectKey}") }
    results.recurringNotices.forEach { add("recurring:${it.candidate.subjectKey}") }
  }.sorted()
  return "$CALENDAR_CHECK_SUBJECT_PREFIX:" + stableFingerprint(*parts.toTypedArray())
}
