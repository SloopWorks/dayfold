package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Small cross-target contract suite for the preview reducer's safety-critical invariants. */
class RoutineContractCommonTest {
  private fun membership(familyId: String = "synthetic-family") = FamilyMembership(
    familyId = familyId,
    name = "Synthetic family",
    role = "owner",
    status = "active",
  )

  private fun state(
    routine: RoutineState = RoutineState.preview(),
    route: Route = Route.Account,
  ) = AppState(
    session = SessionState(
      session = Session("synthetic-access", "synthetic-refresh"),
      families = listOf(membership()),
      activeFamilyId = "synthetic-family",
    ),
    navigation = NavigationState(route = route),
    routines = routine,
  )

  @Test
  fun hiddenCapabilityCannotOpenButPreviewCapabilityCan() {
    val hidden = state(routine = RoutineState())
    assertEquals(hidden, rootReducer(hidden, OpenSmartBriefings))
    assertEquals(hidden, rootReducer(hidden, RestoreSmartBriefings))

    val preview = rootReducer(state(), OpenSmartBriefings)
    assertEquals(Route.SmartBriefings, preview.navigation.route)
  }

  @Test
  fun unknownRecoveryWireValuesFailClosed() {
    val recovery = routineRecoveryFromWire(
      schemaVersion = ROUTINE_RECOVERY_SCHEMA_VERSION,
      phase = "future-phase",
      reason = "provider returned private text",
      retryability = "user_action",
      recommendedAction = "retry",
    )

    assertEquals(RoutineRecoveryPhase.UNKNOWN, recovery.phase)
    assertEquals(RoutineRecoveryReason.UNKNOWN, recovery.reason)
    assertEquals(RoutineRetryability.UNKNOWN, recovery.retryability)
    assertEquals(RoutineRecommendedAction.CONTACT_SUPPORT, recovery.recommendedAction)
    assertFalse(recovery.supportCodeAvailable)
  }

  @Test
  fun operationGenerationsFenceLateAndReusedTerminals() {
    val handoff = state(routine = RoutineState.preview().copy(destination = RoutineDestination.HANDOFF))
    val started = rootReducer(
      handoff,
      RoutineOperationStarted(RoutineOperation.PREPARATION, generation = 2),
    )

    assertEquals(started, rootReducer(started, RoutinePreparationCompleted(generation = 1)))
    val completed = rootReducer(started, RoutinePreparationCompleted(generation = 2))
    assertEquals(RoutineLifecycle.AWAITING_PROVIDER, completed.routines.lifecycle)
    assertNull(completed.routines.pendingOperation)
    assertEquals(
      completed,
      rootReducer(completed, RoutineOperationStarted(RoutineOperation.PREPARATION, generation = 2)),
    )
  }

  @Test
  fun everyRequestedExternalSourceNeedsCurrentObservedEvidenceBeforeLastGoodOrDraft() {
    val status = state(routine = RoutineState.preview().copy(
      destination = RoutineDestination.STATUS,
      lifecycle = RoutineLifecycle.VERIFYING,
      externalSources = setOf(RoutineExternalSource.GMAIL, RoutineExternalSource.GOOGLE_CALENDAR),
      sourceStatuses = mapOf(
        RoutineExternalSource.GMAIL to RoutineSourceStatus.REQUESTED,
        RoutineExternalSource.GOOGLE_CALENDAR to RoutineSourceStatus.REQUESTED,
      ),
    ))
    val started = rootReducer(status, RoutineOperationStarted(RoutineOperation.RUN, generation = 1))
    val prematureDraft = rootReducer(
      started,
      RoutineDraftPresented(generation = 1, draft = RoutineDraftFixture.FIRST_DRAFT),
    )

    assertNull(prematureDraft.routines.draft)
    assertFalse(prematureDraft.routines.syntheticFinishMarker)
    assertEquals(RoutineRecoveryReason.PARTIAL_SOURCE_SET, prematureDraft.routines.recovery?.reason)

    val incomplete = rootReducer(
      started,
      RoutineRunFinished(
        generation = 1,
        result = RoutineRunResult.NO_CHANGES,
        sourceStatuses = mapOf(RoutineExternalSource.GMAIL to RoutineSourceStatus.OBSERVED),
      ),
    )

    assertEquals(RoutineRunResult.PARTIAL, incomplete.routines.lastRun?.result)
    assertEquals(RoutineRecoveryReason.PARTIAL_SOURCE_SET, incomplete.routines.recovery?.reason)
    assertNull(incomplete.routines.lastGoodRun)
    assertEquals(
      RoutineSourceStatus.REQUESTED,
      incomplete.routines.sourceStatuses[RoutineExternalSource.GOOGLE_CALENDAR],
    )

    val allObservedStart = rootReducer(
      status.copy(routines = status.routines.copy(operationGeneration = 1)),
      RoutineOperationStarted(RoutineOperation.RUN, generation = 2),
    )
    val allObserved = rootReducer(
      allObservedStart,
      RoutineRunFinished(
        generation = 2,
        result = RoutineRunResult.NO_CHANGES,
        counts = RoutineSafeCounts(sourcesObserved = 2),
        sourceStatuses = mapOf(
          RoutineExternalSource.GMAIL to RoutineSourceStatus.OBSERVED,
          RoutineExternalSource.GOOGLE_CALENDAR to RoutineSourceStatus.OBSERVED,
        ),
      ),
    )

    assertEquals(RoutineRunResult.NO_CHANGES, allObserved.routines.lastGoodRun?.result)
    assertNull(allObserved.routines.recovery)
    assertTrue(allObserved.routines.syntheticFinishMarker)
  }

  @Test
  fun familyReplacementClearsTenantStateButPreservesHostGateAndGenerationFence() {
    val configured = state(routine = RoutineState.preview().copy(
      provider = RoutineProvider.CLAUDE,
      selectedHub = RoutineSyntheticHub.FAMILY_BRIEFING,
      operationGeneration = 7,
      pendingOperation = RoutineOperation.RUN,
    ))

    val switched = rootReducer(configured, MembershipsLoaded(listOf(membership("other-family"))))

    assertEquals("other-family", switched.session.activeFamilyId)
    assertEquals(RoutineCapability.PREVIEW, switched.routines.capability)
    assertEquals(7, switched.routines.operationGeneration)
    assertEquals(RoutineProvider.UNSELECTED, switched.routines.provider)
    assertNull(switched.routines.selectedHub)
    assertNull(switched.routines.pendingOperation)
  }

  @Test
  fun routineBackClosesOverlaysThenWizardThenRoute() {
    val base = state(
      route = Route.SmartBriefings,
      routine = RoutineState.preview().copy(
        destination = RoutineDestination.PRIVACY,
        detailsOpen = true,
        revokeSheetOpen = true,
      ),
    )

    assertEquals(CloseRoutineDetails, backAction(base))
    assertEquals(
      CloseRoutineRevokeSheet,
      backAction(base.copy(routines = base.routines.copy(detailsOpen = false))),
    )
    assertEquals(
      RoutineNavigateBack,
      backAction(base.copy(routines = base.routines.copy(detailsOpen = false, revokeSheetOpen = false))),
    )
    assertEquals(
      CloseSmartBriefings,
      backAction(base.copy(routines = base.routines.copy(
        destination = RoutineDestination.ENTRY,
        detailsOpen = false,
        revokeSheetOpen = false,
      ))),
    )
  }
}
