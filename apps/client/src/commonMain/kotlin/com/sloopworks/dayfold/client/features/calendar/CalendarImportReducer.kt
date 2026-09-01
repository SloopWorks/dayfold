package com.sloopworks.dayfold.client

/** The proposal in-progress under any state that carries one — null only for [ImportProposalState.None]. */
fun ImportProposalState.proposalOrNull(): CalendarImportProposal? = when (this) {
  ImportProposalState.None -> null
  is ImportProposalState.ChoosingDestination -> proposal
  is ImportProposalState.PreviewingFields -> proposal
  is ImportProposalState.ChoosingAudience -> proposal
  is ImportProposalState.Confirming -> proposal
  is ImportProposalState.Applying -> proposal
  is ImportProposalState.Saved -> proposal
  is ImportProposalState.OfflineQueued -> proposal
  is ImportProposalState.PermissionLost -> proposal
  is ImportProposalState.RoleDenied -> proposal
  is ImportProposalState.SourceChanged -> proposal
  is ImportProposalState.VersionConflict -> proposal
}

/**
 * CAL-10 (ADR 0063 §6) — pure transitions over state.calendar.importState. CalendarImportEngine
 * owns every effect (id minting, revalidation, DB/outbox writes, the calendar-binding write on
 * success); this function only ever produces the next state from the current one + the action.
 * Unreachable action/state combinations (e.g. ChooseImportDestination while Applying) are no-ops —
 * a stale UI callback firing after the machine has already moved on must never corrupt it.
 */
fun reduceCalendarImport(state: ImportProposalState, action: CalendarImportAction): ImportProposalState = when (action) {
  is CalendarImportDestinationsLoaded -> state

  is StartCalendarImport -> ImportProposalState.ChoosingDestination(action.proposal)

  is ChooseImportDestination -> when (state) {
    is ImportProposalState.ChoosingDestination ->
      ImportProposalState.PreviewingFields(state.proposal.copy(destination = action.destination))
    is ImportProposalState.RoleDenied ->
      ImportProposalState.PreviewingFields(state.proposal.copy(destination = action.destination))
    else -> state
  }

  is SetImportDescriptionOptIn -> (state as? ImportProposalState.PreviewingFields)?.let {
    ImportProposalState.PreviewingFields(it.proposal.copy(description = action.description))
  } ?: state

  ProceedImportToAudience -> (state as? ImportProposalState.PreviewingFields)?.let {
    ImportProposalState.ChoosingAudience(it.proposal)
  } ?: state

  is SetImportAudience -> (state as? ImportProposalState.ChoosingAudience)?.let { s ->
    val dest = s.proposal.destination as? ImportDestination.NewHub ?: return@let state
    ImportProposalState.ChoosingAudience(s.proposal.copy(destination = dest.copy(visibility = action.visibility, audience = action.audience)))
  } ?: state

  ProceedImportToConfirm -> (state as? ImportProposalState.ChoosingAudience)?.let {
    ImportProposalState.Confirming(it.proposal)
  } ?: state

  BackImportStep -> when (state) {
    is ImportProposalState.Confirming -> ImportProposalState.ChoosingAudience(state.proposal)
    is ImportProposalState.ChoosingAudience -> ImportProposalState.PreviewingFields(state.proposal)
    is ImportProposalState.PreviewingFields -> ImportProposalState.ChoosingDestination(state.proposal)
    is ImportProposalState.ChoosingDestination -> ImportProposalState.None
    is ImportProposalState.RoleDenied -> ImportProposalState.ChoosingDestination(state.proposal)
    else -> state
  }

  DiscardCalendarImport -> ImportProposalState.None

  is ImportApplyStarted -> (state as? ImportProposalState.Confirming)?.let {
    ImportProposalState.Applying(it.proposal, action.ids)
  } ?: state

  is ImportSaved -> when (state) {
    is ImportProposalState.Applying -> ImportProposalState.Saved(state.proposal, state.ids, action.audienceSummary)
    is ImportProposalState.OfflineQueued -> state.ids?.let {
      ImportProposalState.Saved(state.proposal, it, action.audienceSummary)
    } ?: state
    else -> state
  }

  is ImportOfflineQueued -> state.proposalOrNull()?.let { ImportProposalState.OfflineQueued(it, action.ids) } ?: state

  ImportPermissionLost -> state.proposalOrNull()?.let { ImportProposalState.PermissionLost(it) } ?: state

  ImportRoleDenied -> state.proposalOrNull()?.let { ImportProposalState.RoleDenied(it.copy(destination = null)) } ?: state

  is ImportSourceChanged -> ImportProposalState.SourceChanged(action.refreshedProposal, action.ids, action.diffs)

  is ImportVersionConflict -> state.proposalOrNull()?.let {
    ImportProposalState.VersionConflict(it, action.ids, action.audiencePreview)
  } ?: state

  ReconfirmCalendarImport -> when (state) {
    is ImportProposalState.SourceChanged -> ImportProposalState.Confirming(state.proposal)
    is ImportProposalState.VersionConflict -> ImportProposalState.Confirming(state.proposal)
    else -> state
  }
}

/** rootReducer's entry point — mirrors reduceCalendarCheck's AppState-level wrapper. */
fun reduceCalendarImportTop(state: AppState, action: CalendarImportAction): AppState =
  state.copy(
    calendar = state.calendar.copy(
      importState = reduceCalendarImport(state.calendar.importState, action),
      availableImportDestinations = if (action is CalendarImportDestinationsLoaded) {
        action.destinations
      } else {
        state.calendar.availableImportDestinations
      },
    ),
  )
