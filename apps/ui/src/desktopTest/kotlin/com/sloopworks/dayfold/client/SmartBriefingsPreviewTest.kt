package com.sloopworks.dayfold.client

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.sloopworks.dayfold.client.theme.DayfoldTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.math.abs

@OptIn(ExperimentalTestApi::class)
class SmartBriefingsPreviewTest {
  @Test fun previewTruthAndTouchTargetStayVisibleOnEntry() = runComposeUiTest {
    var action: SmartBriefingsPreviewAction? = null
    setContent {
      DayfoldTheme {
        SmartBriefingsPreviewScreen(SmartBriefingsPreviewFixtures.ownerEntry, onAction = { action = it })
      }
    }

    onNodeWithTag("smart-briefings-preview-banner").assertIsDisplayed()
    onNodeWithText("Nothing connects or saves.").assertIsDisplayed()
    onNodeWithTag("smart-briefings-primary-action").assertHeightIsAtLeast(48.dp).performClick()
    assertEquals(SmartBriefingsPreviewAction.Continue, action)
  }

  @Test fun adultEntryIsReadOnlyAndHasNoSetupAction() = runComposeUiTest {
    setContent {
      DayfoldTheme { SmartBriefingsPreviewScreen(SmartBriefingsPreviewFixtures.adultEntry) }
    }

    onNodeWithText("Owner controls only").assertIsDisplayed()
    onNodeWithTag("smart-briefings-primary-action").assertDoesNotExist()
    onNodeWithText("Explore setup").assertDoesNotExist()
  }

  @Test fun providerAndSourceChoicesExposeNativeSelectionSemantics() = runComposeUiTest {
    var action: SmartBriefingsPreviewAction? = null
    setContent {
      DayfoldTheme {
        SmartBriefingsPreviewScreen(
          SmartBriefingsPreviewFixtures.configure(SmartBriefingsPreviewStep.Provider),
          onAction = { action = it },
        )
      }
    }

    onNodeWithTag("provider-claude").assertIsSelected()
    onNodeWithTag("provider-codex").assertHeightIsAtLeast(48.dp).performClick()
    assertEquals(
      SmartBriefingsPreviewAction.SelectProvider(SmartBriefingsPreviewProvider.Codex),
      action,
    )
  }

  @Test fun sourceChoiceIsARealCheckboxAndEmitsOnlyItsSyntheticKey() = runComposeUiTest {
    var action: SmartBriefingsPreviewAction? = null
    setContent {
      DayfoldTheme {
        SmartBriefingsPreviewScreen(
          SmartBriefingsPreviewFixtures.configure(SmartBriefingsPreviewStep.Sources),
          onAction = { action = it },
        )
      }
    }

    onNodeWithTag("source-gmail").assertIsOn().assertHeightIsAtLeast(48.dp).performClick()
    assertEquals(SmartBriefingsPreviewAction.ToggleSource("gmail"), action)
  }

  @Test fun handoffStaysInAppAndNeverClaimsAProviderWasOpened() = runComposeUiTest {
    var action: SmartBriefingsPreviewAction? = null
    setContent {
      DayfoldTheme {
        SmartBriefingsPreviewScreen(SmartBriefingsPreviewFixtures.handoff, onAction = { action = it })
      }
    }

    onNodeWithText("No provider page opens").assertIsDisplayed()
    onNodeWithText("Open Claude").assertDoesNotExist()
    onNodeWithTag("smart-briefings-primary-action").performClick()
    assertEquals(SmartBriefingsPreviewAction.ContinuePreview, action)
  }

  @Test fun offlineStateKeepsContentReadableAndDisablesMutations() = runComposeUiTest {
    setContent { DayfoldTheme { SmartBriefingsPreviewScreen(SmartBriefingsPreviewFixtures.offline) } }

    onNodeWithText("Offline").assertIsDisplayed()
    onNodeWithText("Weekdays · 6:30 AM (example)").assertIsDisplayed()
    onNodeWithTag("smart-briefings-primary-action").assertIsNotEnabled()
    onNodeWithTag("smart-briefings-secondary-action").assertIsNotEnabled()
  }

