package com.sloopworks.dayfold.client

import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import platform.UserNotifications.UNUserNotificationCenter

// ADR 0044 Phase B — the process-global notification glue for iOS. Mirrors the pieces MainActivity keeps
// alive on Android, but only the store-INDEPENDENT ones live here (the redux store + engines stay in
// MainViewController — on iOS the single root view controller is already app-lifetime, so there is no
// store-lifetime reason to hoist them; see the plan's "host stays light" decision). What MUST be
// process-global: the CL/UN delegates + controllers (their OS .delegate refs are weak → a local delegate
// deallocates and callbacks silently stop) and the shared ContentStore. Swift calls IosNotifGlue.shared.*
// from AppDelegate.didFinishLaunching (main thread). Geofence controller + BGTask wiring land in S3.
object IosNotifGlue {
  val localNotifier = IosLocalNotifier()
  val unDelegate = IosUNDelegate()

  private var started = false

  // Called once from AppDelegate.didFinishLaunching on the MAIN THREAD. Idempotent. Sets the UN delegate
  // (retained by this object), warms the shared ContentStore so the later background fetch sees a
  // non-null instance, and requests notification authorization (formal ladder controller arrives in S4).
  fun start() {
    if (started) return
    started = true
    UNUserNotificationCenter.currentNotificationCenter().setDelegate(unDelegate)
    IosContentStoreHolder.get()
    requestNotificationAuthorization()
  }

  // S1 verification scaffold — posts a two-item group so the notifier can be exercised before the real
  // callers exist (the geofence pass in S3 / the scheduler in S2). Removed once those lanes are wired.
  fun debugTestPost() {
    localNotifier.postGroup(
      listOf(
        NotificationSpec(
          subjectKey = "debug:soccer",
          title = "Rain at soccer 4pm",
          body = "Pack jackets — showers expected right at pickup.",
          subtext = "Matched on your device",
          target = DeepLinkTarget(hubId = "hub-demo"),
          urgent = true,
        ),
        NotificationSpec(
          subjectKey = "debug:rsvp",
          title = "School RSVP due Thursday",
          body = "The field-trip form needs a reply by Thursday.",
          subtext = "From your email",
          target = DeepLinkTarget(hubId = "hub-demo"),
          urgent = false,
        ),
      ),
    )
  }

  // S2 verification scaffold — arms an exact local notification ~15s out via the time-lane scheduler
  // (UNTimeIntervalNotificationTrigger). Removed once the real reconcileExactSchedules path is exercised
  // through the settings toggle (S4).
  fun debugScheduleTest() {
    IosExactNotificationScheduler().schedule(
      (Clock.System.now() + 15.seconds).toString(),
      NotificationSpec(
        subjectKey = "debug:sched",
        title = "Field-trip form due Thursday",
        body = "Reply to the school before the Thursday cutoff.",
        subtext = "From your email",
        target = DeepLinkTarget(hubId = "hub-demo"),
        urgent = false,
      ),
    )
  }
}
