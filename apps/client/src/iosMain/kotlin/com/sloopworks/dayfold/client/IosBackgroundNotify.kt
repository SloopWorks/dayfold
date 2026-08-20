package com.sloopworks.dayfold.client

import io.ktor.client.HttpClient
import kotlin.concurrent.Volatile // multiplatform @Volatile (bare resolves to kotlin.jvm → fails on K/Native)
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import platform.Foundation.NSThread
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskInvalid
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_sync

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
private const val IOS_EXACT_REQUEST_PREFIX = "dayfold.exact:"
internal fun iosExactNotificationIdentifier(subjectKey: String) = "$IOS_EXACT_REQUEST_PREFIX$subjectKey"
internal data class IosExactRequestRecord(val identifier: String, val subjectKey: String)
internal data class IosExactRequestInventory(
  val pending: List<IosExactRequestRecord>,
  val delivered: List<IosExactRequestRecord>,
)
internal data class IosExactRemovalPlan(val pending: Set<String>, val delivered: Set<String>)

internal fun iosExactRemovalPlan(
  previous: IosExactRequestInventory,
  desiredIdentifiers: Set<String>,
  acceptedIdentifiers: Set<String>,
  activeSubjects: Set<String>,
  completedSubjects: Set<String> = emptySet(),
): IosExactRemovalPlan = IosExactRemovalPlan(
  pending = previous.pending.filterTo(linkedSetOf()) { prior ->
    val desiredId = iosExactNotificationIdentifier(prior.subjectKey)
    desiredId !in desiredIdentifiers ||
      (!prior.identifier.startsWith(IOS_EXACT_REQUEST_PREFIX) && desiredId in acceptedIdentifiers)
  }.mapTo(linkedSetOf()) { it.identifier },
  // Keep unread, still-relevant delivered notifications. Remove only stale/completed subjects and
  // legacy raw-ID history; explicit foreground visibility uses LocalNotifier.cancelDelivered.
  delivered = previous.delivered.filterTo(linkedSetOf()) { prior ->
    prior.subjectKey in completedSubjects || prior.subjectKey !in activeSubjects ||
      !prior.identifier.startsWith(IOS_EXACT_REQUEST_PREFIX)
  }.mapTo(linkedSetOf()) { it.identifier },
)

internal fun shouldPreserveAcceptedExact(
  expirationGeneration: Long?,
  currentOpenGeneration: Long?,
): Boolean = expirationGeneration != null && expirationGeneration == currentOpenGeneration

class IosExactNotificationScheduler : ExactNotificationScheduler {
  private val center get() = UNUserNotificationCenter.currentNotificationCenter()

  override fun schedule(atIso: String, spec: NotificationSpec) {
    schedule(atIso, spec) {}
  }

  internal fun schedule(atIso: String, spec: NotificationSpec, onComplete: (String?) -> Unit) {
    val at = parseInstantFlexible(atIso, TimeZone.UTC) ?: run {
      onComplete(null)
      return
    }
    val seconds = (at - Clock.System.now()).inWholeMilliseconds / 1000.0
    if (seconds <= 0.0) {
      onComplete(null)
      return
    }   // UNTimeIntervalNotificationTrigger requires a strictly-positive interval
    val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(seconds, repeats = false)
    val identifier = iosExactNotificationIdentifier(spec.subjectKey)
    val request = UNNotificationRequest.requestWithIdentifier(identifier, buildContent(spec), trigger)
    center.addNotificationRequest(request) { error -> onComplete(identifier.takeIf { error == null }) }
  }

  override fun cancel(subjectKey: String) {
    val identifier = iosExactNotificationIdentifier(subjectKey)
    center.removePendingNotificationRequestsWithIdentifiers(listOf(identifier))
    center.removeDeliveredNotificationsWithIdentifiers(listOf(identifier))
  }

  private fun exactRecord(request: UNNotificationRequest): IosExactRequestRecord? {
    val identifier = request.identifier
    if (identifier.startsWith(IOS_EXACT_REQUEST_PREFIX)) {
      return IosExactRequestRecord(identifier, identifier.removePrefix(IOS_EXACT_REQUEST_PREFIX))
    }
    // Migration from builds that used the raw subject key for exact requests. A nil-trigger
    // immediate post must not be swept; the non-repeating time trigger identifies the old exact lane.
    if (request.trigger is UNTimeIntervalNotificationTrigger) {
      return IosExactRequestRecord(identifier, identifier)
    }
    return null
  }

