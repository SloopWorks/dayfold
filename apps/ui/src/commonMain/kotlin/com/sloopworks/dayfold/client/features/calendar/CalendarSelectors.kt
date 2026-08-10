package com.sloopworks.dayfold.client.features.calendar

import com.sloopworks.dayfold.client.AmbiguousMatch
import com.sloopworks.dayfold.client.AppState
import com.sloopworks.dayfold.client.CalendarCheckState
import com.sloopworks.dayfold.client.CalendarEventObservation
import com.sloopworks.dayfold.client.CalendarGapKind
import com.sloopworks.dayfold.client.CalendarPermission
import com.sloopworks.dayfold.client.DayfoldEventCandidate
import com.sloopworks.dayfold.client.DeepLinkTarget
import com.sloopworks.dayfold.client.DetailsDiffer
import com.sloopworks.dayfold.client.DeviceCalendar
import com.sloopworks.dayfold.client.EventPrefill
import com.sloopworks.dayfold.client.FieldDiff
import com.sloopworks.dayfold.client.RecurringNotice
import com.sloopworks.dayfold.client.SuggestedMatch
import com.sloopworks.dayfold.client.calendarOnlyItemKey
import com.sloopworks.dayfold.client.formatMetaWhen

// WI-447 (ADR 0063) — pure UI viewstate over state.calendar. No DB/port access here (that's
// CalendarCheckEngine's job, ADR 0058); these functions only reshape already-loaded state for
// the Compose screens in this package.

/** The chooser's / settings-on list, grouped by masked account label — never the raw account. */
data class CalendarAccountGroup(val accountLabel: String, val calendars: List<DeviceCalendar>)

fun groupCalendarsByAccount(calendars: List<DeviceCalendar>): List<CalendarAccountGroup> =
  calendars.groupBy { it.accountLabel }.map { (label, cals) -> CalendarAccountGroup(label, cals) }

/** Settings — off/on/primer/denied/no-calendars share this read of state.calendar.settings +
 *  the OS-owned permission + the on-demand device calendar list (never persisted, ADR 0063 §3). */
data class CalendarSettingsUiState(
  val featureEnabled: Boolean,
  val permission: CalendarPermission,
  val availableCalendars: List<DeviceCalendar>,
  val selectedCalendarIds: Set<String>,
  val lastCheckAt: String?,
)

fun calendarSettingsUiState(state: AppState): CalendarSettingsUiState = CalendarSettingsUiState(
  featureEnabled = state.calendar.settings.featureEnabled,
  permission = state.calendar.check.permission,
  availableCalendars = state.calendar.availableCalendars,
  selectedCalendarIds = state.calendar.settings.selectedCalendarIds,
  lastCheckAt = state.calendar.settings.lastCheckAt,
)

/** The calendars actually included right now (settings-on list / change-calendars sheet). */
fun CalendarSettingsUiState.includedCalendars(): List<DeviceCalendar> =
  availableCalendars.filter { it.id in selectedCalendarIds }

// WI-446 (ADR 0063 §4/§5, Review.dc.html §16-22) — pure UI viewstate over state.calendar.check for
// the review flow. Mirrors CalendarSettingsUiState: no DB/port access, only reshaping already-
// loaded state (+ the already-loaded hub/calendar name lookups) for the Compose screens in this
// package. CalendarCheckEngine/CalendarReconciler own every effect and the matching ladder itself.

private fun hubTitle(state: AppState, hubId: String?): String? = hubId?.let { id -> state.hubs.hubs.firstOrNull { it.id == id }?.title }
private fun calendarName(state: AppState, calendarId: String?): String =
  calendarId?.let { id -> state.calendar.availableCalendars.firstOrNull { it.id == id } }?.displayName ?: "This calendar"
private fun calendarDot(state: AppState, calendarId: String?): String? =
  calendarId?.let { id -> state.calendar.availableCalendars.firstOrNull { it.id == id } }?.color

fun DayfoldEventCandidate.toEventPrefill(): EventPrefill =
  EventPrefill(title = title, startAt = startAt, endAt = endAt, allDay = allDay, timezone = timezone, location = location, deepLink = deepLink)

