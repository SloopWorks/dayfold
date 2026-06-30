package com.sloopworks.dayfold.client

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

// ADR 0044 §S3 — Android permission controllers. OS permission is OS-owned truth, NEVER DB-cached:
// currentState() reads it live; the Flow re-emits when refresh() is called (Android emits no permission-
// change broadcast → the host calls refresh() on resume). The in-app runtime PROMPT is wired at the
// Compose call site (rememberLauncherForActivityResult, needs an Activity); request* here falls back to
// the OS settings deep-link so a controller without a launcher still leads the user somewhere honest.

private fun Context.granted(permission: String): Boolean =
  ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun Context.openAppSettings() {
  val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  runCatching { startActivity(intent) }
}

class AndroidLocationPermissionController(context: Context) : LocationPermissionController {
  private val app = context.applicationContext
  private val _state = MutableStateFlow(currentState())
  override val state: Flow<LocationPermission> = _state.asStateFlow()

  override fun currentState(): LocationPermission {
    val fine = app.granted(Manifest.permission.ACCESS_FINE_LOCATION)
    // BACKGROUND is a separate runtime permission only on Q+ — pre-Q, fine implies background.
    val background = if (Build.VERSION.SDK_INT >= 29) app.granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) else fine
    return when {
      fine && background -> LocationPermission.Always
      fine -> LocationPermission.WhenInUse
      else -> LocationPermission.Denied
    }
  }

  /** Re-read OS truth (call on app resume — Android has no permission-change broadcast). */
  fun refresh() { _state.value = currentState() }

  // The actual prompts are launched from the permission-ladder Compose screen via an ActivityResult
  // launcher (it needs an Activity). Absent that wiring, lead the user to OS settings — never a dead end.
  override fun requestWhenInUse() = app.openAppSettings()
  override fun requestAlways() = app.openAppSettings()
  override fun openOsSettings() = app.openAppSettings()
}

class AndroidNotificationPermissionController(context: Context) : NotificationPermissionController {
  private val app = context.applicationContext
  private val _state = MutableStateFlow(currentState())
  override val state: Flow<NotificationPermission> = _state.asStateFlow()

  override fun currentState(): NotificationPermission {
    // Blocked = notifications disabled in the OS (channel importance NONE / app toggle off) — detected,
    // never overridden. Otherwise on API 33+ the runtime grant gates it; pre-33 it's implicitly granted.
    if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return NotificationPermission.Blocked
    val granted = if (Build.VERSION.SDK_INT >= 33) app.granted(Manifest.permission.POST_NOTIFICATIONS) else true
    return if (granted) NotificationPermission.Granted else NotificationPermission.Denied
  }

  fun refresh() { _state.value = currentState() }

  override fun request() = app.openAppSettings()
  override fun openOsSettings() = app.openAppSettings()
}
