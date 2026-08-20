package com.sloopworks.dayfold.client

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import platform.Foundation.NSString
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationPresentationOptionBanner
import platform.UserNotifications.UNNotificationPresentationOptionList
import platform.UserNotifications.UNNotificationPresentationOptionSound
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationResponse
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject

// ADR 0044 Phase B — the iOS LOCAL notifier (LocalNotifier impl), mirroring AndroidLocalNotifier.
// UNUserNotificationCenter only — NO FCM/APNs (dumb-server invariant): the headless pass posts these
// on-device. iOS groups automatically by threadIdentifier, so — unlike Android — there is NO explicit
// group-summary notification (do not port AndroidLocalNotifier.buildSummary). The honest on-device
// subtext rides in as the notification subtitle; the deep-link target rides in userInfo; a tapped
// notification routes through IosDeepLinkBus → hubEngine.openHub (mirror MainActivity's extras path).

// userInfo keys for the deep-link target carried into the notification (read back on tap).
private const val UI_HUB_ID = "dayfold.hubId"
private const val UI_SECTION_ID = "dayfold.sectionId"
private const val UI_BLOCK_ID = "dayfold.blockId"
private const val UI_SUBJECT_KEY = "dayfold.subjectKey"

/**
 * Process-wide fence for asynchronous UN permission/request callbacks.
 *
 * Every IosLocalNotifier instance shares this generation. Done cancellation and identity teardown
 * invalidate it before removing requests. [completeIfCurrent] holds the same gate while the runner
 * records its ledger entries, so family cleanup cannot wipe the cache and then admit an outgoing-family
 * record. A callback that lost the generation retracts every request accepted by its stale batch.
 */
internal object IosNotificationPostFence {
  private val gate = SynchronizedObject()
  private var generation = 0L
  private var admissionOpen = true

  fun begin(): Long? = synchronized(gate) { generation.takeIf { admissionOpen } }
  fun currentOpenGeneration(): Long? = synchronized(gate) { generation.takeIf { admissionOpen } }
  fun isCurrent(token: Long): Boolean = synchronized(gate) { admissionOpen && generation == token }
  fun invalidate(): Long = synchronized(gate) { generation += 1L; generation }
  /**
   * Invalidate only after [prepare] has made every dependent state change while this gate is held.
   * A completion callback therefore observes either the old generation and the old state, or the
   * new generation and fully-prepared invalidation state; it can never land in between them.
   */
  fun invalidateIf(prepare: (nextGeneration: Long) -> Boolean): Long? = synchronized(gate) {
    val next = generation + 1L
    if (!prepare(next)) return@synchronized null
    generation = next
    next
  }
  fun closeAdmission() = synchronized(gate) {
    generation += 1L
    admissionOpen = false
  }
  fun openAdmission() = synchronized(gate) {
    generation += 1L
    admissionOpen = true
  }

  fun completeIfCurrent(token: Long, complete: () -> Unit): Boolean = synchronized(gate) {
    if (!admissionOpen || generation != token) return@synchronized false
    complete()
    true
  }
}

/** Foreground visibility invalidates only immediate posts, leaving future exact work untouched. */
internal object IosImmediateNotificationFence {
  private val gate = SynchronizedObject()
  private var generation = 0L
  fun begin(): Long = synchronized(gate) { generation }
  fun isCurrent(token: Long): Boolean = synchronized(gate) { generation == token }
  fun invalidate() = synchronized(gate) { generation += 1L }
  fun completeIfCurrent(token: Long, complete: () -> Unit): Boolean = synchronized(gate) {
    if (generation != token) return@synchronized false
    complete()
    true
  }
}

/**
 * Opaque replay item used to claim one notification tap across controller replacement.
 */
class IosDeepLinkTap internal constructor(internal val target: DeepLinkTarget)

/**
 * Thread-safe latest-tap replay with explicit claim and admitted-commit acknowledgement.
 *
 * A controller claim prevents simultaneous delivery. Disposal releases only after the controller's
 * runtime admission closes, so an uncommitted tap can be claimed by the replacement controller.
 */
internal class IosDeepLinkReplay {
  private val gate = SynchronizedObject()
  private val mutableTaps = MutableStateFlow<IosDeepLinkTap?>(null)
  private var pending: IosDeepLinkTap? = null
  private var owner: Any? = null

  val taps: Flow<IosDeepLinkTap> = mutableTaps.filterNotNull()

  fun emit(target: DeepLinkTarget) {
    synchronized(gate) {
      val tap = IosDeepLinkTap(target)
      pending = tap
      owner = null
      mutableTaps.value = tap
    }
  }

  fun claim(tap: IosDeepLinkTap, candidate: Any): DeepLinkTarget? = synchronized(gate) {
    if (pending !== tap || (owner != null && owner !== candidate)) return@synchronized null
    owner = candidate
    tap.target
  }

