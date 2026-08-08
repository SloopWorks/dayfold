package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RoutineReducerTest {
  private fun membership(familyId: String = "synthetic-family", role: String = "owner") =
    FamilyMembership(familyId = familyId, name = "Synthetic family", role = role, status = "active")

  private fun state(
    role: String = "owner",
    routine: RoutineState = RoutineState.preview(),
    route: Route = Route.Account,
  ) = AppState(
    session = SessionState(
      session = Session("synthetic-access", "synthetic-refresh"),
      families = listOf(membership(role = role)),
      activeFamilyId = "synthetic-family",
    ),
    navigation = NavigationState(route = route),
    routines = routine,
  )

  @Test fun `hidden capability rejects direct and restored opens`() {
    val hidden = state(routine = RoutineState())
    assertEquals(hidden, rootReducer(hidden, OpenSmartBriefings))
    assertEquals(hidden, rootReducer(hidden, RestoreSmartBriefings))

    val preview = rootReducer(state(), OpenSmartBriefings)
    assertEquals(Route.SmartBriefings, preview.navigation.route)
  }

  @Test fun `progressive setup gates required choices and preserves prior selections`() {
    var current = rootReducer(state(), OpenSmartBriefings)
    current = rootReducer(current, RoutineContinue)
    assertEquals(RoutineDestination.PROVIDER, current.routines.destination)

    assertEquals(current, rootReducer(current, RoutineContinue))
    current = rootReducer(current, RoutineProviderSelected(RoutineProvider.CLAUDE))
    current = rootReducer(current, RoutineContinue)
    assertEquals(RoutineDestination.SOURCES, current.routines.destination)

    current = rootReducer(current, RoutineSourcesSelected(setOf(RoutineExternalSource.GMAIL)))
    current = rootReducer(current, RoutineContinue)
    assertEquals(RoutineDestination.HUB_ACCESS, current.routines.destination)
    assertEquals(setOf(RoutineExternalSource.GMAIL), current.routines.externalSources)

    // The restricted fixture is visible but cannot become the selected authoring target.
    assertEquals(current, rootReducer(current, RoutineHubSelected(RoutineSyntheticHub.RESTRICTED_EXAMPLE)))
    current = rootReducer(current, RoutineHubSelected(RoutineSyntheticHub.FAMILY_BRIEFING))
    current = rootReducer(current, RoutineContinue)
    assertEquals(RoutineDestination.SCHEDULE, current.routines.destination)

    assertEquals(current, rootReducer(current, RoutineContinue))
    current = rootReducer(current, RoutineScheduleSelected(RoutineSchedulePreset.WEEKDAY_MORNING))
    current = rootReducer(current, RoutineContinue)
    assertEquals(RoutineDestination.PRIVACY, current.routines.destination)
    assertEquals(current, rootReducer(current, RoutineContinue))
    current = rootReducer(current, RoutinePrivacyAcknowledged(true))
    current = rootReducer(current, RoutineContinue)
    assertEquals(RoutineDestination.HANDOFF, current.routines.destination)
    assertEquals(RoutineExecutionMode.SHADOW, current.routines.executionMode)
    assertEquals(RoutinePublicationMode.REVIEW, current.routines.publicationMode)
  }

  @Test fun `adult mode is read only and owner role loss cancels owner-only work`() {
    val adult = state(role = "adult")
    assertEquals(RoutineAuthority.ADULT_READ_ONLY, routineAuthority(adult))
    assertEquals(adult, rootReducer(adult, RoutineContinue))
    assertEquals(adult, rootReducer(adult, OpenRoutineRevokeSheet))

    val ownerBusy = state(
      routine = RoutineState.preview().copy(
        destination = RoutineDestination.HANDOFF,
        lifecycle = RoutineLifecycle.PREPARING,
        operationGeneration = 4,
        pendingOperation = RoutineOperation.PREPARATION,
        revokeSheetOpen = true,
      ),
    )
    val changed = rootReducer(ownerBusy, MembershipsLoaded(listOf(membership(role = "adult"))))
    assertEquals(RoutineAuthority.ADULT_READ_ONLY, routineAuthority(changed))
    assertEquals(RoutineDestination.ENTRY, changed.routines.destination)
    assertEquals(RoutineLifecycle.OFF, changed.routines.lifecycle)
    assertNull(changed.routines.pendingOperation)
    assertFalse(changed.routines.revokeSheetOpen)
  }

  @Test fun `family switch and session terminal reset tenant preview but preserve host capability`() {
    val configured = state(
      routine = RoutineState.preview().copy(
        provider = RoutineProvider.CHATGPT,
        selectedHub = RoutineSyntheticHub.FAMILY_BRIEFING,
        lifecycle = RoutineLifecycle.ACTIVE,
        syntheticFinishMarker = true,
      ),
    )
    val switched = rootReducer(configured, MembershipsLoaded(listOf(membership("synthetic-family-2"))))
    assertEquals("synthetic-family-2", switched.session.activeFamilyId)
    assertEquals(RoutineState.preview(), switched.routines)

    listOf(rootReducer(configured, SignedOut), rootReducer(configured, SessionExpired)).forEach {
      assertEquals(RoutineCapability.PREVIEW, it.routines.capability)
      assertEquals(RoutineLifecycle.OFF, it.routines.lifecycle)
      assertEquals(RoutineProvider.UNSELECTED, it.routines.provider)
      assertEquals(Route.SignIn, it.navigation.route)
    }
  }

  @Test fun `late operation results cannot cross generations`() {
    val handoff = state(routine = RoutineState.preview().copy(destination = RoutineDestination.HANDOFF))
    val started = rootReducer(handoff, RoutineOperationStarted(RoutineOperation.PREPARATION, generation = 2))
    assertEquals(RoutineLifecycle.PREPARING, started.routines.lifecycle)

    assertEquals(started, rootReducer(started, RoutinePreparationCompleted(generation = 1)))
    val completed = rootReducer(started, RoutinePreparationCompleted(generation = 2))
    assertEquals(RoutineLifecycle.AWAITING_PROVIDER, completed.routines.lifecycle)
    assertNull(completed.routines.pendingOperation)

    // A retry must advance the generation so a late terminal from the failed attempt cannot win.
    val retry = rootReducer(
      rootReducer(handoff, RoutineOperationStarted(RoutineOperation.PREPARATION, generation = 3)),
      RoutinePreparationFailed(generation = 3),
    )
    assertEquals(3, retry.routines.operationGeneration)
    assertEquals(RoutineRecoveryReason.PREPARATION_FAILED, retry.routines.recovery?.reason)
    assertNull(
      rootReducer(retry, RoutineOperationStarted(RoutineOperation.PREPARATION, generation = 3)).routines.pendingOperation,
    )
    val restarted = rootReducer(retry, RoutineOperationStarted(RoutineOperation.PREPARATION, generation = 4))
    assertEquals(RoutineOperation.PREPARATION, restarted.routines.pendingOperation)
    assertEquals(
      restarted,
      rootReducer(restarted, RoutinePreparationCompleted(generation = 3)),
      "a late terminal from the failed attempt must not complete the retry",
    )
  }

  @Test fun `operation generation fence survives a family switch`() {
    val oldFamily = state(routine = RoutineState.preview().copy(
      destination = RoutineDestination.HANDOFF,
      operationGeneration = 4,
      pendingOperation = RoutineOperation.PREPARATION,
    ))
    val switched = rootReducer(oldFamily, MembershipsLoaded(listOf(membership("synthetic-family-2"))))
    assertEquals(4, switched.routines.operationGeneration)
    assertNull(switched.routines.pendingOperation)

    val newFamilyHandoff = switched.copy(routines = switched.routines.copy(destination = RoutineDestination.HANDOFF))
    val newOperation = rootReducer(newFamilyHandoff, RoutineOperationStarted(RoutineOperation.PREPARATION, 5))
    assertEquals(
      newOperation,
      rootReducer(newOperation, RoutinePreparationCompleted(4)),
      "late family-A generation must not complete family-B work",
    )
    assertEquals(
      RoutineLifecycle.AWAITING_PROVIDER,
      rootReducer(newOperation, RoutinePreparationCompleted(5)).routines.lifecycle,
    )
  }

  @Test fun `failed run preserves last good summary and exposes only structured recovery`() {
    val status = state(routine = RoutineState.preview().copy(
      destination = RoutineDestination.STATUS,
      lifecycle = RoutineLifecycle.VERIFYING,
      syntheticGrantMarker = true,
      externalSources = setOf(RoutineExternalSource.GMAIL),
    ))
    val first = rootReducer(
      rootReducer(status, RoutineOperationStarted(RoutineOperation.RUN, 1)),
      RoutineRunFinished(
        generation = 1,
        result = RoutineRunResult.NO_CHANGES,
        counts = RoutineSafeCounts(candidates = 0, sourcesObserved = 1),
        sourceStatuses = mapOf(RoutineExternalSource.GMAIL to RoutineSourceStatus.OBSERVED),
      ),
    )
    assertEquals(RoutineRunResult.NO_CHANGES, first.routines.lastGoodRun?.result)
    assertTrue(first.routines.syntheticFinishMarker)

    val failed = rootReducer(
      rootReducer(first, RoutineOperationStarted(RoutineOperation.RUN, 2)),
      RoutineRunFinished(generation = 2, result = RoutineRunResult.FAILED),
    )
    assertEquals(RoutineRunResult.FAILED, failed.routines.lastRun?.result)
    assertSame(first.routines.lastGoodRun, failed.routines.lastGoodRun)
    assertEquals(RoutineRecoveryReason.RUN_REPORTED_FAILED, failed.routines.recovery?.reason)
    assertEquals(RoutineProviderHealth.REPORTED_FAILED, failed.routines.providerHealth)
  }

  @Test fun `unexpected source results fail closed without replacing last good`() {
    val previous = RoutineRunSummary(RoutineRunResult.NO_CHANGES)
    val status = state(routine = RoutineState.preview().copy(
      destination = RoutineDestination.STATUS,
      lifecycle = RoutineLifecycle.VERIFYING,
      externalSources = setOf(RoutineExternalSource.GMAIL),
      lastGoodRun = previous,
    ))
    val started = rootReducer(status, RoutineOperationStarted(RoutineOperation.RUN, 1))
    val finished = rootReducer(started, RoutineRunFinished(
      generation = 1,
      result = RoutineRunResult.SUCCESS,
      sourceStatuses = mapOf(RoutineExternalSource.GOOGLE_DRIVE to RoutineSourceStatus.OBSERVED),
    ))
    assertEquals(RoutineRecoveryReason.UNEXPECTED_SOURCE_SET, finished.routines.recovery?.reason)
    assertEquals(
      mapOf(RoutineExternalSource.GMAIL to RoutineSourceStatus.REQUESTED),
      finished.routines.sourceStatuses,
    )
    assertSame(previous, finished.routines.lastGoodRun)
  }

  @Test fun `successful terminal requires every requested source to be currently observed`() {
    val previous = RoutineRunSummary(
      result = RoutineRunResult.NO_CHANGES,
      sourceStatuses = mapOf(RoutineExternalSource.GOOGLE_CALENDAR to RoutineSourceStatus.OBSERVED),
    )
    val status = state(routine = RoutineState.preview().copy(
      destination = RoutineDestination.STATUS,
      lifecycle = RoutineLifecycle.VERIFYING,
      externalSources = setOf(RoutineExternalSource.GMAIL, RoutineExternalSource.GOOGLE_CALENDAR),
      sourceStatuses = mapOf(
        RoutineExternalSource.GMAIL to RoutineSourceStatus.OBSERVED,
        RoutineExternalSource.GOOGLE_CALENDAR to RoutineSourceStatus.OBSERVED,
      ),
      lastGoodRun = previous,
    ))
    val missingCurrentEvidence = rootReducer(
      rootReducer(status, RoutineOperationStarted(RoutineOperation.RUN, 1)),
      RoutineRunFinished(
        generation = 1,
        result = RoutineRunResult.SUCCESS,
        sourceStatuses = mapOf(RoutineExternalSource.GMAIL to RoutineSourceStatus.OBSERVED),
      ),
    )

    assertEquals(RoutineRunResult.PARTIAL, missingCurrentEvidence.routines.lastRun?.result)
    assertEquals(RoutineRecoveryReason.PARTIAL_SOURCE_SET, missingCurrentEvidence.routines.recovery?.reason)
    assertSame(previous, missingCurrentEvidence.routines.lastGoodRun)
    assertEquals(
      RoutineSourceStatus.REQUESTED,
      missingCurrentEvidence.routines.sourceStatuses[RoutineExternalSource.GOOGLE_CALENDAR],
      "current status must not inherit a prior observed result",
    )

    val explicitFailure = rootReducer(
      rootReducer(status, RoutineOperationStarted(RoutineOperation.RUN, 2)),
      RoutineRunFinished(
        generation = 2,
        result = RoutineRunResult.NO_CHANGES,
        sourceStatuses = mapOf(
          RoutineExternalSource.GMAIL to RoutineSourceStatus.OBSERVED,
          RoutineExternalSource.GOOGLE_CALENDAR to RoutineSourceStatus.REAUTH_REQUIRED,
        ),
      ),
    )
    assertEquals(RoutineRunResult.PARTIAL, explicitFailure.routines.lastRun?.result)
    assertSame(previous, explicitFailure.routines.lastGoodRun)
    assertEquals(
      RoutineSourceStatus.REAUTH_REQUIRED,
      explicitFailure.routines.sourceStatuses[RoutineExternalSource.GOOGLE_CALENDAR],
    )
  }

  @Test fun `dayfold only terminal may become last good without external source evidence`() {
    val status = state(routine = RoutineState.preview().copy(
      destination = RoutineDestination.STATUS,
      lifecycle = RoutineLifecycle.VERIFYING,
    ))
    val finished = rootReducer(
      rootReducer(status, RoutineOperationStarted(RoutineOperation.RUN, 1)),
      RoutineRunFinished(generation = 1, result = RoutineRunResult.NO_CHANGES),
    )

    assertEquals(RoutineRunResult.NO_CHANGES, finished.routines.lastGoodRun?.result)
    assertNull(finished.routines.recovery)
  }

  @Test fun `conflicted draft preserves prior last good evidence`() {
    val previous = RoutineRunSummary(RoutineRunResult.NO_CHANGES)
    val status = state(routine = RoutineState.preview().copy(
      destination = RoutineDestination.STATUS,
      lifecycle = RoutineLifecycle.ACTIVE,
      lastGoodRun = previous,
    ))
    val conflicted = rootReducer(
      rootReducer(status, RoutineOperationStarted(RoutineOperation.RUN, 1)),
      RoutineDraftPresented(1, RoutineDraftFixture.STALE_OR_CONFLICTED),
    )

    assertSame(previous, conflicted.routines.lastGoodRun)
    assertEquals(RoutineRecoveryReason.CONFLICT, conflicted.routines.recovery?.reason)
    assertEquals(
      conflicted,
      rootReducer(conflicted, RoutineDraftDecisionPreviewed(RoutineDraftDecision.ACCEPTED_PREVIEW)),
    )
  }

  @Test fun `draft activation fails closed until every requested source is observed`() {
    val status = state(routine = RoutineState.preview().copy(
      destination = RoutineDestination.STATUS,
      lifecycle = RoutineLifecycle.VERIFYING,
      externalSources = setOf(RoutineExternalSource.GMAIL),
      sourceStatuses = mapOf(RoutineExternalSource.GMAIL to RoutineSourceStatus.REQUESTED),
    ))
    val started = rootReducer(status, RoutineOperationStarted(RoutineOperation.RUN, 1))
    val rejected = rootReducer(started, RoutineDraftPresented(1, RoutineDraftFixture.FIRST_DRAFT))

    assertNull(rejected.routines.draft)
    assertEquals(RoutineDestination.ACTIVE, rejected.routines.destination)
    assertEquals(RoutineRecoveryReason.PARTIAL_SOURCE_SET, rejected.routines.recovery?.reason)
    assertFalse(rejected.routines.syntheticFinishMarker)
  }

  @Test fun `offline disables setup and revoke mutations but keeps safe local state readable`() {
    val active = state(routine = RoutineState.preview().copy(
      destination = RoutineDestination.ACTIVE,
      lifecycle = RoutineLifecycle.ACTIVE,
      syntheticFinishMarker = true,
      lastGoodRun = RoutineRunSummary(RoutineRunResult.NO_CHANGES),
    ))
    val offline = rootReducer(active, RoutineOfflineChanged(true))
    assertFalse(routineCanMutate(offline))
    assertEquals(offline, rootReducer(offline, OpenRoutineRevokeSheet))
    assertEquals(offline, rootReducer(offline, RoutineProviderSelected(RoutineProvider.CLAUDE)))
    assertEquals(RoutineRunResult.NO_CHANGES, offline.routines.lastGoodRun?.result)
  }

  @Test fun `revoke outcomes clear only the synthetic grant marker after a matching operation`() {
    val active = state(routine = RoutineState.preview().copy(
      destination = RoutineDestination.ACTIVE,
      lifecycle = RoutineLifecycle.ACTIVE,
      syntheticGrantMarker = true,
      syntheticFinishMarker = true,
    ))
    val started = rootReducer(active, RoutineOperationStarted(RoutineOperation.REVOKE, 1))
    assertEquals(RoutineLifecycle.REVOKING, started.routines.lifecycle)
    assertEquals(started, rootReducer(started, RoutineRevokeFinished(2, RoutineRevokeOutcome.REVOKED)))

    val finished = rootReducer(started, RoutineRevokeFinished(1, RoutineRevokeOutcome.REVOKED_PROVIDER_TASK_REMAINS))
    assertEquals(RoutineLifecycle.REVOKED, finished.routines.lifecycle)
    assertFalse(finished.routines.syntheticGrantMarker)
    assertEquals(RoutineRecoveryReason.PROVIDER_TASK_REMAINS, finished.routines.recovery?.reason)
  }

  @Test fun `pending revoke is read only and can be reopened for inspection while offline`() {
    val active = state(routine = RoutineState.preview().copy(
      destination = RoutineDestination.ACTIVE,
      lifecycle = RoutineLifecycle.ACTIVE,
      revokeSheetOpen = true,
    ))
    val pending = rootReducer(active, RoutineOperationStarted(RoutineOperation.REVOKE, 1))
    assertEquals(RoutineLifecycle.REVOKING, pending.routines.lifecycle)
    assertFalse(routineCanMutate(pending))

    val closedOffline = rootReducer(
      rootReducer(pending, CloseRoutineRevokeSheet),
      RoutineOfflineChanged(true),
    )
    assertFalse(closedOffline.routines.revokeSheetOpen)
    assertEquals(closedOffline, rootReducer(closedOffline, RoutineProviderSelected(RoutineProvider.CLAUDE)))

    val reopened = rootReducer(closedOffline, OpenRoutineRevokeSheet)
    assertTrue(reopened.routines.revokeSheetOpen)
    assertEquals(RoutineLifecycle.REVOKING, reopened.routines.lifecycle)
    assertEquals(RoutineOperation.REVOKE, reopened.routines.pendingOperation)
  }

  @Test fun `revoked routine cannot start a run or reactivate`() {
    val revoked = state(routine = RoutineState.preview().copy(
      destination = RoutineDestination.ACTIVE,
      lifecycle = RoutineLifecycle.REVOKED,
      syntheticFinishMarker = true,
    ))

    assertFalse(routineCanMutate(revoked))
    assertEquals(revoked, rootReducer(revoked, RoutineOperationStarted(RoutineOperation.RUN, 2)))
    assertEquals(revoked, rootReducer(revoked, RoutineDraftPresented(2, RoutineDraftFixture.FIRST_DRAFT)))
  }
}
