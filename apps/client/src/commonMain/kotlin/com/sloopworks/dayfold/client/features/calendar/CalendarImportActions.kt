package com.sloopworks.dayfold.client

// CAL-10 (ADR 0063 §6) — the import wizard's action vocabulary. Mirrors CalendarCheckAction: a
// marker interface so rootReducer routes the whole family in one arm, and every payload here stays
// to the typed fields the reducer needs — never a raw calendar title/location string beyond what
// the member is actively reviewing in [CalendarImportProposal] itself (which is already the
// normalized, non-calendar-identifying boundary — see CalendarImportModel.kt's header).
sealed interface CalendarImportAction : Action

/** Engine → store: normalized writable-Hub choices and the names of their current readers. */
data class CalendarImportDestinationsLoaded(
  val destinations: List<CalendarImportDestinationOption>,
) : CalendarImportAction

/** Host → engine: begin a review from a calendar-only observation (or a "differs" USE_CALENDAR
 *  choice) — the engine derives [CalendarImportProposal] and dispatches this with the result. */
data class StartCalendarImport(val proposal: CalendarImportProposal) : CalendarImportAction

/** Destination step → fields step. */
data class ChooseImportDestination(val destination: ImportDestination) : CalendarImportAction

/** Fields step: the one opt-in control (spec OD-1). [description] is null when unticked. */
data class SetImportDescriptionOptIn(val description: String?) : CalendarImportAction

/** Fields step → audience step. */
data object ProceedImportToAudience : CalendarImportAction

/** Audience step, NewHub destination only: family vs. named-audience choice (spec §2.1). */
data class SetImportAudience(val visibility: HubVisibilityChoice, val audience: List<String>) : CalendarImportAction

/** Audience step → confirm step. */
data object ProceedImportToConfirm : CalendarImportAction

/** Any step → the previous step (Back). A no-op from [ImportProposalState.None]. */
data object BackImportStep : CalendarImportAction

/** Discard the whole review — returns to [ImportProposalState.None]. */
data object DiscardCalendarImport : CalendarImportAction

/** Engine → store: confirm passed revalidation, ids are minted, ops are enqueued. */
data class ImportApplyStarted(val ids: ImportOpIds) : CalendarImportAction

/** Engine → store: every op in the chain Acked (spec state 2). */
data class ImportSaved(val audienceSummary: String) : CalendarImportAction

/** Engine → store: no connectivity, or the sender did not finish within the bounded poll (state 3). */
data class ImportOfflineQueued(val ids: ImportOpIds?) : CalendarImportAction

/** Engine → store: calendar access was revoked/restricted before the chain enqueued (state 4). */
data object ImportPermissionLost : CalendarImportAction

/** Engine → store: revalidation (or a drained op) found the role denies the write (state 5). */
data object ImportRoleDenied : CalendarImportAction

/** Engine → store: the source event changed since the review-start snapshot (state 6). */
data class ImportSourceChanged(
  val ids: ImportOpIds,
  val diffs: List<ImportFieldDiff>,
  val refreshedProposal: CalendarImportProposal,
) : CalendarImportAction

/** Engine → store: the destination Hub's version/audience changed since confirm (state 7). */
data class ImportVersionConflict(val ids: ImportOpIds, val audiencePreview: List<String>) : CalendarImportAction

/** User re-confirms after reviewing a source-changed diff or a version conflict — re-enters
 *  Confirming with (possibly refreshed) proposal/ids retained for re-apply. */
data object ReconfirmCalendarImport : CalendarImportAction
