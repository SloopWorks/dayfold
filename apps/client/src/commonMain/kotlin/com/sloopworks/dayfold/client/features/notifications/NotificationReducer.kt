package com.sloopworks.dayfold.client

/** Device-local notification and permission projections survive account resets. */
fun reduceNotifications(state: AppState, action: Any): AppState = when (action) {
  is NotifConfigLoaded -> state.copy(notifications = state.notifications.copy(config = action.config))
  is LocationPermissionLoaded -> state.copy(notifications = state.notifications.copy(locationPermission = action.state))
  is NotificationPermissionLoaded -> state.copy(notifications = state.notifications.copy(notificationPermission = action.state))
  else -> state
}
