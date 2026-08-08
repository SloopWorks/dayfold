package com.sloopworks.dayfold.client

/** Marker used by privacy-bounded debug tooling to redact preview action names. */
sealed interface RoutinePreviewAction : Action

data object OpenSmartBriefings : RoutinePreviewAction
data object RestoreSmartBriefings : RoutinePreviewAction
data object CloseSmartBriefings : RoutinePreviewAction
data object RoutineContinue : RoutinePreviewAction
data object RoutineNavigateBack : RoutinePreviewAction

data class RoutineProviderSelected(val provider: RoutineProvider) : RoutinePreviewAction
data class RoutineSourcesSelected(val sources: Set<RoutineExternalSource>) : RoutinePreviewAction
data class RoutineHubFixtureSelected(val fixture: RoutineHubFixture) : RoutinePreviewAction
data class RoutineHubSelected(val hub: RoutineSyntheticHub?) : RoutinePreviewAction
data class RoutineScheduleSelected(val schedule: RoutineSchedulePreset) : RoutinePreviewAction
data class RoutinePrivacyAcknowledged(val acknowledged: Boolean) : RoutinePreviewAction

data class RoutineOperationStarted(
  val operation: RoutineOperation,
  val generation: Long,
) : RoutinePreviewAction

data class RoutinePreparationCompleted(val generation: Long) : RoutinePreviewAction
data class RoutinePreparationFailed(
  val generation: Long,
  val recovery: RoutineRecoveryEnvelope = routineRecoveryFor(RoutineRecoveryReason.PREPARATION_FAILED),
) : RoutinePreviewAction

data class RoutineProviderReturnCompleted(
  val generation: Long,
  val confirmed: Boolean,
  val recovery: RoutineRecoveryEnvelope? = null,
) : RoutinePreviewAction

data class RoutineRunFinished(
  val generation: Long,
  val result: RoutineRunResult,
  val counts: RoutineSafeCounts = RoutineSafeCounts(),
  val sourceStatuses: Map<RoutineExternalSource, RoutineSourceStatus> = emptyMap(),
  val recovery: RoutineRecoveryEnvelope? = null,
) : RoutinePreviewAction

data class RoutineDraftPresented(
  val generation: Long,
  val draft: RoutineDraftFixture,
) : RoutinePreviewAction

data class RoutineDraftDecisionPreviewed(val decision: RoutineDraftDecision) : RoutinePreviewAction
data class RoutineRevokeFinished(val generation: Long, val outcome: RoutineRevokeOutcome) : RoutinePreviewAction

/** Closed, local recovery navigation. Neither action invokes a provider or widens access. */
data object RoutineReviewSources : RoutinePreviewAction
data object RoutineReviewPrivacy : RoutinePreviewAction

data object OpenRoutineDetails : RoutinePreviewAction
data object CloseRoutineDetails : RoutinePreviewAction
data object OpenRoutineRevokeSheet : RoutinePreviewAction
data object CloseRoutineRevokeSheet : RoutinePreviewAction
data class RoutineOfflineChanged(val offline: Boolean) : RoutinePreviewAction