private fun candidateMeta(state: AppState, candidate: DayfoldEventCandidate): String {
  val whenLabel = formatMetaWhen(candidate.startAt) ?: candidate.startAt
  val hub = hubTitle(state, candidate.deepLink?.hubId)
  return if (hub != null) "$whenLabel · from the $hub Hub" else whenLabel
}

private fun observationMeta(state: AppState, obs: CalendarEventObservation): String =
  "${formatMetaWhen(obs.startAt) ?: obs.startAt} · ${calendarName(state, obs.calendarId)}"

// ── §16-17 List ──────────────────────────────────────────────────────────────────────────────
data class DayfoldOnlyRow(val subjectKey: String, val title: String, val meta: String, val prefill: EventPrefill, val target: DeepLinkTarget?)
data class CalendarOnlyRow(val itemKey: String, val platformEventId: String, val title: String, val meta: String, val dotColor: String?)

/** Suggested/ambiguous/differs/recurring — every kind that needs its own dedicated screen (§18-21),
 *  surfaced here as a compact tap-through row rather than inventing a third section header beyond
 *  the two the mockup names (NOTES.md's copy-inventory guardrail lists exactly those two). */
data class NeedsReviewRow(val subjectKey: String, val title: String, val kindLabel: String, val gapKind: String)

data class CalendarReviewListUiState(
  val dayfoldRows: List<DayfoldOnlyRow>,
  val needsReviewRows: List<NeedsReviewRow>,
  val calendarOnlyRows: List<CalendarOnlyRow>,
  val ignoredCount: Int,
)

private fun needsReviewLabel(gapKind: String): String = when (gapKind) {
  CalendarGapKind.SUGGESTED -> "Possible match — review"
  CalendarGapKind.AMBIGUOUS -> "Two events could match"
  CalendarGapKind.DIFFERS -> "Matched, details differ"
  CalendarGapKind.RECURRING -> "Repeats — one occurrence to review"
  else -> "Needs a closer look"
}

fun calendarReviewListUiState(state: AppState): CalendarReviewListUiState {
  val results = state.calendar.check.results
  val dayfoldRows = results.dayfoldOnly.map { c ->
    DayfoldOnlyRow(c.subjectKey, c.title, candidateMeta(state, c), c.toEventPrefill(), c.deepLink)
  }
  val needsReview = buildList {
    results.suggested.forEach { add(NeedsReviewRow(it.candidate.subjectKey, it.candidate.title, needsReviewLabel(CalendarGapKind.SUGGESTED), CalendarGapKind.SUGGESTED)) }
    results.ambiguous.forEach { add(NeedsReviewRow(it.candidate.subjectKey, it.candidate.title, needsReviewLabel(CalendarGapKind.AMBIGUOUS), CalendarGapKind.AMBIGUOUS)) }
    results.differs.forEach { add(NeedsReviewRow(it.subjectKey, it.candidate.title, needsReviewLabel(CalendarGapKind.DIFFERS), CalendarGapKind.DIFFERS)) }
    results.recurringNotices.forEach { add(NeedsReviewRow(it.candidate.subjectKey, it.candidate.title, needsReviewLabel(CalendarGapKind.RECURRING), CalendarGapKind.RECURRING)) }
  }
  val calendarOnlyRows = results.calendarOnly.map { o ->
    CalendarOnlyRow(calendarOnlyItemKey(o.platformEventId), o.platformEventId, o.title, observationMeta(state, o), calendarDot(state, o.calendarId))
  }
  return CalendarReviewListUiState(dayfoldRows, needsReview, calendarOnlyRows, state.calendar.check.ignored.size)
}

// ── §18 Suggested match ──────────────────────────────────────────────────────────────────────
data class CalendarSuggestedUiState(
  val subjectKey: String,
  val dayfoldTitle: String,
  val dayfoldMeta: String,
  val calendarTitle: String,
  val calendarMeta: String,
  val calendarDotColor: String?,
  val eventId: String,
  val evidence: List<String>,
)