  @Test fun revokedResultNeverRendersOrReactivatesAsActive() = runComposeUiTest {
    val revoked = ownerState(
      RoutineState.preview().copy(
        destination = RoutineDestination.ACTIVE,
        lifecycle = RoutineLifecycle.REVOKED,
        syntheticFinishMarker = true,
      ),
    )
    val route = smartBriefingsRouteViewState(revoked)
    assertEquals(SmartBriefingsPreviewStatus.Revoked, route.preview.status)
    assertTrue(smartBriefingsPreviewActions(route, SmartBriefingsPreviewAction.ReviewDraft).isEmpty())

    setContent { DayfoldTheme { SmartBriefingsPreviewScreen(route.preview) } }
    onNodeWithText("Example access is off").assertIsDisplayed()
    onNodeWithText("Example briefing is active").assertDoesNotExist()
    onNodeWithTag("smart-briefings-primary-action").assertDoesNotExist()
  }

  @Test fun revokedProviderTaskRemainsIsHonestAndNonRetryable() = runComposeUiTest {
    val revoked = ownerState(
      RoutineState.preview().copy(
        destination = RoutineDestination.ACTIVE,
        lifecycle = RoutineLifecycle.REVOKED,
        revokeSheetOpen = true,
        recovery = routineRecoveryFor(RoutineRecoveryReason.PROVIDER_TASK_REMAINS),
      ),
    )
    val route = smartBriefingsRouteViewState(revoked)
    assertEquals(SmartBriefingsPreviewRevokeState.Revoked, route.preview.revokeState)
    assertEquals(null, route.preview.recovery?.action)
    assertTrue(smartBriefingsPreviewActions(route, SmartBriefingsPreviewAction.Retry).isEmpty())

    setContent { DayfoldTheme { SmartBriefingsPreviewScreen(route.preview) } }
    onNodeWithTag("smart-briefings-status").assertIsDisplayed()
    onNodeWithTag("smart-briefings-revoke-dialog").assertIsDisplayed()
    onAllNodesWithText("Dayfold access is off. The provider task may remain and should be removed in the provider; this preview cannot verify or retry that cleanup.")
      .assertCountEquals(2)
    onNodeWithText("Retry preview").assertDoesNotExist()
  }

  @Test fun revokeCancelClosesTheStateOwnedSheet() = runComposeUiTest {
    var app = ownerState(
      RoutineState.preview().copy(
        destination = RoutineDestination.ACTIVE,
        lifecycle = RoutineLifecycle.ACTIVE,
        revokeSheetOpen = true,
      ),
    )
    val route = smartBriefingsRouteViewState(app)
    setContent {
      DayfoldTheme {
        SmartBriefingsPreviewScreen(
          route.preview,
          onAction = { action ->
            smartBriefingsPreviewActions(route, action).forEach { app = rootReducer(app, it) }
          },
        )
      }
    }

    onNodeWithTag("smart-briefings-revoke-dialog").assertIsDisplayed()
    onNodeWithText("Example briefing is active").assertExists()
    onNodeWithText("Keep briefing").performClick()
    assertTrue(!app.routines.revokeSheetOpen)
    assertEquals(RoutineLifecycle.ACTIVE, app.routines.lifecycle)
  }

  @Test fun revokeDialogTraversesPendingFailureRetryAndExplicitSuccess() = runComposeUiTest {
    var lastAction: SmartBriefingsPreviewAction? = null
    var app by mutableStateOf(
      ownerState(
        RoutineState.preview().copy(
          destination = RoutineDestination.ACTIVE,
          lifecycle = RoutineLifecycle.ACTIVE,
          revokeSheetOpen = true,
        ),
      ),
    )
    setContent {
      DayfoldTheme {
        val route = smartBriefingsRouteViewState(app)
        SmartBriefingsPreviewScreen(
          route.preview,
          onAction = { action ->
            lastAction = action
            smartBriefingsPreviewActions(route, action).forEach { app = rootReducer(app, it) }
          },
        )
      }
    }

    onNodeWithTag("smart-briefings-revoke-confirm").performClick()
    assertEquals(RoutineLifecycle.REVOKING, app.routines.lifecycle)
    assertEquals(RoutineOperation.REVOKE, app.routines.pendingOperation)
    onNodeWithTag("smart-briefings-status").assertIsDisplayed()
    onNodeWithTag("smart-briefings-revoke-dialog").assertIsDisplayed()

    onNodeWithTag("smart-briefings-revoke-close").performClick()
    assertTrue(!app.routines.revokeSheetOpen)
    assertEquals(RoutineLifecycle.REVOKING, app.routines.lifecycle)
    mainClock.advanceTimeBy(500)
    waitForIdle()
    onNodeWithTag("smart-briefings-status").assertIsDisplayed()
    onNodeWithTag("smart-briefings-reopen-revoke").performScrollTo().assertIsDisplayed().performClick()
    waitForIdle()
    assertEquals(SmartBriefingsPreviewAction.PreviewRevocation, lastAction)
    assertTrue(app.routines.revokeSheetOpen)

    onNodeWithTag("smart-briefings-revoke-fail").performClick()
    assertEquals(RoutineLifecycle.ACTIVE, app.routines.lifecycle)
    assertEquals(RoutineRecoveryReason.REVOKE_FAILED, app.routines.recovery?.reason)
    onNodeWithTag("smart-briefings-revoke-retry").performClick()
    assertEquals(RoutineLifecycle.REVOKING, app.routines.lifecycle)
    assertEquals(RoutineOperation.REVOKE, app.routines.pendingOperation)
    onNodeWithTag("smart-briefings-revoke-complete").performClick()
    assertEquals(RoutineLifecycle.REVOKED, app.routines.lifecycle)
    assertEquals(null, app.routines.pendingOperation)
    onNodeWithTag("smart-briefings-revoke-done").performClick()
    assertTrue(!app.routines.revokeSheetOpen)
    onNodeWithText("Example access is off").assertIsDisplayed()
    onNodeWithText("Example briefing is active").assertDoesNotExist()
  }

