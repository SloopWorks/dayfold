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

class IosLocalNotifier : LocalNotifier {
  private val center get() = UNUserNotificationCenter.currentNotificationCenter()

  // iOS has no notification "channel"; categories/actions are optional. No-op keeps the seam parity
  // (a body-tap deep-link needs no category; an explicit "Open" action is a future enhancement).
  override fun ensureChannel() {}

  override fun postGroup(specs: List<NotificationSpec>) {
    if (specs.isEmpty()) return
    specs.forEach { spec ->
      // trigger = null → deliver immediately (the geofence/BGTask pass posts "now"). Stable id =
      // subjectKey so a re-post replaces rather than stacks, and cancel() can target it.
      val request = UNNotificationRequest.requestWithIdentifier(spec.subjectKey, buildContent(spec), null)
      center.addNotificationRequest(request, withCompletionHandler = null)
    }
  }

  override fun cancel(subjectKey: String) {
    center.removePendingNotificationRequestsWithIdentifiers(listOf(subjectKey))
    center.removeDeliveredNotificationsWithIdentifiers(listOf(subjectKey))
  }

  override fun cancelAll() {
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
internal fun requestNotificationAuthorization() {
  UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(
    UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge,
  ) { _, _ -> }
}
