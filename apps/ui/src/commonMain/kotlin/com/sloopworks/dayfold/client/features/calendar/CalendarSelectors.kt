package com.sloopworks.dayfold.client.features.calendar

import com.sloopworks.dayfold.client.AppState
import com.sloopworks.dayfold.client.CalendarPermission
import com.sloopworks.dayfold.client.DeviceCalendar

// WI-447 (ADR 0063) — pure UI viewstate over state.calendar. No DB/port access here (that's
// CalendarCheckEngine's job, ADR 0058); these functions only reshape already-loaded state for
// the Compose screens in this package.

/** The chooser's / settings-on list, grouped by masked account label — never the raw account. */
data class CalendarAccountGroup(val accountLabel: String, val calendars: List<DeviceCalendar>)

fun groupCalendarsByAccount(calendars: List<DeviceCalendar>): List<CalendarAccountGroup> =
  calendars.groupBy { it.accountLabel }.map { (label, cals) -> CalendarAccountGroup(label, cals) }

/** Settings — off/on/primer/denied/no-calendars share this read of state.calendar.settings +
 *  the OS-owned permission + the on-demand device calendar list (never persisted, ADR 0063 §3). */
data class CalendarSettingsUiState(
  val featureEnabled: Boolean,
  val permission: CalendarPermission,
  val availableCalendars: List<DeviceCalendar>,
  val selectedCalendarIds: Set<String>,
  val lastCheckAt: String?,
)

fun calendarSettingsUiState(state: AppState): CalendarSettingsUiState = CalendarSettingsUiState(
  featureEnabled = state.calendar.settings.featureEnabled,
  permission = state.calendar.check.permission,
  availableCalendars = state.calendar.availableCalendars,
  selectedCalendarIds = state.calendar.settings.selectedCalendarIds,
  lastCheckAt = state.calendar.settings.lastCheckAt,
)

/** The calendars actually included right now (settings-on list / change-calendars sheet). */
fun CalendarSettingsUiState.includedCalendars(): List<DeviceCalendar> =
  availableCalendars.filter { it.id in selectedCalendarIds }
