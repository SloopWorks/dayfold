package com.sloopworks.dayfold.client.features.calendar

import androidx.compose.runtime.Composable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.sloopworks.dayfold.client.AppState
import com.sloopworks.dayfold.client.CalendarGapKind
import com.sloopworks.dayfold.client.CalendarEditorOutcome
import com.sloopworks.dayfold.client.CalendarEventObservation
import com.sloopworks.dayfold.client.CalendarNotificationOwner
import com.sloopworks.dayfold.client.CalendarPermission
import com.sloopworks.dayfold.client.DayfoldEventCandidate
import com.sloopworks.dayfold.client.DayfoldCommandPort
import com.sloopworks.dayfold.client.DeepLinkTarget
import com.sloopworks.dayfold.client.DayfoldIcons
import com.sloopworks.dayfold.client.formatMetaWhen
import org.reduxkotlin.compose.SelectorStore
import org.reduxkotlin.compose.selectorState

// WI-446 (ADR 0063 §4/§5) — wires the stateless review screens in this package to the store +
// command port (mirrors CalendarSettingsHost, WI-447). The outer CalendarReview route is Redux;
// the bounded review drill-down remains local Compose state like other multi-step surfaces.

private sealed interface ReviewStep {
  data object List : ReviewStep
  data class Suggested(val subjectKey: String) : ReviewStep
  data class Ambiguous(val subjectKey: String) : ReviewStep
  data class Differ(val subjectKey: String) : ReviewStep
  data class Recurring(val subjectKey: String) : ReviewStep
  data class Matched(val candidate: DayfoldEventCandidate, val observation: CalendarEventObservation) : ReviewStep
  data class Return(val row: DayfoldOnlyRow, val checkBefore: String?) : ReviewStep
  data object Ignored : ReviewStep
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarReviewHost(
  store: SelectorStore<AppState>,
  commands: DayfoldCommandPort,
  onBack: () -> Unit,
  // "Open Hub" / "Add to a Hub" are cross-surface deep-links; the caller wires them the same way
  // CardAction.OpenHub is routed elsewhere (this package doesn't own familyId/hub navigation).
  onOpenHub: (DeepLinkTarget) -> Unit = {},
  onAddToHub: (CalendarOnlyRow) -> Unit = {},
  onOpenAppSettings: () -> Unit = {},
  onResumeImport: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val ui by store.selectorState(::calendarReviewListUiState)
  val lastCheckAt by store.selectorState { it.calendar.check.lastCheckAt }
  val importState by store.selectorState { it.calendar.importState }
  val compareLabel = formatMetaWhen(lastCheckAt)?.let { "Compared on this phone · $it" } ?: "Compared on this phone"

  var step by remember { mutableStateOf<ReviewStep>(ReviewStep.List) }
  // Ignoring drops the item from state.calendar.check.results on the same dispatch (see
  // CalendarSelectors.kt's ignoredKeysMostRecentFirst doc) — this local cache is the ONLY place
  // an ignored item's title/meta survives, for exactly the items ignored during this visit.
  val ignoredDisplay = remember { mutableStateMapOf<String, IgnoredRowDisplay>() }
  var pendingPrefill by remember { mutableStateOf<DayfoldOnlyRow?>(null) }
  var alertOverrideSubject by remember { mutableStateOf<String?>(null) }

  fun toList() { step = ReviewStep.List }

  when (val s = step) {
    ReviewStep.List -> CalendarReviewListScreen(
      ui = ui, compareLabel = compareLabel, onBack = onBack,
      onAddToCalendar = { row -> pendingPrefill = row },
      onIgnoreDayfoldOnly = { row ->
        ignoredDisplay[row.subjectKey] = IgnoredRowDisplay(row.subjectKey, DayfoldIcons.Event, row.title, "Not on calendar · ignored just now")
        commands.ignoreCalendarItem(row.subjectKey)
      },
      onOpenHub = { row -> row.target?.let(onOpenHub) },
      onOpenNeedsReview = { row ->
        step = when (row.gapKind) {
          CalendarGapKind.SUGGESTED -> ReviewStep.Suggested(row.subjectKey)
          CalendarGapKind.AMBIGUOUS -> ReviewStep.Ambiguous(row.subjectKey)
          CalendarGapKind.DIFFERS -> ReviewStep.Differ(row.subjectKey)
          CalendarGapKind.RECURRING -> ReviewStep.Recurring(row.subjectKey)
          else -> ReviewStep.List
        }
      },
      onKeepCalendarOnly = { row ->
        ignoredDisplay[row.itemKey] = IgnoredRowDisplay(row.itemKey, DayfoldIcons.CalendarMonth, row.title, "Calendar-only · ignored just now")
        commands.ignoreCalendarItem(row.itemKey)
      },
      onAddToHub = onAddToHub,
      onOpenIgnored = { step = ReviewStep.Ignored },
      onResumeImport = if (importState is com.sloopworks.dayfold.client.ImportProposalState.None) null else onResumeImport,
      modifier = modifier,
    )

    is ReviewStep.Suggested -> {
      val suggested by store.selectorState(key = s.subjectKey) { calendarSuggestedUiState(it, s.subjectKey) }
      val current = suggested
      if (current == null) { toList() } else {
        CalendarSuggestedMatchScreen(
          ui = current, onBack = ::toList,
          onKeepSeparate = { commands.keepCalendarSeparate(current.subjectKey); toList() },
          onConfirmMatch = {
            val match = store.state.calendar.check.results.suggested
              .firstOrNull { it.candidate.subjectKey == current.subjectKey && it.observation.platformEventId == current.eventId }
            if (match != null) step = ReviewStep.Matched(match.candidate, match.observation) else toList()
            commands.confirmCalendarMatch(current.subjectKey, current.eventId)
          },
          modifier = modifier,
        )
      }
    }

    is ReviewStep.Ambiguous -> {
      val ambiguous by store.selectorState(key = s.subjectKey) { calendarAmbiguousUiState(it, s.subjectKey) }
      val current = ambiguous
      if (current == null) { toList() } else {
        CalendarAmbiguousMatchScreen(
          ui = current, onBack = ::toList,
          onLeaveUnresolved = ::toList,
          onMatchSelected = { eventId ->
            val match = store.state.calendar.check.results.ambiguous
              .firstOrNull { it.candidate.subjectKey == current.subjectKey }
            val observation = match?.observations?.firstOrNull { it.platformEventId == eventId }
            if (match != null && observation != null) step = ReviewStep.Matched(match.candidate, observation) else toList()
            commands.resolveAmbiguousCalendarMatch(current.subjectKey, eventId)
          },
          modifier = modifier,
        )
      }
    }

    is ReviewStep.Differ -> {
      val differ by store.selectorState(key = s.subjectKey) { calendarDifferUiState(it, s.subjectKey) }
      val current = differ
      if (current == null) { toList() } else {
        CalendarDetailsDifferScreen(
          ui = current, onBack = ::toList,
          onFieldChoice = { field, resolution -> commands.chooseCalendarField(current.subjectKey, field, resolution) },
          modifier = modifier,
        )
      }
    }

    is ReviewStep.Recurring -> {
      val recurring by store.selectorState(key = s.subjectKey) { calendarRecurringUiState(it, s.subjectKey) }
      val current = recurring
      if (current == null) { toList() } else {
        CalendarRecurringScreen(
          ui = current, onBack = ::toList,
          onReviewOccurrence = { commands.openObservedCalendarEvent(current.platformEventId) },
          onKeepSeriesCalendarOnly = {
            ignoredDisplay[current.subjectKey] = IgnoredRowDisplay(current.subjectKey, DayfoldIcons.CalendarMonth, current.title, "Series kept calendar-only · ignored just now")
            commands.keepCalendarSeriesOnly(current.subjectKey)
            toList()
          },
          modifier = modifier,
        )
      }
    }

    is ReviewStep.Matched -> {
      val calendar = store.state.calendar.availableCalendars.firstOrNull { it.id == s.observation.calendarId }
      val date = s.candidate.startAt.take(10)
      val month = date.substring(5, 7).toIntOrNull()?.let { MONTHS.getOrNull(it - 1) } ?: ""
      val day = date.substring(8, 10).toIntOrNull()?.toString() ?: ""
      val owner = store.state.calendar.check.notificationOwnerOverrides[s.candidate.subjectKey]
        ?: CalendarNotificationOwner.CALENDAR
      CalendarMatchedSummaryScreen(
        hubTitle = s.candidate.title,
        monthAbbrev = month,
        dayNumber = day,
        dateLabel = formatMetaWhen(s.candidate.startAt) ?: s.candidate.startAt,
        timeLocationLabel = s.candidate.location?.let { it.label ?: it.address }.orEmpty().ifBlank { "Calendar is the schedule" },
        calendarName = calendar?.displayName ?: "This calendar",
        calendarDotColor = calendar?.color,
        lastCheckedLabel = compareLabel,
        onBack = ::toList,
        onOpenInCalendar = { commands.openObservedCalendarEvent(s.observation.platformEventId) },
        onUnlink = { commands.unlinkCalendarMatch(s.candidate.subjectKey); toList() },
        onAlertSettings = { alertOverrideSubject = s.candidate.subjectKey },
        modifier = modifier,
      )
      alertOverrideSubject?.let { subjectKey ->
        CalendarAlertOverrideHost(subjectKey, owner, commands) { alertOverrideSubject = null }
      }
    }

    is ReviewStep.Return -> {
      val check by store.selectorState { it.calendar.check }
      val stillPending = check.results.dayfoldOnly.any { it.subjectKey == s.row.subjectKey } ||
        check.results.suggested.any { it.candidate.subjectKey == s.row.subjectKey } ||
        check.results.ambiguous.any { it.candidate.subjectKey == s.row.subjectKey }
      val checkedSinceHandoff = check.lastCheckAt != null && check.lastCheckAt != s.checkBefore
      val phase = when {
        check.editorReturn == CalendarEditorOutcome.SAVED -> CalendarReturnPhase.ADDED
        check.editorReturn == CalendarEditorOutcome.CANCELED || check.editorReturn == CalendarEditorOutcome.DELETED -> CalendarReturnPhase.CANCELED
        check.permission != CalendarPermission.Granted && store.state.calendar.settings.featureEnabled -> CalendarReturnPhase.PERMISSION_CHANGED
        check.checkInProgress || !checkedSinceHandoff -> CalendarReturnPhase.CHECKING
        !stillPending -> CalendarReturnPhase.ADDED
        else -> CalendarReturnPhase.UNCONFIRMED
      }
      CalendarReturnScreen(
        phase = phase,
        hubTitle = s.row.title,
        dateLabel = formatMetaWhen(s.row.prefill.startAt) ?: s.row.prefill.startAt,
        timeRangeLabel = s.row.prefill.endAt?.let { "Ends ${formatMetaWhen(it) ?: it}" } ?: "Time reviewed above",
        locationLabel = s.row.prefill.location?.let { it.label ?: it.address },
        onBack = ::toList,
        onPrimaryAction = {
          when (phase) {
            CalendarReturnPhase.ADDED -> commands.openMatchedCalendarEvent(s.row.subjectKey)
            CalendarReturnPhase.CANCELED -> commands.openCalendarEventEditor(s.row.prefill)
            CalendarReturnPhase.PERMISSION_CHANGED -> onOpenAppSettings()
            CalendarReturnPhase.UNCONFIRMED -> commands.startCalendarCheck()
            CalendarReturnPhase.CHECKING -> Unit
          }
        },
        onSecondaryAction = {
          if (phase == CalendarReturnPhase.ADDED) commands.unlinkCalendarMatch(s.row.subjectKey)
          toList()
        },
        modifier = modifier,
      )
    }

    ReviewStep.Ignored -> {
      val ignoredKeysState by store.selectorState { ignoredKeysMostRecentFirst(it.calendar.check) }
      val rows = ignoredKeysState.map { key ->
        ignoredDisplay[key] ?: IgnoredRowDisplay(
          key, if (key.startsWith("calendarEvent:")) DayfoldIcons.CalendarMonth else DayfoldIcons.Event,
          if (key.startsWith("calendarEvent:")) "Calendar event" else "Dayfold item",
          "Ignored on this phone",
        )
      }
      CalendarIgnoredScreen(
        rows = rows, onBack = ::toList,
        onUndo = { row -> ignoredDisplay.remove(row.itemKey); commands.undoCalendarIgnore(row.itemKey) },
        modifier = modifier,
      )
    }
  }

  pendingPrefill?.let { row ->
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = { pendingPrefill = null }, sheetState = sheetState) {
      PrefillSheetContent(
        eventTitle = row.title,
        rows = buildList {
          add(PrefillRow(DayfoldIcons.CalendarMonth, "Date and time", formatMetaWhen(row.prefill.startAt) ?: row.prefill.startAt))
          row.prefill.location?.let { location ->
            add(PrefillRow(DayfoldIcons.Location, "Location", location.label ?: location.address.orEmpty()))
          }
        },
        onCancel = { pendingPrefill = null },
        onOpenCalendarApp = {
          pendingPrefill = null
          step = ReviewStep.Return(row, lastCheckAt)
          commands.openCalendarEventEditor(row.prefill)
        },
      )
    }
  }
}

private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