  @Test fun recoveryActionsFollowTheirExplicitDestination() = runComposeUiTest {
    var app by mutableStateOf(
      ownerState(
        RoutineState.preview().copy(
          destination = RoutineDestination.DRAFT,
          lifecycle = RoutineLifecycle.ACTIVE,
          draft = RoutineDraftFixture.STALE_OR_CONFLICTED,
          recovery = routineRecoveryFor(RoutineRecoveryReason.CONFLICT),
        ),
      ),
    )
    setContent {
      DayfoldTheme {
        val route = smartBriefingsRouteViewState(app)
        SmartBriefingsPreviewScreen(
          route.preview,
          onAction = { action ->
            smartBriefingsPreviewActions(route, action).forEach { app = rootReducer(app, it) }
          },
        )
      }
    }

    onNodeWithText("Review newest example").performClick()
    assertEquals(RoutineDraftFixture.FIRST_DRAFT, app.routines.draft)
    assertEquals(null, app.routines.recovery)

    app = app.copy(routines = app.routines.copy(
      destination = RoutineDestination.ACTIVE,
      recovery = routineRecoveryFor(RoutineRecoveryReason.PARTIAL_SOURCE_SET),
    ))
    onNodeWithText("Review example sources").performClick()
    assertEquals(RoutineDestination.SOURCES, app.routines.destination)
  }

  @Test fun offlineConflictDisablesRecoveryAndBothDraftDecisions() = runComposeUiTest {
    setContent {
      DayfoldTheme {
        SmartBriefingsPreviewScreen(SmartBriefingsPreviewFixtures.conflict.copy(mutationsEnabled = false))
      }
    }

    onNodeWithTag("smart-briefings-recovery-action").assertIsNotEnabled()
    onNodeWithTag("smart-briefings-primary-action").assertIsNotEnabled()
    onNodeWithTag("smart-briefings-secondary-action").assertIsNotEnabled()
  }

  @Test fun onlineConflictAllowsRefreshButNotStaleDraftDecisions() = runComposeUiTest {
    setContent {
      DayfoldTheme { SmartBriefingsPreviewScreen(SmartBriefingsPreviewFixtures.conflict) }
    }

    onNodeWithTag("smart-briefings-recovery-action").assertIsEnabled()
    onNodeWithTag("smart-briefings-primary-action").assertIsNotEnabled()
    onNodeWithTag("smart-briefings-secondary-action").assertIsNotEnabled()
  }

  @Test fun statusUsesSelectedScheduleAndCurrentPerSourceTruth() = runComposeUiTest {
    val route = smartBriefingsRouteViewState(
      ownerState(
        RoutineState.preview().copy(
          destination = RoutineDestination.ACTIVE,
          lifecycle = RoutineLifecycle.ACTIVE,
          schedule = RoutineSchedulePreset.DAILY_EVENING,
          externalSources = setOf(RoutineExternalSource.GMAIL, RoutineExternalSource.GOOGLE_DRIVE),
          sourceStatuses = mapOf(
            RoutineExternalSource.GMAIL to RoutineSourceStatus.OBSERVED,
            RoutineExternalSource.GOOGLE_DRIVE to RoutineSourceStatus.REAUTH_REQUIRED,
          ),
          recovery = routineRecoveryFor(RoutineRecoveryReason.PARTIAL_SOURCE_SET),
          syntheticFinishMarker = true,
        ),
      ),
    )
    setContent { DayfoldTheme { SmartBriefingsPreviewScreen(route.preview) } }

    onNodeWithText("Daily · 6:00 PM (example)").assertIsDisplayed()
    onNodeWithText("Weekdays · 6:30 AM (example)").assertDoesNotExist()
    onNodeWithText("Observed in simulated result").performScrollTo().assertIsDisplayed()
    onNodeWithText("Example source needs authorization").performScrollTo().assertIsDisplayed()
  }

