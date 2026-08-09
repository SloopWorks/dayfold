package com.sloopworks.dayfold.client

/** Device-local Calendar Check settings — DB→store bridge plus user-initiated changes (WI-447),
 *  all landing in the same state.calendar.settings/availableCalendars slices. */
fun reduceCalendar(state: AppState, action: Any): AppState = when (action) {
  is CalendarSettingsLoaded -> state.copy(calendar = state.calendar.copy(settings = action.settings))
  is SetCalendarEnabled -> state.copy(calendar = state.calendar.copy(settings = state.calendar.settings.copy(featureEnabled = action.enabled)))
  is SetSelectedCalendars -> state.copy(calendar = state.calendar.copy(settings = state.calendar.settings.copy(selectedCalendarIds = action.calendarIds)))
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
      ignoreHistory = check.ignoreHistory + action.itemKey,
      results = check.results.withoutItemKey(action.itemKey),
    )

    is UndoIgnore -> if (action.itemKey !in check.ignored) check else check.copy(
      ignored = check.ignored - action.itemKey,
      ignoreHistory = check.ignoreHistory - action.itemKey,
    )

    is FieldChoice -> {
      val pendingWrites = if (action.resolution == FieldResolution.USE_CALENDAR) {
        val calendarValue = check.results.differs
          .firstOrNull { it.subjectKey == action.subjectKey }
          ?.diffs?.firstOrNull { it.field == action.field }
          ?.calendarValue
        check.pendingWrites + PendingFieldWrite(action.subjectKey, action.field, calendarValue)
      } else {
        check.pendingWrites
      }
      check.copy(results = check.results.resolveField(action.subjectKey, action.field), pendingWrites = pendingWrites)
    }

    is KeepSeriesCalendarOnly -> check.copy(
      ignored = check.ignored + action.subjectKey,
      ignoreHistory = check.ignoreHistory + action.subjectKey,
      results = check.results.copy(recurringNotices = check.results.recurringNotices.filterNot { it.candidate.subjectKey == action.subjectKey }),
    )

    ResetLocalMatches -> CalendarCheckState()

    is SetNotificationOwner -> check.copy(notificationOwnerOverrides = check.notificationOwnerOverrides + (action.subjectKey to action.owner))
  }
  return state.copy(calendar = state.calendar.copy(check = next))
}
