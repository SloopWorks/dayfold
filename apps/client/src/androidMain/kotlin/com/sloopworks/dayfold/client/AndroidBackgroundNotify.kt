package com.sloopworks.dayfold.client

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import kotlinx.datetime.TimeZone
import java.util.concurrent.TimeUnit

// ADR 0044 §S3 — Android device-glue actuals + the shared headless pass. All decision logic stays in
// commonMain (planBackgroundNotifications / BackgroundNotificationRunner); these are thin OS bridges.

// The single entry point both background receivers call: read the synchronous snapshot from the
// process-shared store, run the pass (reusing nowFeed + selectNotifications — no engine fork), post via
// NotificationCompat, write the notification_log. [location] = the place coord on a geofence enter (the
// live position never persists), or null for a time-only wake. Returns the plan (for diagnostics).
fun runBackgroundNotificationPass(context: Context, location: DeviceLocation?): NotifPlan =
  AndroidNotificationLifecycleCoordinator.runIfAdmitted(
    orElse = { NotifPlan() },
    action = { runBackgroundNotificationPassUnchecked(context, location) },
  )

private fun runBackgroundNotificationPassUnchecked(context: Context, location: DeviceLocation?): NotifPlan {
  val cs = AndroidContentStoreHolder.get(context)
  val nowIso = kotlin.time.Clock.System.now().toString()
  val runner = BackgroundNotificationRunner(AndroidLocalNotifier(context), cs::logNotification)
  return runner.run(cs.notifSnapshot(), nowIso, location, TimeZone.currentSystemDefault())
}

/**
 * BroadcastReceivers are free to execute on different threads. Hold one process-wide critical section
 * across snapshot → select → platform post → ledger commit so simultaneous geofence/exact wakes cannot
 * both spend the same daily-cap slot or post the same subject from the same pre-write snapshot.
 */
internal object AndroidNotificationLifecycleCoordinator {
  private val coordinator = NotificationPassAdmissionCoordinator()

  fun <T> runIfAdmitted(orElse: () -> T, action: () -> T): T =
    coordinator.runIfAdmitted(orElse = orElse, action = action)

  fun closeAndDrain(blocker: NotificationAdmissionBlocker, cleanup: () -> Unit) =
    coordinator.closeAndDrain(blocker = blocker, cleanup = cleanup)

  fun openAdmission(blocker: NotificationAdmissionBlocker) = coordinator.openAdmission(blocker)
}

/**
 * Close notification admission, drain an active pass/reconcile, then clear every owned OS artifact.
 * Admission deliberately remains closed through the caller's family-cache wipe or disabled-config
 * transition. [finishAndroidFamilyNotificationStateClear] is the explicit success commit.
 */
fun clearAndroidFamilyNotificationState(context: Context) {
  AndroidNotificationLifecycleCoordinator.closeAndDrain(NotificationAdmissionBlocker.FAMILY) {
    AndroidGeofenceController(context).deregisterAllAndAwait()
    AndroidExactNotificationScheduler(context).cancelAll()
    AndroidLocalNotifier(context).cancelAll()
  }
}

fun finishAndroidFamilyNotificationStateClear() {
  AndroidNotificationLifecycleCoordinator.openAdmission(NotificationAdmissionBlocker.FAMILY)
}

fun disableAndroidNotificationState(context: Context) {
  AndroidNotificationLifecycleCoordinator.closeAndDrain(NotificationAdmissionBlocker.CONFIG) {
    AndroidGeofenceController(context).deregisterAllAndAwait()
    AndroidExactNotificationScheduler(context).cancelAll()
    AndroidLocalNotifier(context).cancelAll()
  }
}

fun enableAndroidNotificationState() {
  AndroidNotificationLifecycleCoordinator.openAdmission(NotificationAdmissionBlocker.CONFIG)
}

// Handle a geofence broadcast end-to-end (called from the androidApp receiver, which stays free of any
// Play-Services import). On a real ENTER, resolve each triggering region → place coord → run the pass.
fun onGeofenceEnter(context: Context, intent: Intent) {
  val event = com.google.android.gms.location.GeofencingEvent.fromIntent(intent) ?: return
  if (event.hasError()) return
  if (event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER) return
  val ids = event.triggeringGeofences?.map { it.requestId } ?: return
  // Admit at receiver entry, before reading a family-scoped place. The unchecked pass stays inside the
  // same execution gate so family cleanup cannot interleave between region resolution and ledger write.
  AndroidNotificationLifecycleCoordinator.runIfAdmitted(orElse = {}, action = {
    ids.forEach { id -> runBackgroundNotificationPassUnchecked(context, placeLocationForRegion(context, id)) }
  })
}

