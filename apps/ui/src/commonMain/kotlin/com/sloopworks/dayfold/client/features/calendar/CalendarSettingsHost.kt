package com.sloopworks.dayfold.client.features.calendar

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.sloopworks.dayfold.client.AppState
import com.sloopworks.dayfold.client.CalendarNotificationOwner
import com.sloopworks.dayfold.client.CalendarPermission
import com.sloopworks.dayfold.client.DayfoldCommandPort
import org.reduxkotlin.compose.SelectorStore
import org.reduxkotlin.compose.selectorState

// WI-447 (ADR 0063) — production Calendar Check settings/setup host.

private enum class CalendarSetupStep { OFF, PRIMER, CHOOSER, DENIED, NO_CALENDARS }

@Composable
fun CalendarSettingsHost(
  store: SelectorStore<AppState>,
  commands: DayfoldCommandPort,
  onOpenAppSettings: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val ui by store.selectorState(::calendarSettingsUiState)

  // Re-read OS truth and the ephemeral device calendar list every time this route is entered.
  // Persisted settings arrive independently through ContentBridge's device projection.
  LaunchedEffect(Unit) { commands.startCalendarCheck() }
  LaunchedEffect(ui.permission) {
    if (ui.permission == CalendarPermission.Granted) commands.loadAvailableCalendars()
  }

  if (ui.featureEnabled && ui.permission == CalendarPermission.Granted && ui.selectedCalendarIds.isNotEmpty()) {
    CalendarSettingsOnHost(ui, store, commands, onBack, modifier)
    return
  }

  var step by remember(ui.featureEnabled, ui.permission, ui.availableCalendars) {
    mutableStateOf(
      when {
        ui.featureEnabled && ui.permission != CalendarPermission.Granted -> CalendarSetupStep.DENIED
        ui.permission == CalendarPermission.Denied || ui.permission == CalendarPermission.Restricted -> CalendarSetupStep.DENIED
        ui.permission == CalendarPermission.Granted && ui.availableCalendars.isEmpty() -> CalendarSetupStep.NO_CALENDARS
        else -> CalendarSetupStep.OFF
      },
    )
  }

  when (step) {
    CalendarSetupStep.OFF -> CalendarSettingsOffScreen(
      onBack = onBack,
      onSetUp = { step = CalendarSetupStep.PRIMER },
      modifier = modifier,
    )
    CalendarSetupStep.PRIMER -> {
      // The OS permission prompt is asynchronous (the user must tap Allow/Deny) — requestCalendarPermission()
      // only fires it; it can't tell us the answer synchronously the way onContinue used to assume. Once
      // the real determination lands in ui.permission (a fresh CalendarCheckCompleted from the port's
      // launcher callback re-reading OS truth), react to it here instead of guessing at tap time.
      LaunchedEffect(ui.permission) {
        when (ui.permission) {
          CalendarPermission.Granted -> { commands.loadAvailableCalendars(); step = CalendarSetupStep.CHOOSER }
          CalendarPermission.Denied, CalendarPermission.Restricted -> step = CalendarSetupStep.DENIED
          CalendarPermission.Unavailable -> step = CalendarSetupStep.NO_CALENDARS
          CalendarPermission.NotRequested -> Unit // no answer yet — stay on the primer
        }
      }
      CalendarPrimerScreen(
        onNotNow = { step = CalendarSetupStep.OFF },
        onContinue = { commands.requestCalendarPermission() },
        modifier = modifier,
      )
    }
    CalendarSetupStep.CHOOSER -> {
      var selected by remember { mutableStateOf(ui.selectedCalendarIds) }
      val groups = remember(ui.availableCalendars) { groupCalendarsByAccount(ui.availableCalendars) }
      if (ui.availableCalendars.isEmpty()) {
        CalendarNoCalendarsScreen(onBack = { step = CalendarSetupStep.OFF }, onDoneForNow = onBack, modifier = modifier)
      } else {
        CalendarChooserScreen(
          groups = groups, selectedIds = selected,
          onBack = { step = CalendarSetupStep.OFF },
          onToggle = { id -> selected = if (id in selected) selected - id else selected + id },
          onIncludeSelected = {
            commands.enableCalendarCheck(selected)
          },
          modifier = modifier,
        )
      }
    }
    CalendarSetupStep.DENIED -> CalendarDeniedScreen(
      onBack = onBack,
      onOpenSettings = onOpenAppSettings,
      onKeepOff = { commands.setCalendarEnabled(false); onBack() },
      modifier = modifier,
    )
    CalendarSetupStep.NO_CALENDARS -> CalendarNoCalendarsScreen(
      onBack = { step = CalendarSetupStep.OFF },
      onDoneForNow = onBack,
      modifier = modifier,
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarSettingsOnHost(
  ui: CalendarSettingsUiState,
  store: SelectorStore<AppState>,
  commands: DayfoldCommandPort,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var changeCalendarsOpen by remember { mutableStateOf(false) }
  var turnOffOpen by remember { mutableStateOf(false) }
  var resetOpen by remember { mutableStateOf(false) }

  if (changeCalendarsOpen) {
    var selected by remember(changeCalendarsOpen) { mutableStateOf(ui.selectedCalendarIds) }
    CalendarChooserScreen(
      groups = groupCalendarsByAccount(ui.availableCalendars),
      selectedIds = selected,
      onBack = { changeCalendarsOpen = false },
      onToggle = { id -> selected = if (id in selected) selected - id else selected + id },
      onIncludeSelected = {
        if (selected.isNotEmpty()) {
          commands.setSelectedCalendars(selected)
          changeCalendarsOpen = false
        }
      },
      modifier = modifier,
    )
    return
  }

  CalendarSettingsOnScreen(
    includedCalendars = ui.includedCalendars(),
    lastCheckLabel = ui.lastCheckAt ?: "Not yet",
    onBack = onBack,
    onToggleOff = { turnOffOpen = true },
    onChangeCalendars = { changeCalendarsOpen = true },
    onEventTimeAlerts = null,
    onResetLocalMatches = { resetOpen = true },
    onTurnOff = { turnOffOpen = true },
    modifier = modifier,
  )

  if (turnOffOpen) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = { turnOffOpen = false }, sheetState = sheetState) {
      TurnOffSheetContent(
        onCancel = { turnOffOpen = false },
        onTurnOff = { turnOffOpen = false; commands.setCalendarEnabled(false) },
      )
    }
  }
  if (resetOpen) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = { resetOpen = false }, sheetState = sheetState) {
      ResetLocalMatchesSheetContent(
        onCancel = { resetOpen = false },
        onReset = { resetOpen = false; commands.resetLocalCalendarMatches() },
      )
    }
  }
}

/** WI-447 §30 — the per-match alert-override sheet, opened from a matched Now/review item
 *  (that item's own Host owns [subjectKey] + the current [owner]; not reachable from Settings). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarAlertOverrideHost(subjectKey: String, owner: CalendarNotificationOwner, commands: DayfoldCommandPort, onDismiss: () -> Unit) {
  val sheetState = rememberModalBottomSheetState()
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    AlertOverrideSheetContent(
      owner = owner,
      onPick = { picked -> commands.setCalendarNotificationOwner(subjectKey, picked); onDismiss() },
    )
  }
}
