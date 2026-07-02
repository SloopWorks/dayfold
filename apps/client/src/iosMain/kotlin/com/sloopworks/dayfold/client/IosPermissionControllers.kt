package com.sloopworks.dayfold.client

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNNotificationSettings
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.NSObject

// ADR 0044 §S3 — iOS permission controllers (OS-owned truth, NEVER DB-cached), mirroring the Android
// controllers. Location + notification authorization are SEPARATE axes. Held process-global by
// IosNotifGlue (their CL delegate is weak → a local delegate would deallocate); created on the main thread.

private fun openAppSettings() {
  val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
  UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any?>(), completionHandler = null)
}

// Location: requestWhenInUse → (later) requestAlways — the two-step ladder is driven by the OS +
// locationManagerDidChangeAuthorization, not a synchronous sequence. Uses the iOS-14 instance property
// authorizationStatus (not the deprecated class method). Background region delivery needs Always; a
// downgrade (Always → WhenInUse/Denied) deregisters all geofences (the NotifSeams contract).
class IosLocationPermissionController : LocationPermissionController {
  private val manager = CLLocationManager()
  private val delegate = Delegate(this)
  private val _state = MutableStateFlow(readStatus())
  override val state: Flow<LocationPermission> = _state.asStateFlow()

  init { manager.delegate = delegate }   // constructed on the main thread by IosNotifGlue.start()

  private fun readStatus(): LocationPermission = when (manager.authorizationStatus) {
    kCLAuthorizationStatusAuthorizedAlways -> LocationPermission.Always
    kCLAuthorizationStatusAuthorizedWhenInUse -> LocationPermission.WhenInUse
    else -> LocationPermission.Denied
  }

  override fun currentState(): LocationPermission = readStatus()
  /** Re-read OS truth (call on app resume). */
  fun refresh() { _state.value = readStatus() }
  override fun requestWhenInUse() { manager.requestWhenInUseAuthorization() }
  override fun requestAlways() { manager.requestAlwaysAuthorization() }
  override fun openOsSettings() = openAppSettings()

  private fun onAuthChange() {
    val s = readStatus()
    _state.value = s
    // Downgrade → region monitoring is silently dead; stop it + reflect feature-off honestly.
    if (s != LocationPermission.Always) IosNotifGlue.geofence.deregisterAll()
  }

  private class Delegate(private val owner: IosLocationPermissionController) :
    NSObject(), CLLocationManagerDelegateProtocol {
    override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) { owner.onAuthChange() }
  }
}

// Notification authorization. request → the OS prompt (once); state read via getNotificationSettings
// (async → cached, re-read on resume). iOS reports `denied` whether the user declined initially or
// disabled later, so denied → Blocked (the "detected, never overridden" state); notDetermined → Denied.
class IosNotificationPermissionController : NotificationPermissionController {
  private val _state = MutableStateFlow(NotificationPermission.Denied)
  override val state: Flow<NotificationPermission> = _state.asStateFlow()
  override fun currentState(): NotificationPermission = _state.value

  /** Re-read OS truth into the cached flow (async; call on app resume + after request). */
  fun refresh() {
    UNUserNotificationCenter.currentNotificationCenter().getNotificationSettingsWithCompletionHandler { settings ->
      _state.value = mapStatus(settings)
    }
  }

  private fun mapStatus(settings: UNNotificationSettings?): NotificationPermission =
    when (settings?.authorizationStatus) {
      UNAuthorizationStatusAuthorized, UNAuthorizationStatusProvisional, UNAuthorizationStatusEphemeral ->
        NotificationPermission.Granted
      UNAuthorizationStatusDenied -> NotificationPermission.Blocked
      else -> NotificationPermission.Denied   // notDetermined
    }

  override fun request() {
    requestNotificationAuthorization()
    refresh()
  }

  override fun openOsSettings() = openAppSettings()
}