// Re-register geofences from scratch (BOOT_COMPLETED / MY_PACKAGE_REPLACED / place change). No live
// location at boot → register ALL saved places (capped); the nearest-N narrowing happens later when a
// location is available. No-op when the feature is off (default).
fun reRegisterGeofences(context: Context) {
  AndroidNotificationLifecycleCoordinator.runIfAdmitted(orElse = {}, action = {
    val cs = AndroidContentStoreHolder.get(context)
    if (cs.notifConfig().enabled) {
      val regions = cs.activePlaces().take(ANDROID_REGION_CAP)
        .map { GeoRegion(it.id, it.lat, it.lng, it.radiusM?.toDouble() ?: DEFAULT_GEOFENCE_RADIUS_M) }
      AndroidGeofenceController(context).register(regions)
    }
  })
}

// Resolve a triggering geofence's request-id back to its saved-place coordinate — region-enter delivers
// an identifier, not a location, so we read the coord from the synced places (the "live position" proxy
// for the pass is the place you just arrived at). Returns null if the id is unknown (place de-synced).
fun placeLocationForRegion(context: Context, regionId: String): DeviceLocation? =
  AndroidContentStoreHolder.get(context).activePlaces().firstOrNull { it.id == regionId }
    ?.let { DeviceLocation(it.lat, it.lng) }

// Arm the exact local notifications for known future instants (the pure planExactSchedules decides
// which + when). Re-arm is idempotent — same subject → same alarm request code → updated in place. A
// completed subject is cancelled here as well as suppressed at fire time, so it cannot leave a needless
// wake behind when a tombstone was skipped by an old cursor. No-op when the feature is off. Called on
// enable + after a content sync.
fun reconcileExactSchedules(context: Context) {
  AndroidNotificationLifecycleCoordinator.runIfAdmitted(orElse = {}, action = {
    val cs = AndroidContentStoreHolder.get(context)
    val nowIso = kotlin.time.Clock.System.now().toString()
    val snapshot = cs.notifSnapshot()
    val scheduler = AndroidExactNotificationScheduler(context)
    val notifier = AndroidLocalNotifier(context)
    completedNotificationSubjects(snapshot).forEach { subjectKey ->
      scheduler.cancel(subjectKey)
      notifier.cancel(subjectKey)
    }
    val desired = if (snapshot.config.enabled) {
      planExactSchedules(snapshot, nowIso, TimeZone.currentSystemDefault())
    } else {
      emptyList()
    }
    scheduler.replace(desired)
  })
}

// Geofencing (GeofencingClient). PendingIntent is MUTABLE (the OS injects the GeofencingEvent). The
// receiver is targeted by package + action (no :client→:androidApp compile dependency). FINE/BACKGROUND
// location are caller-ensured (the permission ladder); MissingPermission is suppressed deliberately.
class AndroidGeofenceOperationQueue {
  private val gate = Any()
  private var tail: Task<*>? = null
  private var generation = 0L

  private fun safe(action: () -> Task<Void>): Task<Void> =
    try { action() } catch (error: Exception) { Tasks.forException(error) }

  private fun enqueueLocked(action: () -> Task<Void>): Task<Void> {
    val operation = tail?.continueWithTask { safe(action) } ?: safe(action)
    // A failed Play Services task must not poison admission for the next replacement.
    tail = operation.continueWith { null }
    return operation
  }

  fun enqueueReplace(remove: () -> Task<Void>, add: () -> Task<Void>): Task<Void> = synchronized(gate) {
    val admittedGeneration = generation
    enqueueLocked {
      safe(remove).continueWithTask { removed ->
        when {
          !removed.isSuccessful ->
            Tasks.forException(removed.exception ?: IllegalStateException("Geofence removal failed"))
          synchronized(gate) { generation != admittedGeneration } ->
            TaskCompletionSource<Void>().apply { setResult(null) }.task
          else -> safe(add)
        }
      }
    }
  }

  fun enqueueRemove(remove: () -> Task<Void>): Task<Void> = synchronized(gate) {
    // Invalidate every admitted-but-not-yet-added replacement before queuing the final removal.
    generation += 1L
    enqueueLocked(remove)
  }
}

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
    operations.enqueueReplace(
      remove = { client.removeGeofences(pendingIntent()) },
      add = { client.addGeofences(request, pendingIntent()) },
    )
  }

  override fun deregisterAll() {
    operations.enqueueRemove { client.removeGeofences(pendingIntent()) }
  }

  /** Family cleanup is already off-main; await the serialized removal before tenant data is wiped. */
  fun deregisterAllAndAwait() {
    runCatching {
      Tasks.await(
        operations.enqueueRemove { client.removeGeofences(pendingIntent()) },
        GEOFENCE_CLEANUP_TIMEOUT_SECONDS,
        TimeUnit.SECONDS,
      )
    }
  }

  companion object {
    const val GEOFENCE_ACTION = "com.sloopworks.dayfold.GEOFENCE_EVENT"
    private const val GEOFENCE_CLEANUP_TIMEOUT_SECONDS = 5L
    private val operations = AndroidGeofenceOperationQueue()
  }
}

