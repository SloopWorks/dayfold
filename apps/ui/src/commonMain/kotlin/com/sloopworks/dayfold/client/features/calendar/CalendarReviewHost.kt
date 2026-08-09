package com.sloopworks.dayfold.client.features.calendar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.sloopworks.dayfold.client.AppState
import com.sloopworks.dayfold.client.CalendarGapKind
import com.sloopworks.dayfold.client.DayfoldCommandPort
import com.sloopworks.dayfold.client.DeepLinkTarget
import com.sloopworks.dayfold.client.DayfoldIcons
import com.sloopworks.dayfold.client.formatMetaWhen
import org.reduxkotlin.compose.SelectorStore
import org.reduxkotlin.compose.selectorState

// WI-446 (ADR 0063 §4/§5) — wires the stateless review screens in this package to the store +
// command port (mirrors CalendarSettingsHost, WI-447). Local, in-Compose navigation only: nothing
// here is redux state, matching every other multi-step surface's route-graph deferral in this epic
// (ADR 0063 is still Proposed) — a later WI mounts this from the real Now-card "Review" tap once
// accepted.

private sealed interface ReviewStep {
  data object List : ReviewStep
  data class Suggested(val subjectKey: String) : ReviewStep
  data class Ambiguous(val subjectKey: String) : ReviewStep
  data class Differ(val subjectKey: String) : ReviewStep
  data class Recurring(val subjectKey: String) : ReviewStep
  data object Ignored : ReviewStep
}

@Composable
fun CalendarReviewHost(
  store: SelectorStore<AppState>,
  commands: DayfoldCommandPort,
  onBack: () -> Unit,
  // "Open Hub" / "Add to a Hub" are cross-surface deep-links; the caller wires them the same way
  // CardAction.OpenHub is routed elsewhere (this package doesn't own familyId/hub navigation).
  onOpenHub: (DeepLinkTarget) -> Unit = {},
  onAddToHub: (CalendarOnlyRow) -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val ui by store.selectorState(::calendarReviewListUiState)
  val lastCheckAt by store.selectorState { it.calendar.check.lastCheckAt }
  val compareLabel = formatMetaWhen(lastCheckAt)?.let { "Compared on this phone · $it" } ?: "Compared on this phone"

  var step by remember { mutableStateOf<ReviewStep>(ReviewStep.List) }
  // Ignoring drops the item from state.calendar.check.results on the same dispatch (see
  // CalendarSelectors.kt's ignoredKeysMostRecentFirst doc) — this local cache is the ONLY place
  // an ignored item's title/meta survives, for exactly the items ignored during this visit.
  val ignoredDisplay = remember { mutableStateMapOf<String, IgnoredRowDisplay>() }

  fun toList() { step = ReviewStep.List }

  when (val s = step) {
    ReviewStep.List -> CalendarReviewListScreen(
      ui = ui, compareLabel = compareLabel, onBack = onBack,
      onAddToCalendar = { row -> commands.openCalendarEventEditor(row.prefill) },
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
      modifier = modifier,
    )

    is ReviewStep.Suggested -> {
      val suggested by store.selectorState(key = s.subjectKey) { calendarSuggestedUiState(it, s.subjectKey) }
      val current = suggested
      if (current == null) { toList() } else {
        CalendarSuggestedMatchScreen(
          ui = current, onBack = ::toList,
          onKeepSeparate = { commands.keepCalendarSeparate(current.subjectKey); toList() },
          onConfirmMatch = { commands.confirmCalendarMatch(current.subjectKey, current.eventId); toList() },
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
          onMatchSelected = { eventId -> commands.resolveAmbiguousCalendarMatch(current.subjectKey, eventId); toList() },
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
          // No domain action exists yet for binding a single occurrence (ADR 0063 §4 first-slice
          // limit: recurring series matching is deferred) — this stays a no-op until that lands.
          onReviewOccurrence = {},
          onKeepSeriesCalendarOnly = {
            ignoredDisplay[current.subjectKey] = IgnoredRowDisplay(current.subjectKey, DayfoldIcons.CalendarMonth, current.title, "Series kept calendar-only · ignored just now")
            commands.keepCalendarSeriesOnly(current.subjectKey)
            toList()
          },
          modifier = modifier,
        )
      }
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
        onUndo = { row -> ignoredDisplay.remove(row.itemKey); commands.undoCalendarIgnore() },
        modifier = modifier,
      )
    }
  }
}
