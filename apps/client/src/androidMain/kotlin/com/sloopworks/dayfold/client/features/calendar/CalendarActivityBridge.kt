package com.sloopworks.dayfold.client

import android.content.Intent

/**
 * Rebinds the Activity-owned ActivityResultLaunchers behind [AndroidCalendarPort] on every Activity
 * (re)creation — mirrors AndroidApiConfigHolder (ADR 0020 R3). [CalendarCheckEngine] and the
 * [CalendarPort] it owns are retained across a config change inside the ViewModel-scoped
 * DayfoldRuntimeGraph; the Activity that can actually launch a permission prompt or the native
 * event-editor Intent is not — its launchers die and are re-registered on every recreation.
 * Between an old Activity's onDestroy and its replacement's onCreate rebinding these, a request
 * landing in that narrow window is a silent no-op (mirrors how a real permission prompt can't
 * survive without a foreground Activity anyway) rather than a call into a dead launcher.
 */
object CalendarActivityBridge {
  @Volatile var launchPermissionRequest: (() -> Unit)? = null
  @Volatile var onPermissionResult: ((CalendarPermission) -> Unit)? = null
  @Volatile var launchEditorIntent: ((Intent) -> Unit)? = null
}
