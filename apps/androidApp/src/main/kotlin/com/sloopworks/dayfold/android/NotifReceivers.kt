package com.sloopworks.dayfold.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sloopworks.dayfold.client.onGeofenceEnter
import com.sloopworks.dayfold.client.reRegisterGeofences
import com.sloopworks.dayfold.client.runBackgroundNotificationPass

// ADR 0044 §S3 — the headless background entry points. Thin: all decision logic lives in :client
// commonMain (planBackgroundNotifications / BackgroundNotificationRunner over the shared store snapshot);
// these receivers just hand off. goAsync() keeps the process alive for the short DB-read + notify.

// Geofence ENTER → run the pass with the arrived place's coord (the live position never persists).
class GeofenceReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val pending = goAsync()
    Thread {
      try { onGeofenceEnter(context.applicationContext, intent) } finally { pending.finish() }
    }.start()
  }
}

// Exact alarm (known future instant) → run the pass with no location (time-only); the cap / quiet hours
// / dedup are honored at FIRE time, not schedule time.
class ExactAlarmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val pending = goAsync()
    Thread {
      try { runBackgroundNotificationPass(context.applicationContext, null) } finally { pending.finish() }
    }.start()
  }
}

// BOOT_COMPLETED / MY_PACKAGE_REPLACED → re-arm geofences (the OS drops them on reboot / app update).
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val pending = goAsync()
    Thread {
      try { reRegisterGeofences(context.applicationContext) } finally { pending.finish() }
    }.start()
  }
}