  fun acknowledge(tap: IosDeepLinkTap, candidate: Any) {
    synchronized(gate) {
      if (pending !== tap || owner !== candidate) return@synchronized
      pending = null
      owner = null
      mutableTaps.value = null
    }
  }

  fun release(candidate: Any) {
    synchronized(gate) {
      if (owner === candidate) owner = null
    }
  }
}

/** Process-global notification-tap replay retained independently of a UIKit controller. */
object IosDeepLinkBus {
  private val replay = IosDeepLinkReplay()

  val taps: Flow<IosDeepLinkTap> get() = replay.taps

  fun emit(target: DeepLinkTarget) = replay.emit(target)
  fun claim(tap: IosDeepLinkTap, owner: Any): DeepLinkTarget? = replay.claim(tap, owner)
  fun acknowledge(tap: IosDeepLinkTap, owner: Any) = replay.acknowledge(tap, owner)
  fun release(owner: Any) = replay.release(owner)
}

internal fun buildContent(spec: NotificationSpec): UNMutableNotificationContent =
  UNMutableNotificationContent().apply {
    setTitle(spec.title)
    setBody(spec.body)
    setSubtitle(spec.subtext)              // the on-device honesty line ("Matched on your device" …)
    setThreadIdentifier(spec.group)        // OS-rendered grouping (no explicit summary needed on iOS)
    setSound(UNNotificationSound.defaultSound)
    setUserInfo(buildMap<Any?, Any?> {
      spec.target?.let {
        put(UI_HUB_ID, it.hubId)
        it.sectionId?.let { s -> put(UI_SECTION_ID, s) }
        it.blockId?.let { b -> put(UI_BLOCK_ID, b) }
      }
      put(UI_SUBJECT_KEY, spec.subjectKey)
    })
  }

class IosLocalNotifier private constructor(
  private val admittedGeneration: Long? = null,
  @Suppress("UNUSED_PARAMETER") marker: Unit,
) : LocalNotifier {
  /** Public platform seam used by the UI host for ordinary Done cancellation. */
  constructor() : this(null, Unit)

  /** A coordinator-owned notifier whose own stale-subject cleanup retains the admitted pass token. */
  internal constructor(admittedGeneration: Long) : this(admittedGeneration, Unit)

  private val center get() = UNUserNotificationCenter.currentNotificationCenter()

  // iOS has no notification "channel"; categories/actions are optional. No-op keeps the seam parity
  // (a body-tap deep-link needs no category; an explicit "Open" action is a future enhancement).
  override fun ensureChannel() {}

  override fun postGroup(specs: List<NotificationSpec>, onAccepted: (Set<String>) -> Unit) {
    if (specs.isEmpty()) {
      onAccepted(emptySet())
      return
    }
    val generation = admittedGeneration ?: IosNotificationPostFence.begin()
    if (generation == null) {
      onAccepted(emptySet())
      return
    }
    val immediateGeneration = IosImmediateNotificationFence.begin()

    // Never gate a background wake on the permission controller's launch-time cache. A cold region
    // callback can arrive before start()'s asynchronous refresh completes. Read current UN settings for
    // this pass, then count only requests whose enqueue completion reports success.
    IosNotifGlue.notificationPermission.readFresh { permission ->
      if (permission != NotificationPermission.Granted) {
        onAccepted(emptySet())
      } else {
        enqueueSequentially(
          specs,
          index = 0,
          accepted = linkedSetOf(),
          generation = generation,
          immediateGeneration = immediateGeneration,
          onAccepted = onAccepted,
        )
      }
    }
  }

  private fun enqueueSequentially(
    specs: List<NotificationSpec>,
    index: Int,
    accepted: LinkedHashSet<String>,
    generation: Long,
    immediateGeneration: Long,
    onAccepted: (Set<String>) -> Unit,
  ) {
    if (!IosNotificationPostFence.isCurrent(generation) ||
      !IosImmediateNotificationFence.isCurrent(immediateGeneration)) {
      finishStale(accepted, onAccepted)
      return
    }
    if (index == specs.size) {
      // The callback writes notification_log synchronously while the generation gate is held. Family
      // teardown must invalidate this fence before wiping the cache, so either the outgoing record
      // finishes first and is then cleared, or it is rejected here and can never cross the boundary.
      // Hold the immediate gate around the nested family completion too: foreground visibility cannot
      // retract this batch between the last current-check and its durable ledger acknowledgement.
      var familyCompleted = false
      val immediateCompleted = IosImmediateNotificationFence.completeIfCurrent(immediateGeneration) {
        familyCompleted = IosNotificationPostFence.completeIfCurrent(generation) {
          onAccepted(accepted)
        }
      }
      if (!immediateCompleted || !familyCompleted) {
        finishStale(accepted, onAccepted)
      }
      return
    }
    val spec = specs[index]
    // trigger = null → deliver immediately (the geofence/BGTask pass posts "now"). Stable id =
    // subjectKey so a re-post replaces rather than stacks, and cancel() can target it.
    val request = UNNotificationRequest.requestWithIdentifier(spec.subjectKey, buildContent(spec), null)
    center.addNotificationRequest(request) { error ->
      if (error == null) accepted += spec.subjectKey
      if (!IosNotificationPostFence.isCurrent(generation) ||
        !IosImmediateNotificationFence.isCurrent(immediateGeneration)) {
        finishStale(accepted, onAccepted)
      } else {
        enqueueSequentially(
          specs, index + 1, accepted, generation, immediateGeneration, onAccepted,
        )
      }
    }
  }

  private fun finishStale(accepted: Set<String>, onAccepted: (Set<String>) -> Unit) {
    // Invalidation can race between the pre-enqueue check and Notification Center accepting a request.
    // Teardown's earlier removeAll may therefore miss that late request; retract it explicitly here.
    accepted.forEach { subjectKey ->
      center.removePendingNotificationRequestsWithIdentifiers(listOf(subjectKey))
      center.removeDeliveredNotificationsWithIdentifiers(listOf(subjectKey))
    }
    onAccepted(emptySet())
  }

  override fun cancel(subjectKey: String) {
    // A UI/runtime Done must invalidate an already-planned asynchronous pass. A coordinator-admitted
    // pass is cancelling stale completed subjects from its own snapshot and must retain its generation.
    if (admittedGeneration == null) {
      IosNotificationPostFence.invalidate()
      IosImmediateNotificationFence.invalidate()
    }
    cancelWithoutInvalidating(subjectKey)
  }

  override fun cancelDelivered(subjectKey: String) {
    // Foreground visibility means the user has seen the current banner. Remove an immediate request
    // that has not yet presented and all delivered forms, but leave the prefixed future exact request
    // armed. This path intentionally does not invalidate exact/background reconciliation.
    IosImmediateNotificationFence.invalidate()
    center.removePendingNotificationRequestsWithIdentifiers(listOf(subjectKey))
    center.removeDeliveredNotificationsWithIdentifiers(
      listOf(subjectKey, iosExactNotificationIdentifier(subjectKey)),
    )
  }

  internal fun cancelWithoutInvalidating(subjectKey: String) {
    val identifiers = listOf(subjectKey, iosExactNotificationIdentifier(subjectKey))
    center.removePendingNotificationRequestsWithIdentifiers(identifiers)
    center.removeDeliveredNotificationsWithIdentifiers(identifiers)
  }

  override fun cancelAll() {
    IosNotificationPostFence.invalidate()
    IosImmediateNotificationFence.invalidate()
    center.removeAllPendingNotificationRequests()
    center.removeAllDeliveredNotifications()
  }
}

