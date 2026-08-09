package com.sloopworks.dayfold.client

/** Device-local Calendar Check settings — DB→store bridge (sole writer of state.calendar.settings). */
fun reduceCalendar(state: AppState, action: Any): AppState = when (action) {
  is CalendarSettingsLoaded -> state.copy(calendar = state.calendar.copy(settings = action.settings))
  else -> state
}
