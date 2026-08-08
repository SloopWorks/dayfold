package com.sloopworks.dayfold.client

fun reduceRoutines(state: AppState, action: Any): AppState {
  val routine = state.routines
  return when (action) {
    is OpenSmartBriefings, is RestoreSmartBriefings -> if (
      routinePreviewAvailable(state) && state.session.activeFamilyId != null
    ) {
      val destination = if (
        routine.destination == RoutineDestination.ENTRY &&
        routine.lifecycle in setOf(RoutineLifecycle.ACTIVE, RoutineLifecycle.PAUSED, RoutineLifecycle.REVOKING, RoutineLifecycle.REVOKED)
      ) RoutineDestination.ACTIVE else routine.destination
      state.copy(routines = routine.copy(destination = destination))
    } else state
    is CloseSmartBriefings -> state.copy(routines = routine.copy(detailsOpen = false, revokeSheetOpen = false))
    is RoutineProviderSelected -> state.mutateRoutineIfAllowed {
      copy(provider = action.provider, privacyAcknowledged = false, recovery = null)
    }
    is RoutineSourcesSelected -> state.mutateRoutineIfAllowed {
      copy(
        externalSources = action.sources.toSet(),
        sourceStatuses = action.sources.associateWith { RoutineSourceStatus.REQUESTED }.toMap(),
        privacyAcknowledged = false,
        recovery = null,
      )
    }
    is RoutineHubFixtureSelected -> state.mutateRoutineIfAllowed {
      copy(hubFixture = action.fixture, recovery = null)
    }
    is RoutineHubSelected -> state.mutateRoutineIfAllowed {
      val eligible = routineHubOptions(state.copy(routines = this)).any { it.hub == action.hub && it.eligible }
      if (action.hub == null || eligible) copy(selectedHub = action.hub, recovery = null) else this
    }
    is RoutineScheduleSelected -> state.mutateRoutineIfAllowed {
      copy(schedule = action.schedule, privacyAcknowledged = false, recovery = null)
    }
    is RoutinePrivacyAcknowledged -> state.mutateRoutineIfAllowed {
      copy(privacyAcknowledged = action.acknowledged, recovery = null)
    }
    is RoutineContinue -> continueRoutine(state)
    is RoutineNavigateBack -> state.copy(routines = routine.copy(destination = previousRoutineDestination(routine.destination)))
    is RoutineOperationStarted -> startRoutineOperation(state, action)
    is RoutinePreparationCompleted -> if (routine.matches(RoutineOperation.PREPARATION, action.generation)) state.copy(
      routines = routine.copy(
        lifecycle = RoutineLifecycle.AWAITING_PROVIDER,
        destination = RoutineDestination.HANDOFF,
        pendingOperation = null,
        recovery = null,
      ),
    ) else state
    is RoutinePreparationFailed -> if (routine.matches(RoutineOperation.PREPARATION, action.generation)) state.copy(
      routines = routine.copy(
        lifecycle = RoutineLifecycle.OFF,
        destination = RoutineDestination.HANDOFF,
        pendingOperation = null,
        recovery = action.recovery.forPreview(),
      ),
    ) else state
    is RoutineProviderReturnCompleted -> finishProviderReturn(state, action)
    is RoutineRunFinished -> finishRoutineRun(state, action)
    is RoutineDraftPresented -> presentRoutineDraft(state, action)
    is RoutineDraftDecisionPreviewed -> if (
      routineCanMutate(state) && routine.draft == RoutineDraftFixture.FIRST_DRAFT
    ) state.copy(
      routines = routine.copy(draftDecision = action.decision, destination = RoutineDestination.ACTIVE),
    ) else state
    is RoutineRevokeFinished -> finishRoutineRevoke(state, action)
    is RoutineReviewSources -> state.mutateRoutineIfAllowed {
      if (destination in setOf(RoutineDestination.STATUS, RoutineDestination.ACTIVE, RoutineDestination.DRAFT)) {
        copy(destination = RoutineDestination.SOURCES, recovery = null)
      } else this
    }
    is RoutineReviewPrivacy -> state.mutateRoutineIfAllowed {
      if (destination in setOf(RoutineDestination.STATUS, RoutineDestination.ACTIVE, RoutineDestination.DRAFT)) {
        copy(destination = RoutineDestination.PRIVACY, privacyAcknowledged = false, recovery = null)
      } else this
    }
    is OpenRoutineDetails -> if (routinePreviewAvailable(state)) state.copy(routines = routine.copy(detailsOpen = true)) else state
    is CloseRoutineDetails -> state.copy(routines = routine.copy(detailsOpen = false))
    is OpenRoutineRevokeSheet -> if (
      routineCanConfigure(state) && (
        routine.lifecycle == RoutineLifecycle.REVOKING ||
          (!routine.offline && routine.lifecycle in setOf(RoutineLifecycle.ACTIVE, RoutineLifecycle.PAUSED))
      )
    ) state.copy(routines = routine.copy(revokeSheetOpen = true)) else state
    is CloseRoutineRevokeSheet -> state.copy(routines = routine.copy(revokeSheetOpen = false))
    is RoutineOfflineChanged -> if (routinePreviewAvailable(state)) state.copy(routines = routine.copy(offline = action.offline)) else state
    else -> state
  }
}

