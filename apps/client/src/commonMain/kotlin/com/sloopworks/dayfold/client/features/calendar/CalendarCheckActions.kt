package com.sloopworks.dayfold.client

// CAL-4 (ADR 0063 §4/§5) — the Calendar Check engine/reducer's action vocabulary. A marker
// interface (mirrors ResponseAction/RoutineAction) so rootReducer can route the whole family in
// one arm and debug tooling can recognize it at a glance — calendar content must never be logged,
// so keep every payload here to the typed fields the reducer actually needs (subjectKey/eventId/
// field name), never a raw title/location string.
sealed interface CalendarCheckAction : Action

/** UI/host → engine: begin a reconciliation pass. Pure reducer effect: checkInProgress = true. */
data object StartCalendarCheck : CalendarCheckAction

/** Engine → store: the sole writer of state.calendar.check.results (mirrors NowContentLoaded). */
data class CalendarCheckCompleted(
  val results: ReconcileResult,
  val permission: CalendarPermission,
  val checkedAt: String,
  // True when the pass could not do a real comparison (permission not granted, feature off, or
  // no calendars selected) — results is empty because nothing was compared, not because it's clear.
  val stale: Boolean = false,
  // Persisted device-local ignore keys, oldest first. Null keeps reducer-only test fixtures intact;
  // production engine completions always supply the DB-backed list.
  val ignoredKeys: List<String>? = null,
) : CalendarCheckAction

/** User confirms a suggested match (rung c) or a chosen ambiguous candidate (rung b) is correct. */
data class ConfirmMatch(val subjectKey: String, val eventId: String) : CalendarCheckAction

/** User rejects a suggested match — the candidate returns to the plain dayfold-only gap. */
data class KeepSeparate(val subjectKey: String) : CalendarCheckAction

/** User picks the correct event among an ambiguous candidate's multiple strict hits. */
data class ResolveAmbiguous(val subjectKey: String, val chosenEventId: String) : CalendarCheckAction

/** Local, undoable dismissal. [itemKey] is a subjectKey or "calendarEvent:<platformEventId>". */
data class IgnoreItem(val itemKey: String) : CalendarCheckAction

/** Undoes ignoring [itemKey] specifically (the Ignored screen offers per-row Undo, not just the
 *  most recent). A no-op if [itemKey] was never ignored. */
data class UndoIgnore(val itemKey: String) : CalendarCheckAction

/** Resolves one diverging field on a matched-but-different subject (ADR 0063 §5). */
data class FieldChoice(val subjectKey: String, val field: String, val resolution: FieldResolution) : CalendarCheckAction

/** Dismisses a recurring-series notice without matching it (first-slice limit, ADR 0063 §4). */
data class KeepSeriesCalendarOnly(val subjectKey: String) : CalendarCheckAction

/** Clears every local binding/ignore/review decision on this device — a feature-scoped reset, not a sign-out. */
data object ResetLocalMatches : CalendarCheckAction

/** Flips which side owns the generic start-time alert for a matched subject (ADR 0063 §7). Reversible. */
data class SetNotificationOwner(val subjectKey: String, val owner: CalendarNotificationOwner) : CalendarCheckAction

/** Clears a prior editor outcome before a new native handoff starts. */
data object CalendarEditorOpened : CalendarCheckAction

/** CAL-9 — the platform editor handoff's completion outcome, routed here by CalendarCheckEngine
 *  from CalendarPort.openEventEditor's onResult callback. The shared action every platform's
 *  return-state UI reads (Native-Handoff.dc.html §11). */
data class CalendarEditorReturned(val outcome: CalendarEditorOutcome) : CalendarCheckAction
