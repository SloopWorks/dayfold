package com.sloopworks.dayfold.client

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import kotlinx.datetime.TimeZone

// ADR 0044 §S3 — Android device-glue actuals + the shared headless pass. All decision logic stays in
// commonMain (planBackgroundNotifications / BackgroundNotificationRunner); these are thin OS bridges.

// The single entry point both background receivers call: read the synchronous snapshot from the
// process-shared store, run the pass (reusing nowFeed + selectNotifications — no engine fork), post via
// NotificationCompat, write the notification_log. [location] = the place coord on a geofence enter (the
// live position never persists), or null for a time-only wake. Returns the plan (for diagnostics).
fun runBackgroundNotificationPass(context: Context, location: DeviceLocation?): NotifPlan {
  val cs = AndroidContentStoreHolder.get(context)
  val nowIso = kotlin.time.Clock.System.now().toString()
  val runner = BackgroundNotificationRunner(AndroidLocalNotifier(context), cs::logNotification)
  return runner.run(cs.notifSnapshot(), nowIso, location, TimeZone.currentSystemDefault())
}

// Handle a geofence broadcast end-to-end (called from the androidApp receiver, which stays free of any
// Play-Services import). On a real ENTER, resolve each triggering region → place coord → run the pass.
fun onGeofenceEnter(context: Context, intent: Intent) {
  val event = com.google.android.gms.location.GeofencingEvent.fromIntent(intent) ?: return
  if (event.hasError()) return
  if (event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER) return
  val ids = event.triggeringGeofences?.map { it.requestId } ?: return
  ids.forEach { id -> runBackgroundNotificationPass(context, placeLocationForRegion(context, id)) }
}

// Re-register geofences from scratch (BOOT_COMPLETED / MY_PACKAGE_REPLACED / place change). No live
// location at boot → register ALL saved places (capped); the nearest-N narrowing happens later when a
// location is available. No-op when the feature is off (default).
fun reRegisterGeofences(context: Context) {
  val cs = AndroidContentStoreHolder.get(context)
  if (!cs.notifConfig().enabled) return
  val regions = cs.activePlaces().take(ANDROID_REGION_CAP)
    .map { GeoRegion(it.id, it.lat, it.lng, it.radiusM?.toDouble() ?: DEFAULT_GEOFENCE_RADIUS_M) }
  AndroidGeofenceController(context).register(regions)
}

// Resolve a triggering geofence's request-id back to its saved-place coordinate — region-enter delivers
// an identifier, not a location, so we read the coord from the synced places (the "live position" proxy
// for the pass is the place you just arrived at). Returns null if the id is unknown (place de-synced).
fun placeLocationForRegion(context: Context, regionId: String): DeviceLocation? =
  AndroidContentStoreHolder.get(context).activePlaces().firstOrNull { it.id == regionId }
    ?.let { DeviceLocation(it.lat, it.lng) }

// Arm the exact local notifications for known future instants (the pure planExactSchedules decides
// which + when). Re-arm is idempotent — same subject → same alarm request code → updated in place. Stale
// alarms (item since removed) fire harmlessly: the receiver re-runs the pass, which posts nothing if the
// subject is gone. No-op when the feature is off. Called on enable + after a content sync.
fun reconcileExactSchedules(context: Context) {
  val cs = AndroidContentStoreHolder.get(context)
  if (!cs.notifConfig().enabled) return
  val nowIso = kotlin.time.Clock.System.now().toString()
  val scheduler = AndroidExactNotificationScheduler(context)
  planExactSchedules(cs.notifSnapshot(), nowIso, TimeZone.currentSystemDefault())
    .forEach { scheduler.schedule(it.atIso, it.spec) }
}

// Geofencing (GeofencingClient). PendingIntent is MUTABLE (the OS injects the GeofencingEvent). The
// receiver is targeted by package + action (no :client→:androidApp compile dependency). FINE/BACKGROUND
// location are caller-ensured (the permission ladder); MissingPermission is suppressed deliberately.
class AndroidGeofenceController(private val context: Context) : GeofenceController {
  private val client: GeofencingClient = LocationServices.getGeofencingClient(context)

  private fun pendingIntent(): PendingIntent {
    val intent = Intent(GEOFENCE_ACTION).setPackage(context.packageName)
    return PendingIntent.getBroadcast(
      context, 0, intent,
      PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
  }

  @SuppressLint("MissingPermission")
  override fun register(regions: List<GeoRegion>) {
    if (regions.isEmpty()) { deregisterAll(); return }
    val geofences = regions.map { r ->
      Geofence.Builder()
        .setRequestId(r.id)
        .setCircularRegion(r.lat, r.lng, r.radiusM.toFloat())
        .setExpirationDuration(Geofence.NEVER_EXPIRE)
        .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
        .build()
    }
    val request = GeofencingRequest.Builder()
      .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
      .addGeofences(geofences)
      .build()
    runCatching { client.addGeofences(request, pendingIntent()) }
  }

  override fun deregisterAll() { runCatching { client.removeGeofences(pendingIntent()) } }

  companion object { const val GEOFENCE_ACTION = "com.sloopworks.dayfold.GEOFENCE_EVENT" }
}

// Exact local notifications at known future instants (when.at / countdown / milestone) — NOT a periodic
// worker (Doze would miss the moment). setExactAndAllowWhileIdle fires the alarm; the receiver runs the
// pass (so the daily cap / quiet hours / dedup are honored at FIRE time, not schedule time).
class AndroidExactNotificationScheduler(private val context: Context) : ExactNotificationScheduler {
  private val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

  private fun pendingIntent(subjectKey: String): PendingIntent {
    val intent = Intent(EXACT_ALARM_ACTION).setPackage(context.packageName)
      .putExtra(EXTRA_SUBJECT_KEY, subjectKey)
    return PendingIntent.getBroadcast(
      context, subjectKey.hashCode(), intent,
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
  }

  @SuppressLint("MissingPermission")
  override fun schedule(atIso: String, spec: NotificationSpec) {
    val atMillis = parseInstantFlexible(atIso, TimeZone.UTC)?.toEpochMilliseconds() ?: return
    runCatching {
      alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pendingIntent(spec.subjectKey))
    }
  }

  override fun cancel(subjectKey: String) { runCatching { alarms.cancel(pendingIntent(subjectKey)) } }

  companion object {
    const val EXACT_ALARM_ACTION = "com.sloopworks.dayfold.EXACT_ALARM"
    const val EXTRA_SUBJECT_KEY = "dayfold.subjectKey"
  }
}