/** Android 12+ special-access truth and the OS-owned guidance screen. */
fun canScheduleExactNotifications(context: Context): Boolean =
  (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()

fun exactAlarmPermissionIntent(context: Context): Intent =
  Intent(
    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
    Uri.parse("package:${context.packageName}"),
  ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

fun exactReminderIdentityUri(subjectKey: String): Uri = Uri.Builder()
  .scheme("dayfold")
  .authority("exact-reminder")
  .appendPath(subjectKey)
  .build()

// Exact local notifications at known future instants (when.at / countdown / milestone) — NOT a periodic
// worker (Doze would miss the moment). setExactAndAllowWhileIdle fires the alarm; the receiver runs the
// pass (so the daily cap / quiet hours / dedup are honored at FIRE time, not schedule time).
class AndroidExactNotificationScheduler(private val context: Context) : ExactNotificationScheduler {
  private val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
  private val registry = context.getSharedPreferences(REGISTRY_PREFS, Context.MODE_PRIVATE)

  private fun pendingIntent(subjectKey: String): PendingIntent {
    val intent = Intent(EXACT_ALARM_ACTION).setPackage(context.packageName)
      .setData(exactReminderIdentityUri(subjectKey))
      .putExtra(EXTRA_SUBJECT_KEY, subjectKey)
    return PendingIntent.getBroadcast(
      context, 0, intent,
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
  }

  @SuppressLint("MissingPermission")
  override fun schedule(atIso: String, spec: NotificationSpec) {
    synchronized(REGISTRY_LOCK) {
      val scheduled = arm(atIso, spec)
      if (scheduled && !writeTracked(tracked() + spec.subjectKey)) {
        // Never leave an alarm the next family boundary cannot enumerate.
        runCatching { alarms.cancel(pendingIntent(spec.subjectKey)) }
      }
    }
  }

  /** Replacement reconciliation: add/replace desired alarms first, then cancel every tracked stale key. */
  internal fun replace(schedules: List<ExactSchedule>) {
    synchronized(REGISTRY_LOCK) {
      val previous = tracked()
      val desired = schedules.mapTo(linkedSetOf()) { it.spec.subjectKey }
      val accepted = schedules.filter { arm(it.atIso, it.spec) }
        .mapTo(linkedSetOf()) { it.spec.subjectKey }

      // Persist newly armed keys before any cancellation. If that durable registry write fails,
      // retract only the untracked additions and leave the previous plan intact/enumerable.
      val withAccepted = previous + accepted
      if (withAccepted != previous && !writeTracked(withAccepted)) {
        (accepted - previous).forEach { runCatching { alarms.cancel(pendingIntent(it)) } }
        return
      }

      // Desired-but-failed replacements retain their previous alarm; new failures remain absent.
      val stale = previous - desired
      stale.forEach { runCatching { alarms.cancel(pendingIntent(it)) } }
      // A failed final commit only over-tracks alarms already cancelled above, which is safe: the
      // next reconcile/identity cleanup can cancel them again and can never leave an orphan unknown.
      writeTracked(withAccepted - stale)
    }
  }

  @SuppressLint("MissingPermission")
  private fun arm(atIso: String, spec: NotificationSpec): Boolean {
    val atMillis = parseInstantFlexible(atIso, TimeZone.UTC)?.toEpochMilliseconds() ?: return false
    return runCatching {
      val alarm = pendingIntent(spec.subjectKey)
      if (alarms.canScheduleExactAlarms()) {
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, alarm)
      } else {
        // Permission denial must degrade to an approximate reminder, not silently drop it.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, alarm)
      }
    }.isSuccess
  }

  override fun cancel(subjectKey: String) {
    synchronized(REGISTRY_LOCK) {
      runCatching { alarms.cancel(pendingIntent(subjectKey)) }
      writeTracked(tracked() - subjectKey)
    }
  }

  /** AlarmManager cannot enumerate app alarms, so schedule/cancel maintain a tiny owned-key registry. */
  override fun cancelAll() {
    synchronized(REGISTRY_LOCK) {
      tracked().forEach { subjectKey -> runCatching { alarms.cancel(pendingIntent(subjectKey)) } }
      writeTracked(emptySet())
    }
  }

  private fun tracked(): Set<String> = registry.getStringSet(REGISTRY_SUBJECTS, emptySet())?.toSet().orEmpty()

  private fun writeTracked(subjects: Set<String>): Boolean {
    // commit is intentional: a process kill immediately after arming must not leave an alarm that
    // the next identity boundary cannot enumerate and cancel.
    return registry.edit().putStringSet(REGISTRY_SUBJECTS, subjects).commit()
  }

  companion object {
    const val EXACT_ALARM_ACTION = "com.sloopworks.dayfold.EXACT_ALARM"
    const val EXTRA_SUBJECT_KEY = "dayfold.subjectKey"
    private const val REGISTRY_PREFS = "dayfold.exact_notifications"
    private const val REGISTRY_SUBJECTS = "subject_keys"
    private val REGISTRY_LOCK = Any()
  }
}