  @Test fun statusRecoveryAndSourceChangesUsePoliteLiveSemantics() = runComposeUiTest {
    setContent { DayfoldTheme { SmartBriefingsPreviewScreen(SmartBriefingsPreviewFixtures.partial) } }

    onNodeWithTag("smart-briefings-status").assert(
      SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
    )
    onNodeWithTag("smart-briefings-recovery").assert(
      SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
    )
    onNodeWithTag("smart-briefings-source-status-gmail").performScrollTo().assert(
      SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
    )
  }

  @Test fun statusChangeResetsScrollToRevealRevokedResult() = runComposeUiTest {
    var state by mutableStateOf(SmartBriefingsPreviewFixtures.active)
    setContent { DayfoldTheme { SmartBriefingsPreviewScreen(state) } }

    onNodeWithTag("smart-briefings-primary-action").performScrollTo().assertIsDisplayed()
    state = SmartBriefingsPreviewFixtures.revoked
    onNodeWithTag("smart-briefings-status").assertIsDisplayed()
  }

  @Test fun technicalDetailsAreStateOwnedContentFreeAndDismissible() = runComposeUiTest {
    var app by mutableStateOf(ownerState(RoutineState.preview().copy(destination = RoutineDestination.PRIVACY)))
    setContent {
      DayfoldTheme {
        val route = smartBriefingsRouteViewState(app)
        SmartBriefingsPreviewScreen(
          route.preview,
          onAction = { action ->
            smartBriefingsPreviewActions(route, action).forEach { app = rootReducer(app, it) }
          },
        )
      }
    }

    onNodeWithTag("smart-briefings-details-action").performClick()
    assertTrue(app.routines.detailsOpen)
    onNodeWithTag("smart-briefings-details-dialog").assertIsDisplayed()
    onNodeWithText("Support code · Not available in preview").assertIsDisplayed()
    onNodeWithText("This local surface contains no provider URL, grant ID, key fingerprint, credential, or family content.").assertIsDisplayed()
    assertEquals(CloseRoutineDetails, routineBackAction(app))
    onNodeWithTag("smart-briefings-details-done").performClick()
    assertTrue(!app.routines.detailsOpen)
    onNodeWithTag("smart-briefings-details-dialog").assertDoesNotExist()
  }

  @Test fun wideLayoutKeepsPairedActionsOnOneRow() = runComposeUiTest {
    setContent {
      DayfoldTheme {
        Box(Modifier.width(700.dp).height(891.dp)) {
          SmartBriefingsPreviewScreen(SmartBriefingsPreviewFixtures.active)
        }
      }
    }

    val primaryTop = onNodeWithTag("smart-briefings-primary-action").fetchSemanticsNode().boundsInRoot.top
    val secondaryTop = onNodeWithTag("smart-briefings-secondary-action").fetchSemanticsNode().boundsInRoot.top
    assertTrue(abs(primaryTop - secondaryTop) < 1f)
  }

  @Test fun draftNamesItsSyntheticBoundaryAndNeverPublishes() = runComposeUiTest {
    var action: SmartBriefingsPreviewAction? = null
    setContent {
      DayfoldTheme {
        SmartBriefingsPreviewScreen(SmartBriefingsPreviewFixtures.draft, onAction = { action = it })
      }
    }

    onNodeWithText("Every item here is example content. Nothing is sent, saved, or published.").assertIsDisplayed()
    onNodeWithTag("smart-briefings-primary-action").performClick()
    assertEquals(SmartBriefingsPreviewAction.AcceptDraftPreview, action)
  }

  @Test fun accountConnectionIsCapabilityGatedAndFamilyScoped() = runComposeUiTest {
    val app = ownerState(RoutineState.preview())
    setContent {
      DayfoldTheme {
        AccountScreen(
          state = accountViewState(app),
          smartBriefingsPreviewAvailable = true,
        )
      }
    }

    onNodeWithTag("account-smart-briefings").assertHeightIsAtLeast(48.dp)
    onNodeWithText("CONNECTIONS").assertIsDisplayed()
    onNodeWithText("Interactive preview · nothing connects or saves").assertIsDisplayed()
  }