private inline fun AppState.mutateRoutineIfAllowed(block: RoutineState.() -> RoutineState): AppState =
  if (routineCanMutate(this)) copy(routines = routines.block()) else this

private fun continueRoutine(state: AppState): AppState {
  if (!routineCanMutate(state)) return state
  val routine = state.routines
  val next = when (routine.destination) {
    RoutineDestination.ENTRY -> RoutineDestination.PROVIDER
    RoutineDestination.PROVIDER -> if (routine.provider != RoutineProvider.UNSELECTED) RoutineDestination.SOURCES else null
    RoutineDestination.SOURCES -> RoutineDestination.HUB_ACCESS
    RoutineDestination.HUB_ACCESS -> if (routineSelectedHubOption(state) != null) RoutineDestination.SCHEDULE else null
    RoutineDestination.SCHEDULE -> if (routine.schedule != null) RoutineDestination.PRIVACY else null
    RoutineDestination.PRIVACY -> if (routine.privacyAcknowledged) RoutineDestination.HANDOFF else null
    // Explicit local-fixture control. It creates no external task, callback, grant, or URL.
    RoutineDestination.HANDOFF -> RoutineDestination.STATUS
    RoutineDestination.STATUS -> when {
      routine.draft != null -> RoutineDestination.DRAFT
      routine.lastRun != null -> RoutineDestination.ACTIVE
      else -> null
    }
    RoutineDestination.DRAFT -> RoutineDestination.ACTIVE
    RoutineDestination.ACTIVE -> null
  } ?: return state
  val lifecycle = if (routine.destination == RoutineDestination.HANDOFF) RoutineLifecycle.VERIFYING else routine.lifecycle
  return state.copy(routines = routine.copy(destination = next, lifecycle = lifecycle, recovery = null))
}

private fun previousRoutineDestination(destination: RoutineDestination): RoutineDestination = when (destination) {
  RoutineDestination.ENTRY -> RoutineDestination.ENTRY
  RoutineDestination.PROVIDER -> RoutineDestination.ENTRY
  RoutineDestination.SOURCES -> RoutineDestination.PROVIDER
  RoutineDestination.HUB_ACCESS -> RoutineDestination.SOURCES
  RoutineDestination.SCHEDULE -> RoutineDestination.HUB_ACCESS
  RoutineDestination.PRIVACY -> RoutineDestination.SCHEDULE
  RoutineDestination.HANDOFF -> RoutineDestination.PRIVACY
  RoutineDestination.STATUS -> RoutineDestination.HANDOFF
  RoutineDestination.DRAFT -> RoutineDestination.ACTIVE
  RoutineDestination.ACTIVE -> RoutineDestination.ACTIVE
}

private fun startRoutineOperation(state: AppState, action: RoutineOperationStarted): AppState {
  if (!routineCanMutate(state) || action.generation <= 0L) return state
  val routine = state.routines
  // Every attempt gets a fresh generation. Reusing a completed/failed generation would let a
  // delayed terminal from the previous attempt match the retry.
  val acceptedGeneration = action.generation > routine.operationGeneration
  if (!acceptedGeneration || !routineOperationAllowed(routine, action.operation)) return state
  val lifecycle = when (action.operation) {
    RoutineOperation.PREPARATION -> RoutineLifecycle.PREPARING
    RoutineOperation.REVOKE -> RoutineLifecycle.REVOKING
    else -> RoutineLifecycle.VERIFYING
  }
  return state.copy(routines = routine.copy(
    lifecycle = lifecycle,
    operationGeneration = action.generation,
    pendingOperation = action.operation,
    recovery = null,
  ))
}

