package com.sloopworks.dayfold.client

data class RoutineHubOption(
  val hub: RoutineSyntheticHub,
  val title: String,
  val eligible: Boolean,
  val restricted: Boolean,
)

fun routinePreviewAvailable(state: AppState): Boolean = state.routines.capability == RoutineCapability.PREVIEW

fun routineAuthority(state: AppState): RoutineAuthority {
  val active = state.session.families.firstOrNull {
    it.familyId == state.session.activeFamilyId && it.status == "active"
  }
  return if (active?.role == "owner") RoutineAuthority.OWNER else RoutineAuthority.ADULT_READ_ONLY
}

fun routineCanConfigure(state: AppState): Boolean =
  routinePreviewAvailable(state) &&
    state.session.activeFamilyId != null &&
    routineAuthority(state) == RoutineAuthority.OWNER

fun routineCanMutate(state: AppState): Boolean =
  routineCanConfigure(state) && !state.routines.offline &&
    state.routines.lifecycle !in setOf(RoutineLifecycle.REVOKING, RoutineLifecycle.REVOKED)

fun routineHubOptions(state: AppState): List<RoutineHubOption> = when (state.routines.hubFixture) {
  RoutineHubFixture.LOADING, RoutineHubFixture.EMPTY -> emptyList()
  RoutineHubFixture.AVAILABLE -> listOf(
    RoutineHubOption(RoutineSyntheticHub.FAMILY_BRIEFING, "Family briefing", eligible = true, restricted = false),
    RoutineHubOption(RoutineSyntheticHub.RESTRICTED_EXAMPLE, "Restricted example", eligible = false, restricted = true),
  )
  RoutineHubFixture.SELECTED_REMOVED -> listOf(
    RoutineHubOption(RoutineSyntheticHub.RESTRICTED_EXAMPLE, "Restricted example", eligible = false, restricted = true),
  )
}

fun routineSelectedHubOption(state: AppState): RoutineHubOption? =
  routineHubOptions(state).firstOrNull { it.hub == state.routines.selectedHub && it.eligible }

fun nextRoutineOperationGeneration(state: AppState): Long = state.routines.operationGeneration + 1L

fun routineRecoveryFromWire(
  schemaVersion: Int,
  phase: String,
  reason: String,
  retryability: String,
  recommendedAction: String,
): RoutineRecoveryEnvelope {
  if (schemaVersion != ROUTINE_RECOVERY_SCHEMA_VERSION) return unknownRoutineRecovery()
  val decoded = RoutineRecoveryEnvelope(
    phase = RoutineRecoveryPhase.fromWire(phase),
    reason = RoutineRecoveryReason.fromWire(reason),
    retryability = RoutineRetryability.fromWire(retryability),
    recommendedAction = RoutineRecommendedAction.fromWire(recommendedAction),
  )
  return if (
    decoded.phase == RoutineRecoveryPhase.UNKNOWN ||
    decoded.reason == RoutineRecoveryReason.UNKNOWN ||
    decoded.retryability == RoutineRetryability.UNKNOWN ||
    decoded.recommendedAction == RoutineRecommendedAction.UNKNOWN
  ) unknownRoutineRecovery() else decoded
}

fun unknownRoutineRecovery(): RoutineRecoveryEnvelope = RoutineRecoveryEnvelope(
  phase = RoutineRecoveryPhase.UNKNOWN,
  reason = RoutineRecoveryReason.UNKNOWN,
  retryability = RoutineRetryability.UNKNOWN,
  recommendedAction = RoutineRecommendedAction.CONTACT_SUPPORT,
)

fun routineRecoveryFor(reason: RoutineRecoveryReason): RoutineRecoveryEnvelope = when (reason) {
  RoutineRecoveryReason.PREPARATION_FAILED -> RoutineRecoveryEnvelope(
    phase = RoutineRecoveryPhase.PROVIDER_HANDOFF,
    reason = reason,
    retryability = RoutineRetryability.USER_ACTION,
    recommendedAction = RoutineRecommendedAction.RETRY,
  )
  RoutineRecoveryReason.ROUTINE_NOT_CONFIRMED,
  RoutineRecoveryReason.APP_RETURN_LOST,
  RoutineRecoveryReason.LATE_CALLBACK -> RoutineRecoveryEnvelope(
    phase = RoutineRecoveryPhase.PROVIDER_HANDOFF,
    reason = reason,
    retryability = RoutineRetryability.USER_ACTION,
    recommendedAction = RoutineRecommendedAction.RESUME_PROVIDER,
  )
  RoutineRecoveryReason.PARTIAL_SOURCE_SET -> RoutineRecoveryEnvelope(
    phase = RoutineRecoveryPhase.SOURCE_PROBE,
    reason = reason,
    retryability = RoutineRetryability.USER_ACTION,
    recommendedAction = RoutineRecommendedAction.CONTINUE_REVIEW_ONLY,
  )
  RoutineRecoveryReason.POLICY_REJECTED -> RoutineRecoveryEnvelope(
    phase = RoutineRecoveryPhase.VALIDATION,
    reason = reason,
    retryability = RoutineRetryability.FINAL,
    recommendedAction = RoutineRecommendedAction.REVIEW_POLICY,
  )
  RoutineRecoveryReason.CONFLICT -> RoutineRecoveryEnvelope(
    phase = RoutineRecoveryPhase.STAGE,
    reason = reason,
    retryability = RoutineRetryability.USER_ACTION,
    recommendedAction = RoutineRecommendedAction.REFRESH_DRAFT,
  )
  RoutineRecoveryReason.REVOKE_FAILED -> RoutineRecoveryEnvelope(
    phase = RoutineRecoveryPhase.REVOKE,
    reason = reason,
    retryability = RoutineRetryability.USER_ACTION,
    recommendedAction = RoutineRecommendedAction.RETRY,
  )
  RoutineRecoveryReason.PROVIDER_TASK_REMAINS -> RoutineRecoveryEnvelope(
    phase = RoutineRecoveryPhase.REVOKE,
    reason = reason,
    retryability = RoutineRetryability.USER_ACTION,
    recommendedAction = RoutineRecommendedAction.RESUME_PROVIDER,
  )
  RoutineRecoveryReason.RUN_REPORTED_FAILED -> RoutineRecoveryEnvelope(
    phase = RoutineRecoveryPhase.FINISH,
    reason = reason,
    retryability = RoutineRetryability.AUTOMATIC,
    recommendedAction = RoutineRecommendedAction.RETRY,
  )
  RoutineRecoveryReason.UNKNOWN -> unknownRoutineRecovery()
  else -> RoutineRecoveryEnvelope(
    phase = RoutineRecoveryPhase.FINISH,
    reason = reason,
    retryability = RoutineRetryability.USER_ACTION,
    recommendedAction = RoutineRecommendedAction.CONTACT_SUPPORT,
  )
}