  internal fun exactInventory(onReady: (IosExactRequestInventory) -> Unit) {
    center.getPendingNotificationRequestsWithCompletionHandler { requests ->
      val pending = requests.orEmpty().mapNotNull { (it as? UNNotificationRequest)?.let(::exactRecord) }
      center.getDeliveredNotificationsWithCompletionHandler { notifications ->
        val delivered = notifications.orEmpty().mapNotNull {
          (it as? UNNotification)?.request?.let(::exactRecord)
        }
        onReady(IosExactRequestInventory(pending, delivered))
      }
    }
  }

  internal fun removePendingExactRequests(identifiers: Collection<String>) {
    if (identifiers.isEmpty()) return
    center.removePendingNotificationRequestsWithIdentifiers(identifiers.toList())
  }

  internal fun removeDeliveredExactRequests(identifiers: Collection<String>) {
    if (identifiers.isEmpty()) return
    center.removeDeliveredNotificationsWithIdentifiers(identifiers.toList())
  }

  // Queue an exact-only empty replacement. Family cleanup also calls LocalNotifier.cancelAll(), which
  // synchronously submits the broader all-lanes removal after closing admission.
  override fun cancelAll() = IosBackgroundNotificationPassCoordinator.submitExact(forceEmpty = true)
}

// Arm the exact local notifications for known future instants (planExactSchedules decides which + when).
// Re-arm is idempotent (same subject id → replaced). No-op when the feature is off. Called on enable +
// after a content sync. Because iOS can't re-run the pass at fire time, we apply the posture filters HERE
// (schedule time): cap/dedup are evaluated independently for each trigger's local date and non-urgent
// triggers inside quiet hours are skipped (urgent bypasses quiet, mirroring selectNotifications).
fun reconcileExactSchedules() {
  IosBackgroundNotificationPassCoordinator.submitExact()
}

// ── GEOFENCE (place) lane ──────────────────────────────────────────────────────────────────────────
// iOS may deliver several region events together. Planning those callbacks independently lets each read
// the same pre-post ledger and exceed the daily cap. It also lets the delegate return before permission,
// enqueue, and notification_log callbacks complete, at which point iOS may suspend the process. This
// process-global coordinator fixes both boundaries: one retained UIKit background task per admitted pass,
// strict snapshot -> post -> ledger serialization, and a family-generation token captured before the
// selected ContentStore is touched.
internal object IosBackgroundNotificationPassCoordinator {
  private data class Pass(
    val location: DeviceLocation?,
    val generation: Long,
    val exact: Boolean = false,
    val forceExactEmpty: Boolean = false,
    var backgroundTask: ULong = UIBackgroundTaskInvalid,
    var completed: Boolean = false,
    @Volatile var preserveAcceptedExactGeneration: Long? = null,
  )

  private val gate = SynchronizedObject()
  private val queued = mutableListOf<Pass>()
  private var active: Pass? = null

  fun submit(location: DeviceLocation?) {
    val generation = IosNotificationPostFence.begin() ?: return
    val pass = Pass(location, generation)
    enqueue(pass)
  }

  fun submitExact(forceEmpty: Boolean = false) {
    val generation = IosNotificationPostFence.begin() ?: return
    val pass = Pass(location = null, generation = generation, exact = true, forceExactEmpty = forceEmpty)
    enqueue(pass)
  }

  private fun enqueue(pass: Pass) {
    pass.backgroundTask = beginBackgroundTask { expire(pass) }

    val startNow = synchronized(gate) {
      if (active == null) {
        active = pass
        true
      } else {
        queued += pass
        false
      }
    }
    if (startNow) start(pass)
  }