  @Test fun reduxAdapterUsesClosedFixtureActionsOnly() {
    val route = smartBriefingsRouteViewState(
      ownerState(
        RoutineState.preview().copy(
          destination = RoutineDestination.STATUS,
          lifecycle = RoutineLifecycle.VERIFYING,
          operationGeneration = 8,
        ),
      ),
    )

    val actions = smartBriefingsPreviewActions(route, SmartBriefingsPreviewAction.ReviewDraft)
    assertEquals(4, actions.size)
    val started = assertIs<RoutineOperationStarted>(actions[0])
    val finished = assertIs<RoutineRunFinished>(actions[1])
    val draftStarted = assertIs<RoutineOperationStarted>(actions[2])
    val presented = assertIs<RoutineDraftPresented>(actions[3])
    assertEquals(RoutineOperation.RUN, started.operation)
    assertEquals(9, started.generation)
    assertEquals(RoutineRunResult.SUCCESS, finished.result)
    assertEquals(9, finished.generation)
    assertEquals(RoutineOperation.RUN, draftStarted.operation)
    assertEquals(10, draftStarted.generation)
    assertEquals(10, presented.generation)
    assertEquals(RoutineDraftFixture.FIRST_DRAFT, presented.draft)
    assertTrue(actions.none { it is OpenSmartBriefings || it is RoutineProviderReturnCompleted })
  }

  @Test fun reviewDraftObservesEverySelectedSourceBeforeActivation() {
    var app = ownerState(
      RoutineState.preview().copy(
        destination = RoutineDestination.STATUS,
        lifecycle = RoutineLifecycle.VERIFYING,
        externalSources = setOf(RoutineExternalSource.GMAIL, RoutineExternalSource.GOOGLE_CALENDAR),
        sourceStatuses = mapOf(
          RoutineExternalSource.GMAIL to RoutineSourceStatus.REQUESTED,
          RoutineExternalSource.GOOGLE_CALENDAR to RoutineSourceStatus.REQUESTED,
        ),
      ),
    )
    val route = smartBriefingsRouteViewState(app)
    smartBriefingsPreviewActions(route, SmartBriefingsPreviewAction.ReviewDraft).forEach {
      app = rootReducer(app, it)
    }

    assertEquals(RoutineDestination.DRAFT, app.routines.destination)
    assertEquals(RoutineLifecycle.ACTIVE, app.routines.lifecycle)
    assertEquals(null, app.routines.recovery)
    assertTrue(app.routines.externalSources.all { app.routines.sourceStatuses[it] == RoutineSourceStatus.OBSERVED })
    assertEquals(RoutineDraftFixture.FIRST_DRAFT, app.routines.draft)
  }

  @Test fun projectionDerivesAdultAuthorityAndMapsUnknownRecoveryToLocalGuidance() {
    val state = ownerState(
      RoutineState.preview().copy(
        destination = RoutineDestination.STATUS,
        recovery = unknownRoutineRecovery(),
      ),
      role = "adult",
    )

    val route = smartBriefingsRouteViewState(state)
    val preview = route.preview
    assertEquals(SmartBriefingsPreviewAuthority.AdultReadOnly, preview.authority)
    assertEquals(SmartBriefingsPreviewStatus.Unknown, preview.status)
    assertEquals("Status is temporarily unavailable", preview.recovery?.title)
    assertEquals(SmartBriefingsPreviewRecoveryAction.ViewGuidance, preview.recovery?.action)
    assertTrue(preview.recovery?.message?.contains("code", ignoreCase = true) != true)
    val actions = smartBriefingsPreviewActions(
      route,
      SmartBriefingsPreviewAction.Recover(SmartBriefingsPreviewRecoveryAction.ViewGuidance),
    )
    assertEquals(listOf(OpenRoutineDetails), actions)
    assertTrue(actions.none { it is RoutineOperationStarted })
  }

  private fun ownerState(routine: RoutineState, role: String = "owner"): AppState = AppState(
    session = SessionState(
      session = Session("access", "refresh"),
      families = listOf(FamilyMembership("family", "Example family", role = role, status = "active")),
      activeFamilyId = "family",
    ),
    navigation = NavigationState(route = Route.SmartBriefings),
    routines = routine,
  )
}
