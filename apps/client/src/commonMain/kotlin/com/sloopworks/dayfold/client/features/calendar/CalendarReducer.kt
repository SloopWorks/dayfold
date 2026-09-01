package com.sloopworks.dayfold.client

/** Device-local Calendar Check settings and OS calendar choices hydrated into the store. */
fun reduceCalendar(state: AppState, action: Any): AppState = when (action) {
  is CalendarSettingsLoaded -> state.copy(calendar = state.calendar.copy(settings = action.settings))
  is DeviceCalendarsLoaded -> state.copy(calendar = state.calendar.copy(availableCalendars = action.calendars))
  else -> state
}

/**
 * CAL-4 (ADR 0063 §4/§5) — pure transitions over state.calendar.check. CalendarCheckEngine owns
 * every DB/port effect (auto-binding persistence, notification-owner writes, the reset); this
 * function only ever produces the next AppState from the current one + the action.
 */
fun reduceCalendarCheck(state: AppState, action: CalendarCheckAction): AppState {
  val check = state.calendar.check
  val next = when (action) {
    StartCalendarCheck -> check.copy(checkInProgress = true)

    is CalendarCheckCompleted -> check.copy(
      checkInProgress = false,
      permission = action.permission,
      lastCheckAt = action.checkedAt,
      stale = action.stale,
      results = action.results,
      ignored = action.ignoredKeys?.toSet() ?: check.ignored,
      ignoreHistory = action.ignoredKeys ?: check.ignoreHistory,
    )

    is ConfirmMatch -> check.copy(results = check.results.withoutSubject(action.subjectKey))

    is KeepSeparate -> {
      val candidate = check.results.candidateFor(action.subjectKey)
      val cleared = check.results.withoutSubject(action.subjectKey)
      check.copy(results = if (candidate != null) cleared.copy(dayfoldOnly = cleared.dayfoldOnly + candidate) else cleared)
    }

    is ResolveAmbiguous -> check.copy(results = check.results.withoutSubject(action.subjectKey))

    is IgnoreItem -> check.copy(
      ignored = check.ignored + action.itemKey,
      // A double-tap before the row disappears would otherwise dispatch IgnoreItem twice for the
      // same key; ignoreHistory must stay duplicate-free or a single UndoIgnore(itemKey) (which
      // removes every occurrence, see below) would leave a since-removed key visible forever.
      ignoreHistory = if (action.itemKey in check.ignoreHistory) check.ignoreHistory else check.ignoreHistory + action.itemKey,
      results = check.results.withoutItemKey(action.itemKey),
    )

    is UndoIgnore -> if (action.itemKey !in check.ignored) check else check.copy(
      ignored = check.ignored - action.itemKey,
      ignoreHistory = check.ignoreHistory.filterNot { it == action.itemKey },
    )

    is FieldChoice -> check.copy(results = check.results.resolveField(action.subjectKey, action.field))

    is KeepSeriesCalendarOnly -> check.copy(
      ignored = check.ignored + action.subjectKey,
      ignoreHistory = check.ignoreHistory + action.subjectKey,
      results = check.results.copy(recurringNotices = check.results.recurringNotices.filterNot { it.candidate.subjectKey == action.subjectKey }),
    )

    ResetLocalMatches -> CalendarCheckState()

    is SetNotificationOwner -> check.copy(notificationOwnerOverrides = check.notificationOwnerOverrides + (action.subjectKey to action.owner))

    CalendarEditorOpened -> check.copy(editorReturn = null)

    is CalendarEditorReturned -> check.copy(editorReturn = action.outcome)

  }
  return state.copy(calendar = state.calendar.copy(check = next))
}