private fun routineOperationAllowed(state: RoutineState, operation: RoutineOperation): Boolean = when (operation) {
  RoutineOperation.PREPARATION -> state.destination == RoutineDestination.HANDOFF
  RoutineOperation.PROVIDER_RETURN -> state.destination in setOf(RoutineDestination.HANDOFF, RoutineDestination.STATUS)
  RoutineOperation.SOURCE_PROBE ->
    state.destination == RoutineDestination.STATUS && state.lifecycle != RoutineLifecycle.REVOKED
  RoutineOperation.RUN ->
    state.destination in setOf(RoutineDestination.STATUS, RoutineDestination.ACTIVE) &&
      state.lifecycle in setOf(RoutineLifecycle.VERIFYING, RoutineLifecycle.ACTIVE, RoutineLifecycle.PAUSED)
  RoutineOperation.DRAFT_REFRESH ->
    state.destination == RoutineDestination.DRAFT && state.lifecycle == RoutineLifecycle.ACTIVE
  RoutineOperation.REVOKE -> state.lifecycle in setOf(RoutineLifecycle.ACTIVE, RoutineLifecycle.PAUSED)
}

private fun RoutineState.matches(operation: RoutineOperation, generation: Long): Boolean =
  pendingOperation == operation && operationGeneration == generation

private fun finishProviderReturn(state: AppState, action: RoutineProviderReturnCompleted): AppState {
  val routine = state.routines
  if (!routine.matches(RoutineOperation.PROVIDER_RETURN, action.generation)) return state
  return if (action.confirmed) state.copy(routines = routine.copy(
    lifecycle = RoutineLifecycle.VERIFYING,
    destination = RoutineDestination.STATUS,
    pendingOperation = null,
    syntheticGrantMarker = true,
    providerHealth = RoutineProviderHealth.LAST_SEEN,
    recovery = null,
  )) else state.copy(routines = routine.copy(
    lifecycle = RoutineLifecycle.AWAITING_PROVIDER,
    destination = RoutineDestination.STATUS,
    pendingOperation = null,
    syntheticGrantMarker = false,
    providerHealth = RoutineProviderHealth.NEVER_SEEN,
    recovery = action.recovery?.forPreview() ?: routineRecoveryFor(RoutineRecoveryReason.ROUTINE_NOT_CONFIRMED),
  ))
}

private fun finishRoutineRun(state: AppState, action: RoutineRunFinished): AppState {
  val routine = state.routines
  if (!routine.matches(RoutineOperation.RUN, action.generation) && !routine.matches(RoutineOperation.SOURCE_PROBE, action.generation)) return state
  val unexpectedSources = action.sourceStatuses.keys - routine.externalSources
  val incompleteRequestedSources = routine.externalSources.filterTo(mutableSetOf()) { source ->
    action.sourceStatuses[source] != RoutineSourceStatus.OBSERVED
  }
  val safeSourceStatuses = routine.externalSources.associateWith { source ->
    action.sourceStatuses[source] ?: RoutineSourceStatus.REQUESTED
  }.toMap()
  val incompleteGoodClaim = incompleteRequestedSources.isNotEmpty() &&
    action.result in setOf(RoutineRunResult.SUCCESS, RoutineRunResult.NO_CHANGES)
  val effectiveResult = if (incompleteGoodClaim) RoutineRunResult.PARTIAL else action.result
  val summary = RoutineRunSummary(effectiveResult, action.counts, safeSourceStatuses)
  val recovery = if (unexpectedSources.isNotEmpty()) {
    routineRecoveryFor(RoutineRecoveryReason.UNEXPECTED_SOURCE_SET)
  } else if (incompleteGoodClaim) {
    routineRecoveryFor(RoutineRecoveryReason.PARTIAL_SOURCE_SET)
  } else action.recovery?.forPreview() ?: when (action.result) {
    RoutineRunResult.SUCCESS, RoutineRunResult.NO_CHANGES -> null
    RoutineRunResult.PARTIAL -> routineRecoveryFor(RoutineRecoveryReason.PARTIAL_SOURCE_SET)
    RoutineRunResult.REJECTED -> routineRecoveryFor(RoutineRecoveryReason.POLICY_REJECTED)
    RoutineRunResult.FAILED -> routineRecoveryFor(RoutineRecoveryReason.RUN_REPORTED_FAILED)
  }
  val isGood = unexpectedSources.isEmpty() && incompleteRequestedSources.isEmpty() &&
    action.result in setOf(RoutineRunResult.SUCCESS, RoutineRunResult.NO_CHANGES)
  return state.copy(routines = routine.copy(
    lifecycle = RoutineLifecycle.ACTIVE,
    destination = RoutineDestination.ACTIVE,
    pendingOperation = null,
    sourceStatuses = safeSourceStatuses,
    providerHealth = if (action.result == RoutineRunResult.FAILED) RoutineProviderHealth.REPORTED_FAILED else RoutineProviderHealth.LAST_SEEN,
    lastRun = summary,
    lastGoodRun = if (isGood) summary else routine.lastGoodRun,
    recovery = recovery,
    syntheticFinishMarker = true,
  ))
}