  /** End retained work immediately at an identity boundary; stale callbacks become harmless no-ops. */
  fun cancelAll() {
    val cancelledTaskIds = synchronized(gate) {
      val passes = buildList {
        active?.let(::add)
        addAll(queued)
      }
      val identifiers = passes.map { pass ->
        pass.completed = true
        pass.backgroundTask.also { pass.backgroundTask = UIBackgroundTaskInvalid }
      }
      active = null
      queued.clear()
      identifiers
    }
    cancelledTaskIds.forEach(::endBackgroundTask)
  }

  private fun start(pass: Pass) {
    if (!IosNotificationPostFence.isCurrent(pass.generation)) {
      finish(pass)
      return
    }
    if (pass.exact) startExact(pass) else startImmediate(pass)
  }

  private fun startImmediate(pass: Pass) {
    try {
      // The fence was captured before this read. A family teardown either waits for a current post's
      // ledger commit or invalidates this pass before it can enqueue anything for the outgoing cache.
      val cs = IosNotificationContentStoreHolder.get()
      val nowIso = Clock.System.now().toString()
      val runner = BackgroundNotificationRunner(
        IosLocalNotifier(admittedGeneration = pass.generation),
        cs::logNotification,
      )
      runner.run(
        cs.notifSnapshot(),
        nowIso,
        pass.location,
        TimeZone.currentSystemDefault(),
        onComplete = { finish(pass) },
      )
    } catch (error: Throwable) {
      Log.e("notifications", error) { "iOS background notification pass failed" }
      finish(pass)
    }
  }

  private fun startExact(pass: Pass) {
    try {
      val cs = IosNotificationContentStoreHolder.get()
      val config = cs.notifConfig()
      val snapshot = cs.notifSnapshot()
      val zone = TimeZone.currentSystemDefault()
      val nowIso = Clock.System.now().toString()
      val desiredSchedules = if (pass.forceExactEmpty || !config.enabled) emptyList() else {
        selectIosExactSchedules(
          schedules = planExactSchedules(snapshot, nowIso, zone),
          config = config,
          log = snapshot.log,
          zone = zone,
        )
      }
      val completedSubjects = completedNotificationSubjects(snapshot)
      val activeSubjects = if (pass.forceExactEmpty) emptySet() else {
        activeExactNotificationSubjects(snapshot, nowIso, zone)
      }
      val scheduler = IosExactNotificationScheduler()
      scheduler.exactInventory { previous ->
        try {
          if (!IosNotificationPostFence.isCurrent(pass.generation)) finish(pass)
          else enqueueExactSequentially(
            pass, scheduler, desiredSchedules, previous, activeSubjects, completedSubjects, 0, linkedSetOf(),
          )
        } catch (error: Throwable) {
          Log.e("notifications", error) { "iOS exact notification reconciliation failed" }
          finish(pass)
        }
      }
    } catch (error: Throwable) {
      Log.e("notifications", error) { "iOS exact notification planning failed" }
      finish(pass)
    }
  }

  private fun enqueueExactSequentially(
    pass: Pass,
    scheduler: IosExactNotificationScheduler,
    schedules: List<ExactSchedule>,
    previous: IosExactRequestInventory,
    activeSubjects: Set<String>,
    completedSubjects: Set<String>,
    index: Int,
    accepted: LinkedHashSet<String>,
  ) {
    if (!IosNotificationPostFence.isCurrent(pass.generation)) {
      finishStaleExact(pass, scheduler, accepted)
      return
    }
    if (index == schedules.size) {
      val desiredIdentifiers = schedules.mapTo(linkedSetOf()) {
        iosExactNotificationIdentifier(it.spec.subjectKey)
      }
      val finalized = IosNotificationPostFence.completeIfCurrent(pass.generation) {
        // Desired stable identifiers were added/replaced first. Remove only stale prior requests;
        // if one add failed, preserve that subject's previous request as the last known-good plan.
        val removals = iosExactRemovalPlan(
          previous, desiredIdentifiers, accepted, activeSubjects, completedSubjects,
        )
        scheduler.removePendingExactRequests(removals.pending)
        scheduler.removeDeliveredExactRequests(removals.delivered)
        completedSubjects.forEach { IosLocalNotifier(pass.generation).cancelWithoutInvalidating(it) }
      }
      if (finalized) finish(pass) else finishStaleExact(pass, scheduler, accepted)
      return
    }
    val schedule = schedules[index]
    scheduler.schedule(schedule.atIso, schedule.spec) { identifier ->
      if (identifier != null) accepted += identifier
      if (!IosNotificationPostFence.isCurrent(pass.generation)) {
        finishStaleExact(pass, scheduler, accepted)
      } else {
        enqueueExactSequentially(
          pass, scheduler, schedules, previous, activeSubjects, completedSubjects, index + 1, accepted,
        )
      }
    }
  }

