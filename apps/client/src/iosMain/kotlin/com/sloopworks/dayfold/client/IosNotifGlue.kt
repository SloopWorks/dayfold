package com.sloopworks.dayfold.client

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
  // Process-global controllers (each owns a CLLocationManager / UN center; retained here for the app
  // lifetime — their OS .delegate refs are weak). Lazily created on first access, which is
  // IosNotifGlue.start() on the MAIN THREAD (CLLocationManager must be created on the thread whose run
  // loop delivers its callbacks). Do not touch before start().
  val geofence = IosGeofenceController()
  val locationPermission = IosLocationPermissionController()
  val notificationPermission = IosNotificationPermissionController()

  private var started = false

  // Called once from AppDelegate.didFinishLaunching on the MAIN THREAD. Idempotent. Sets the UN delegate
  // (retained by this object), warms the shared ContentStore so the later background fetch sees a
  // non-null instance, and requests notification authorization (formal ladder controller arrives in S4).
  fun start() {
    if (started) return
    started = true
    UNUserNotificationCenter.currentNotificationCenter().setDelegate(unDelegate)
    // Force creation on the main thread (each CLLocationManager wires its delegate in init).
    geofence; locationPermission; notificationPermission
    notificationPermission.refresh()   // async re-read of OS auth into its flow
    IosContentStoreHolder.get()
  }

  // Verification scaffold — drive the full enable path without navigating the settings UI: request the
  // permission ladder (notifications + location Always) and enable device-local proximity. The
  // notifConfigFlow reaction in MainViewController then registers geofences + reconciles exact schedules
  // (same code the real settings toggle runs). Removed at S5 once the toggle is the sole entry point.
  fun debugEnableProximity() {
    notificationPermission.request()
    locationPermission.requestAlways()
    IosContentStoreHolder.get().setNotifConfig(NotifConfig(enabled = true))
    reRegisterGeofences()
    reconcileExactSchedules()
  }
}