private fun RoutineRecoveryEnvelope.forPreview(): RoutineRecoveryEnvelope =
  if (
    schemaVersion != ROUTINE_RECOVERY_SCHEMA_VERSION ||
    phase == RoutineRecoveryPhase.UNKNOWN ||
    reason == RoutineRecoveryReason.UNKNOWN ||
    retryability == RoutineRetryability.UNKNOWN ||
    recommendedAction == RoutineRecommendedAction.UNKNOWN
  ) unknownRoutineRecovery()
  else copy(supportCodeAvailable = false)

private fun presentRoutineDraft(state: AppState, action: RoutineDraftPresented): AppState {
  val routine = state.routines
  if (!routine.matches(RoutineOperation.RUN, action.generation) && !routine.matches(RoutineOperation.DRAFT_REFRESH, action.generation)) return state
  val allRequestedSourcesObserved = routine.externalSources.all { source ->
    routine.sourceStatuses[source] == RoutineSourceStatus.OBSERVED
  }
  if (!allRequestedSourcesObserved) return state.copy(routines = routine.copy(
    lifecycle = RoutineLifecycle.ACTIVE,
    destination = RoutineDestination.ACTIVE,
    pendingOperation = null,
    draft = null,
    draftDecision = null,
    recovery = routineRecoveryFor(RoutineRecoveryReason.PARTIAL_SOURCE_SET),
  ))
  val summary = RoutineRunSummary(RoutineRunResult.SUCCESS, RoutineSafeCounts(candidates = 1), routine.sourceStatuses)
  val conflicted = action.draft == RoutineDraftFixture.STALE_OR_CONFLICTED
  return state.copy(routines = routine.copy(
    lifecycle = RoutineLifecycle.ACTIVE,
    destination = RoutineDestination.DRAFT,
    pendingOperation = null,
    draft = action.draft,
    draftDecision = null,
    lastRun = summary,
    lastGoodRun = if (conflicted) routine.lastGoodRun else summary,
    recovery = if (conflicted) routineRecoveryFor(RoutineRecoveryReason.CONFLICT) else null,
    syntheticFinishMarker = true,
  ))
}

private fun finishRoutineRevoke(state: AppState, action: RoutineRevokeFinished): AppState {
  val routine = state.routines
  if (!routine.matches(RoutineOperation.REVOKE, action.generation)) return state
  return when (action.outcome) {
    RoutineRevokeOutcome.REVOKED -> state.copy(routines = routine.copy(
      lifecycle = RoutineLifecycle.REVOKED,
      destination = RoutineDestination.ACTIVE,
      pendingOperation = null,
      syntheticGrantMarker = false,
      recovery = null,
    ))
    RoutineRevokeOutcome.FAILED -> state.copy(routines = routine.copy(
      lifecycle = RoutineLifecycle.ACTIVE,
      destination = RoutineDestination.ACTIVE,
      pendingOperation = null,
      recovery = routineRecoveryFor(RoutineRecoveryReason.REVOKE_FAILED),
    ))
    RoutineRevokeOutcome.REVOKED_PROVIDER_TASK_REMAINS -> state.copy(routines = routine.copy(
      lifecycle = RoutineLifecycle.REVOKED,
      destination = RoutineDestination.ACTIVE,
      pendingOperation = null,
      syntheticGrantMarker = false,
      recovery = routineRecoveryFor(RoutineRecoveryReason.PROVIDER_TASK_REMAINS),
    ))
  }
}

/** Role loss is derived from MembershipsLoaded, not trusted to a UI-only gate. */
fun routineStateAfterAuthorityChange(previous: AppState, updated: AppState): RoutineState {
  if (routineAuthority(previous) != RoutineAuthority.OWNER || routineAuthority(updated) == RoutineAuthority.OWNER) {
    return updated.routines
  }
  val routine = updated.routines
  val inOwnerSetup = routine.destination in setOf(
    RoutineDestination.PROVIDER,
    RoutineDestination.SOURCES,
    RoutineDestination.HUB_ACCESS,
    RoutineDestination.SCHEDULE,
    RoutineDestination.PRIVACY,
    RoutineDestination.HANDOFF,
  )
  val lifecycle = when (routine.lifecycle) {
    RoutineLifecycle.PREPARING, RoutineLifecycle.AWAITING_PROVIDER, RoutineLifecycle.VERIFYING ->
      if (routine.syntheticFinishMarker) RoutineLifecycle.ACTIVE else RoutineLifecycle.OFF
    RoutineLifecycle.REVOKING -> RoutineLifecycle.ACTIVE
    else -> routine.lifecycle
  }
  return routine.copy(
    destination = if (inOwnerSetup) RoutineDestination.ENTRY else routine.destination,
    lifecycle = lifecycle,
    pendingOperation = null,
    revokeSheetOpen = false,
  )
}