  private fun finishStaleExact(
    pass: Pass,
    scheduler: IosExactNotificationScheduler,
    accepted: Set<String>,
  ) {
    // A UIKit time-window expiration is not an identity change: each already-accepted exact request
    // is a valid member of the current plan, so keep it rather than turning a partial replacement
    // into lost reminders. Family teardown closes admission, which always retracts outgoing requests.
    if (!shouldPreserveAcceptedExact(
        pass.preserveAcceptedExactGeneration,
        IosNotificationPostFence.currentOpenGeneration(),
      )) {
      scheduler.removePendingExactRequests(accepted)
      scheduler.removeDeliveredExactRequests(accepted)
    }
    finish(pass)
  }

  private fun finish(pass: Pass) {
    val finished = synchronized(gate) {
      if (pass.completed) return@synchronized null
      pass.completed = true
      val next = if (active === pass) {
        active = null
        queued.removeFirstOrNull()?.also { active = it }
      } else {
        queued.remove(pass)
        null
      }
      val identifier = pass.backgroundTask
      pass.backgroundTask = UIBackgroundTaskInvalid
      identifier to next
    } ?: return
    endBackgroundTask(finished.first)
    // A notifier completes while the post-generation gate is held. Defer the next pass until that
    // callback unwinds; otherwise starting it here would recursively acquire that gate on K/Native.
    finished.second?.let { dispatch_async(dispatch_get_main_queue()) { start(it) } }
  }

  private fun expire(pass: Pass) {
    // Mark every exact pass this expiration cancels before publishing the new fence generation.
    // A queued task can expire while an exact pass is active, so preserving only [pass] loses valid
    // accepted requests. Detach the whole affected set under the same atomic invalidation; a fresh
    // pass admitted afterward is therefore never swept by this older expiration.
    val cancelledTaskIds = mutableListOf<ULong>()
    val invalidated = IosNotificationPostFence.invalidateIf { expirationGeneration ->
      synchronized(gate) {
        val live = !pass.completed && (active === pass || pass in queued)
        if (!live) return@synchronized false
        val affected = buildList {
          active?.let(::add)
          addAll(queued)
        }
        affected.filter { it.exact && !it.completed }.forEach {
          it.preserveAcceptedExactGeneration = expirationGeneration
        }
        affected.forEach {
          it.completed = true
          cancelledTaskIds += it.backgroundTask
          it.backgroundTask = UIBackgroundTaskInvalid
        }
        active = null
        queued.clear()
        true
      }
    }
    if (invalidated != null) cancelledTaskIds.forEach(::endBackgroundTask)
  }

  private fun beginBackgroundTask(onExpire: () -> Unit): ULong {
    var identifier = UIBackgroundTaskInvalid
    onMain {
      identifier = UIApplication.sharedApplication.beginBackgroundTaskWithExpirationHandler(onExpire)
    }
    return identifier
  }

  private fun endBackgroundTask(identifier: ULong) {
    if (identifier == UIBackgroundTaskInvalid) return
    if (NSThread.isMainThread()) UIApplication.sharedApplication.endBackgroundTask(identifier)
    else dispatch_async(dispatch_get_main_queue()) {
      UIApplication.sharedApplication.endBackgroundTask(identifier)
    }
  }

  private inline fun onMain(crossinline block: () -> Unit) {
    if (NSThread.isMainThread()) block()
    else dispatch_sync(dispatch_get_main_queue()) { block() }
  }
}

// The single entry point for a region-enter callback: retain the background execution window, run the
// shared pass (nowFeed + selectNotifications — no engine fork), post via UNUserNotificationCenter, and
// write notification_log before releasing it. [location] is the arrived place coordinate; it never
// persists. Work is intentionally asynchronous and serialized by the coordinator above.
fun runBackgroundNotificationPass(location: DeviceLocation?) {
  IosBackgroundNotificationPassCoordinator.submit(location)
}

