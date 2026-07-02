package com.sloopworks.dayfold.client

import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter

// ADR 0044 §S3 — iOS device-glue for the two background lanes + the exact scheduler. All decision logic
// stays in commonMain (planExactSchedules / planBackgroundNotifications / BackgroundNotificationRunner —
// no engine fork); these are thin OS bridges, mirroring AndroidBackgroundNotify.

// ── EXACT (time/date) lane ─────────────────────────────────────────────────────────────────────────
// Schedules a LOCAL notification to fire at a known future instant. iOS delivers scheduled local notifs
// directly (no background wake for this lane) — so, UNLIKE Android's ExactAlarmReceiver, there is NO
// re-run of the full pass at fire time. The pre-baked spec fires as-is. To keep the ADR 0044 posture
// honest, quiet-hours / cap / dedup are therefore applied at SCHEDULE time in reconcileExactSchedules
// (below), re-reconciled on config + content change. Residual divergence (the cap count can be stale by
// fire time) is the documented Phase-B iOS note in ADR 0044.
class IosExactNotificationScheduler : ExactNotificationScheduler {
  private val center get() = UNUserNotificationCenter.currentNotificationCenter()

  override fun schedule(atIso: String, spec: NotificationSpec) {
    val at = parseInstantFlexible(atIso, TimeZone.UTC) ?: return
    val seconds = (at - Clock.System.now()).inWholeMilliseconds / 1000.0
    if (seconds <= 0.0) return   // UNTimeIntervalNotificationTrigger requires a strictly-positive interval
    val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(seconds, repeats = false)
    val request = UNNotificationRequest.requestWithIdentifier(spec.subjectKey, buildContent(spec), trigger)
    center.addNotificationRequest(request, withCompletionHandler = null)
  }

  override fun cancel(subjectKey: String) {
    center.removePendingNotificationRequestsWithIdentifiers(listOf(subjectKey))
  }
}

// Arm the exact local notifications for known future instants (planExactSchedules decides which + when).
// Re-arm is idempotent (same subject id → replaced). No-op when the feature is off. Called on enable +
// after a content sync. Because iOS can't re-run the pass at fire time, we apply the posture filters HERE
// (schedule time): skip a subject already notified today, skip a non-urgent trigger that lands inside
// quiet hours (urgent bypasses quiet, mirroring selectNotifications), and stop once today's cap is met.
fun reconcileExactSchedules() {
  val cs = IosContentStoreHolder.get()
  val config = cs.notifConfig()
  if (!config.enabled) return
  val zone = TimeZone.currentSystemDefault()
  val nowIso = Clock.System.now().toString()

  val log = cs.notifSnapshot().log
  val today = parseInstantFlexible(nowIso, zone)?.toLocalDateTime(zone)?.date
  val notifiedTodaySubjects = if (today == null) emptySet() else log
    .filter { parseInstantFlexible(it.notifiedAtIso, zone)?.toLocalDateTime(zone)?.date == today }
    .map { it.subjectKey }.toSet()
  var remainingCap = (config.dailyCap - postedTodayCount(log.map { it.notifiedAtIso }, nowIso, zone))
    .coerceAtLeast(0)

  val scheduler = IosExactNotificationScheduler()
  for (sched in planExactSchedules(cs.notifSnapshot(), nowIso, zone)) {
    if (remainingCap <= 0) break
    if (sched.spec.subjectKey in notifiedTodaySubjects) continue
    val at = parseInstantFlexible(sched.atIso, zone) ?: continue
    val minuteOfDay = at.toLocalDateTime(zone).let { it.hour * 60 + it.minute }
    // Non-urgent triggers inside quiet hours are held on Android (re-evaluated at window end); iOS has no
    // wake to re-evaluate, so we simply don't arm them (they surface in-app as the calm "held" state).
    if (!sched.spec.urgent && inQuietHours(minuteOfDay, config)) continue
    scheduler.schedule(sched.atIso, sched.spec)
    remainingCap--
  }
}

// ── GEOFENCE (place) lane ──────────────────────────────────────────────────────────────────────────
// The single entry point the region-enter delegate + BGTask call: read the synchronous snapshot from the
// process-shared store, run the SHARED pass (nowFeed + selectNotifications — no engine fork), post via
// UNUserNotificationCenter, write notification_log. [location] = the arrived place coord on a region
// enter (the live position never persists), or null for a time-only wake. Returns the plan (diagnostics).
fun runBackgroundNotificationPass(location: DeviceLocation?): NotifPlan {
  val cs = IosContentStoreHolder.get()
  val nowIso = Clock.System.now().toString()
  val runner = BackgroundNotificationRunner(IosLocalNotifier(), cs::logNotification)
  return runner.run(cs.notifSnapshot(), nowIso, location, TimeZone.currentSystemDefault())
}

// Resolve a triggering region's id back to its saved-place coordinate — region-enter delivers an
// identifier, not a location, so the coord comes from the synced places (the arrived place is the "live
// position" proxy for the pass). Null if the id is unknown (place de-synced). Mirrors Android.
fun placeLocationForRegion(regionId: String): DeviceLocation? =
  IosContentStoreHolder.get().activePlaces().firstOrNull { it.id == regionId }
    ?.let { DeviceLocation(it.lat, it.lng) }

// (Re)register the geofences from the saved places (enable / place change / app foreground). ≤ the iOS
// 20-region cap: register directly. > cap: take a one-shot location fix and register the nearest-N
// (registerNearest). No-op / deregister when the feature is off. Mirrors Android's reRegisterGeofences.
fun reRegisterGeofences() {
  val cs = IosContentStoreHolder.get()
  if (!cs.notifConfig().enabled) { IosNotifGlue.geofence.deregisterAll(); return }
  val places = cs.activePlaces()
  if (places.size <= IOS_REGION_CAP) {
    IosNotifGlue.geofence.register(
      places.map { GeoRegion(it.id, it.lat, it.lng, it.radiusM?.toDouble() ?: DEFAULT_GEOFENCE_RADIUS_M) },
    )
  } else {
    IosNotifGlue.geofence.registerNearest(places)   // one-shot fix → nearest-20
  }
}

// BGTaskScheduler handler entry (registered in Swift AppDelegate). Reconcile-only: neither lane needs a
// BGTask to DELIVER (region monitoring wakes the app; scheduled local notifs fire directly) — this just
// keeps the region set + exact schedules fresh opportunistically. Re-submit is done on the Swift side.
fun bgReconcile() {
  reRegisterGeofences()
  reconcileExactSchedules()
}
