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
import com.sloopworks.dayfold.client.cards.PlatformActions
import org.reduxkotlin.compose.SelectorStore
import org.reduxkotlin.compose.selectorState

// WI-447 (ADR 0063) — wires the stateless screens/sheets in this package to the store + command
// port. Not yet reachable from the app's route graph (ADR 0063 is still Proposed) — a later WI
// adds the nav entry once the ADR is accepted; this Host is what that entry will call.

private enum class CalendarSetupStep { OFF, PRIMER, CHOOSER, DENIED, NO_CALENDARS }

@Composable
fun CalendarSettingsHost(
  store: SelectorStore<AppState>,
  commands: DayfoldCommandPort,
  platformActions: PlatformActions,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val ui by store.selectorState(::calendarSettingsUiState)

  if (ui.featureEnabled) {
    CalendarSettingsOnHost(ui, store, commands, onBack, modifier)
    return
  }

  var step by remember(ui.permission, ui.availableCalendars) {
    mutableStateOf(
      when {
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
            commands.setSelectedCalendars(selected)
            commands.setCalendarEnabled(true)
            commands.startCalendarCheck()
          },
          modifier = modifier,
        )
      }
    }
    CalendarSetupStep.DENIED -> CalendarDeniedScreen(
      onBack = onBack,
      onOpenSettings = platformActions::openAppSettings,
      onKeepOff = onBack,
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

  CalendarSettingsOnScreen(
    includedCalendars = ui.includedCalendars(),
    lastCheckLabel = ui.lastCheckAt ?: "Not yet",
    onBack = onBack,
    onToggleOff = { turnOffOpen = true },
    onChangeCalendars = { changeCalendarsOpen = true },
    onEventTimeAlerts = {},
    onResetLocalMatches = { resetOpen = true },
    onTurnOff = { turnOffOpen = true },
    modifier = modifier,
  )

  if (changeCalendarsOpen) {
    val toRemove = ui.includedCalendars().firstOrNull()
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = { changeCalendarsOpen = false }, sheetState = sheetState) {
      ChangeCalendarsSheetContent(
        calendarName = toRemove?.displayName ?: "",
        calendarColor = colorFromHex(toRemove?.color),
        onKeepIt = { changeCalendarsOpen = false },
        onStopIncluding = {
          changeCalendarsOpen = false
          toRemove?.let { commands.setSelectedCalendars(ui.selectedCalendarIds - it.id) }
        },
      )
    }
  }
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