// Resolve a triggering region's id back to its saved-place coordinate — region-enter delivers an
// identifier, not a location, so the coord comes from the synced places (the arrived place is the "live
// position" proxy for the pass). Null if the id is unknown (place de-synced). Mirrors Android.
fun placeLocationForRegion(regionId: String): DeviceLocation? =
  IosNotificationContentStoreHolder.get().activePlaces().firstOrNull { it.id == regionId }
    ?.let { DeviceLocation(it.lat, it.lng) }

// (Re)register the geofences from the saved places (enable / place change / app foreground). ≤ the iOS
// 20-region cap: register directly. > cap: take a one-shot location fix and register the nearest-N
// (registerNearest). No-op / deregister when the feature is off. Mirrors Android's reRegisterGeofences.
fun reRegisterGeofences() {
  val cs = IosNotificationContentStoreHolder.get()
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

// The reconcile half of a background wake, called by [bgRefresh] (ADR 0020 R3) — it was the whole BGTask
// handler before the refresh lane existed. Neither notification lane needs a BGTask to DELIVER (region
// monitoring wakes the app; scheduled local notifs fire directly); this just keeps the region set + the
// exact schedules fresh opportunistically. Both halves are mandatory and must stay in step with Android's
// reconcile lambda (reRegisterGeofences + reconcileExactSchedules) — dropping either silently regresses
// ADR 0044: geofences go stale as places sync in, or freshly-synced timed triggers never get armed.
fun bgReconcile() {
  reRegisterGeofences()
  reconcileExactSchedules()
}

// ── BGAppRefreshTask entry — sync + reconcile ──────────────────────────────────────────────────────
// ADR 0020 R3 — the iOS BGAppRefreshTask entry point. Bounded at 25s, comfortably inside the
// ~30s the system grants, so the expiration handler (bgCancelRefresh, below) is a backstop rather
// than the normal exit. It CONTAINS the old reconcile-only lane (it calls bgReconcile on every exit
// path, including the skip) rather than replacing it; the difference is that this one is genuinely
// asynchronous (network I/O), which is exactly why the Swift side must complete the BGTask from
// inside [onComplete], never right after this call returns — see the doc there.
private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
// @Volatile — review round 2: written on the BGTask handler's thread, read by bgCancelRefresh()
// from iOS's expiration-handler thread (a different one). Kotlin/Native gives no visibility
// guarantee across threads without this; a stale read here would make cancel() a silent no-op,
// letting the coroutine keep running past the task's expiration.
@Volatile private var refreshJob: Job? = null

// Real API base is unset on-device this slice (iOS run config is operator-gated on Mac/Xcode) —
// mirrors the literal MainViewController already uses for the foreground graph. No config holder
// exists yet because iOS has exactly one hardcoded value, unlike Android's runtime-set
// AndroidApiConfigHolder.
private const val IOS_API_BASE = ""

/**
 * [onComplete] is invoked on the last line of the pass — the Swift side must call
 * `task.setTaskCompleted` only from there, never immediately after this returns. Calling it early
 * would let iOS suspend the app mid-work (silently — no error, no log) and, worse, it teaches iOS
 * that this task's work can be killed safely, which reduces how often it gets scheduled again.
 *
 * [onComplete] runs UNCONDITIONALLY on every path — success, any thrown exception (including
 * `IosNotificationContentStoreHolder.get()` throwing, a SQLite driver-creation failure;
 * `deps.memberships()`/
 * `deps.session()` throwing, Keychain/NSUserDefaults reads that [backgroundRefreshPass] does not
 * wrap in `runCatching`; or `deps.reconcile()` throwing), and cancellation ([bgCancelRefresh],
 * called from the BGTask's `expirationHandler`). All of that construction and every suspend call
 * now runs INSIDE the launched coroutine, wrapped by a single outer `try`/`catch`/`finally` — a
 * structural guarantee rather than one `runCatching` per call site, which would need to be
 * re-added correctly at every future call site added here.
 *
 * Review round 2 — an earlier version tried to skip [onComplete] specifically on the cancellation
 * path (`expirationHandler` already calls `setTaskCompleted(success: false)` itself, so calling
 * this too looked like a double completion) by checking `isActive` in the `finally` block. That
 * check races `expirationHandler` on a DIFFERENT thread with no synchronization between them —
 * `isActive` can read `true` right as expiration fires elsewhere, and [onComplete] would then call
 * `task.setTaskCompleted(success: true)` after the handler's `success: false`, which crashes.
 * There is no reliable single-threaded signal here to gate on. The robust fix lives on the Swift
 * side instead (`App.swift`'s `TaskCompletionGuard`): an atomic already-completed flag around
 * `task.setTaskCompleted` makes whichever caller (this callback or `expirationHandler`) arrives
 * first win and the other a no-op — which is what makes calling [onComplete] unconditionally,
 * from every path including cancellation, safe.
 */
fun bgRefresh(onComplete: () -> Unit) {
  refreshJob = refreshScope.launch {
    try {
      val cs = IosNotificationContentStoreHolder.get()
      val delegate = RuntimeHandleHolder.get()
      val outcome = if (delegate == null && IOS_API_BASE.isBlank()) {
        // No live runtime to delegate to, and no configured API base for a direct sync: ktor
        // resolves a schemeless/empty base against its own http://localhost default, so building
        // a SyncClient here would fire a real (and always-broken, on-device) request every wake,
        // then have it swallowed by headlessSync's own runCatching as an indistinguishable
        // "sync failed" — not the honest, clearly-labelled skip this state actually is.
        // backlog/next.md's TASK-SYNC REMAINING already tracks "iOS sync-config plumbing (the
        // BuildConfig analogue)" as the open gap; until it lands, reconcile is still local and
        // cheap, so it still runs — mirrors backgroundRefreshPass's own no-family/no-session
        // skip shape.
        bgReconcile()
        RefreshOutcome(reconciled = true, skippedReason = "no-ios-api-base")
      } else {
        val http = HttpClient()
        try {
          // headlessRefreshPass, NOT backgroundRefreshPass: this task is a cache WRITER, so it
          // must heal an older-schema cache before it stamps the running build's version over it.
          // See that function's doc for the overnight-app-update failure this ordering prevents.
          headlessRefreshPass(
            contentStore = cs,
            databaseDispatcher = Dispatchers.Default,
            deps = RefreshDeps(
              memberships = { cs.cachedMemberships() },
              session = { IosTokenStore().load() },
              // A live runtime (MainViewController's composition) owns the one refresh-token use
              // for this wake when one is retained — see RuntimeHandleHolder's doc for why a
              // second, independent refresher here would trip the server's reuse detection.
              delegateToRuntime = delegate,
              syncOnce = { familyId, session ->
                headlessSync(
                  contentStore = cs,
                  syncClient = SyncClient(IOS_API_BASE, http),
                  databaseDispatcher = Dispatchers.Default,
                  familyId = familyId,
                  session = session,
                  refreshAccess = { refresh ->
                    headlessRefreshAccess(AuthClient(IOS_API_BASE, http), IosTokenStore(), refresh)
                  },
                  nowIso = { Clock.System.now().toString() },
                )
              },
              // bgReconcile(), not reconcileExactSchedules() alone — the refresh lane must keep
              // doing everything the reconcile-only BGTask lane did before it (ADR 0044's
              // opportunistic geofence re-registration), and it must match Android's reconcile.
              reconcile = { bgReconcile() },
            ),
            budget = 25.seconds,
          )
        } finally {
          http.close()
        }
      }
      Log.i("refresh") { "background pass: $outcome" }
    } catch (c: CancellationException) {
      throw c
    } catch (t: Throwable) {
      Log.e("refresh") { "background pass threw: $t" }
    } finally {
      // Unconditional — see the doc above for why the previous isActive-gated version was unsafe.
      onComplete()
    }
  }
}

/** Called from the BGTask expirationHandler — stop immediately so iOS is not forced to kill us. */
fun bgCancelRefresh() {
  refreshJob?.cancel()
}