fun calendarSuggestedUiState(state: AppState, subjectKey: String): CalendarSuggestedUiState? {
  val m: SuggestedMatch = state.calendar.check.results.suggested.firstOrNull { it.candidate.subjectKey == subjectKey } ?: return null
  return CalendarSuggestedUiState(
    subjectKey = subjectKey,
    dayfoldTitle = m.candidate.title, dayfoldMeta = candidateMeta(state, m.candidate),
    calendarTitle = m.observation.title, calendarMeta = observationMeta(state, m.observation),
    calendarDotColor = calendarDot(state, m.observation.calendarId),
    eventId = m.observation.platformEventId, evidence = m.evidence,
  )
}

// ── §19 Ambiguous match ──────────────────────────────────────────────────────────────────────
data class AmbiguousCandidateRow(val eventId: String, val title: String, val meta: String, val dotColor: String?)
data class CalendarAmbiguousUiState(
  val subjectKey: String,
  val dayfoldTitle: String,
  val dayfoldMeta: String,
  val candidates: List<AmbiguousCandidateRow>,
)

fun calendarAmbiguousUiState(state: AppState, subjectKey: String): CalendarAmbiguousUiState? {
  val m: AmbiguousMatch = state.calendar.check.results.ambiguous.firstOrNull { it.candidate.subjectKey == subjectKey } ?: return null
  return CalendarAmbiguousUiState(
    subjectKey = subjectKey,
    dayfoldTitle = m.candidate.title, dayfoldMeta = candidateMeta(state, m.candidate),
    candidates = m.observations.map { AmbiguousCandidateRow(it.platformEventId, it.title, observationMeta(state, it), calendarDot(state, it.calendarId)) },
  )
}

// ── §20 Details differ ───────────────────────────────────────────────────────────────────────
data class CalendarDifferUiState(
  val subjectKey: String,
  val title: String,
  val calendarName: String,
  val calendarDotColor: String?,
  val diffs: List<FieldDiff>,
)

fun fieldDiffLabel(field: String): String = when (field) {
  "title" -> "TITLE"
  "start" -> "START TIME"
  "end" -> "END TIME"
  "location" -> "LOCATION"
  else -> field.uppercase()
}

fun calendarDifferUiState(state: AppState, subjectKey: String): CalendarDifferUiState? {
  val d: DetailsDiffer = state.calendar.check.results.differs.firstOrNull { it.subjectKey == subjectKey } ?: return null
  return CalendarDifferUiState(
    subjectKey = subjectKey, title = d.candidate.title,
    calendarName = calendarName(state, d.observation.calendarId), calendarDotColor = calendarDot(state, d.observation.calendarId),
    diffs = d.diffs,
  )
}

// ── §21 Recurring event ──────────────────────────────────────────────────────────────────────
data class CalendarRecurringUiState(val subjectKey: String, val title: String, val recurrenceMeta: String, val calendarDotColor: String?)

fun calendarRecurringUiState(state: AppState, subjectKey: String): CalendarRecurringUiState? {
  val n: RecurringNotice = state.calendar.check.results.recurringNotices.firstOrNull { it.candidate.subjectKey == subjectKey } ?: return null
  val meta = "${formatMetaWhen(n.observation.startAt) ?: n.observation.startAt} · ${calendarName(state, n.observation.calendarId)}"
  return CalendarRecurringUiState(subjectKey, n.candidate.title, meta, calendarDot(state, n.observation.calendarId))
}

// ── §22 Ignored locally ──────────────────────────────────────────────────────────────────────
/** ADR 0063 §5 review action — ignoring drops an item from `results` on the SAME dispatch (nothing
 *  to review twice), so its title/meta can't be re-derived from state afterward. Most-recent-first;
 *  UndoIgnore(itemKey) targets a specific row, so undoing an older row (not just the most recent)
 *  is safe regardless of render order. */
fun ignoredKeysMostRecentFirst(check: CalendarCheckState): List<String> = check.ignoreHistory.asReversed()