// The process-global UN delegate — foreground presentation + tap routing. MUST be retained for the app
// lifetime (UNUserNotificationCenter.delegate is weak); IosNotifGlue holds the single instance.
class IosUNDelegate : NSObject(), UNUserNotificationCenterDelegateProtocol {
  // Foreground: without this, iOS suppresses the banner while the app is in front. Return banner+list+sound.
  override fun userNotificationCenter(
    center: UNUserNotificationCenter,
    willPresentNotification: UNNotification,
    withCompletionHandler: (UNNotificationPresentationOptions) -> Unit,
  ) {
    withCompletionHandler(
      UNNotificationPresentationOptionBanner or UNNotificationPresentationOptionList or UNNotificationPresentationOptionSound,
    )
  }

  // Tap: parse the deep-link target from userInfo → emit on the bus (tolerates a dangling target;
  // openHub falls back to the feed). Then release the OS with the completion handler.
  override fun userNotificationCenter(
    center: UNUserNotificationCenter,
    didReceiveNotificationResponse: UNNotificationResponse,
    withCompletionHandler: () -> Unit,
  ) {
    val info = didReceiveNotificationResponse.notification.request.content.userInfo
    (info[UI_HUB_ID] as? String)?.let { hubId ->
      IosDeepLinkBus.emit(
        DeepLinkTarget(
          hubId = hubId,
          sectionId = info[UI_SECTION_ID] as? String,
          blockId = info[UI_BLOCK_ID] as? String,
        ),
      )
    }
    withCompletionHandler()
  }
}

// Notification authorization request — used by the permission ladder (formal controller lands in S4).
// [.alert,.sound,.badge]; the completion is fire-and-forget (state is re-read via getNotificationSettings).
internal fun requestNotificationAuthorization(onComplete: () -> Unit = {}) {
  UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(
    UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge,
  ) { _, _ -> onComplete() }
}
