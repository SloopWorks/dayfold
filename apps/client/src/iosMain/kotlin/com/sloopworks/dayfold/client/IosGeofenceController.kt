package com.sloopworks.dayfold.client

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreLocation.CLCircularRegion
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationCoordinate2DMake
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.CLRegion
import platform.darwin.NSObject

// ADR 0044 §S3 — iOS geofencing (GeofenceController impl), mirroring AndroidGeofenceController +
// onGeofenceEnter. CLLocationManager region monitoring; honors the iOS 20-region hard cap via nearest-N
// eviction (the live position is used ONLY to choose the nearest saved places and is never persisted —
// ADR 0014). A region ENTER resolves the region id → the arrived saved-place coord → the shared headless
// pass. MUST be a process-global object retained for the app lifetime and created on the MAIN THREAD
// (CLLocationManager delivers callbacks on the run loop of its creating thread; its .delegate is weak, so
// the delegate is a retained field here) — IosNotifGlue holds the instance.
//
// The Kotlin GeofenceController interface and the Obj-C CLLocationManagerDelegateProtocol cannot live on
// one class (K/N: "Mixing Kotlin and Objective-C supertypes is not supported"), so the delegate is a
// separate retained NSObject (mirrors the QrDelegate pattern in QrScanner.ios.kt).
class IosGeofenceController : GeofenceController {
  private val manager = CLLocationManager()
  private val delegate = Delegate(this)

  // Set when a nearest-N narrowing is pending a one-shot location fix (>cap saved places). Consumed in
  // onLocation. Empty in the common case (≤20 places register directly, no fix needed).
  private var pendingNearest: List<Place> = emptyList()

  init { manager.delegate = delegate }   // constructed on the main thread by IosNotifGlue.start()

  @OptIn(ExperimentalForeignApi::class)
  override fun register(regions: List<GeoRegion>) {
    deregisterAll()
    if (regions.isEmpty()) return
    regions.take(IOS_REGION_CAP).forEach { r ->
      val region = CLCircularRegion(
        center = CLLocationCoordinate2DMake(r.lat, r.lng),
        radius = r.radiusM,
        identifier = r.id,
      ).apply {
        notifyOnEntry = true
        notifyOnExit = false
      }
      manager.startMonitoringForRegion(region)
    }
  }

  override fun deregisterAll() {
    // Stop ALL OS-persisted monitored regions (they survive app kill/reboot independent of the DB — a
    // stale region from a previous tenant must never fire for the next; ADR 0044 privacy boundary).
    manager.monitoredRegions.forEach { (it as? CLRegion)?.let { r -> manager.stopMonitoringForRegion(r) } }
  }

  // >cap saved places: take a single location fix, then register the nearest IOS_REGION_CAP. Called by
  // reRegisterGeofences only when needed; the fix is used to choose + is discarded (never persisted).
  fun registerNearest(places: List<Place>) {
    pendingNearest = places
    manager.requestLocation()
  }

  // Region-enter delivers an identifier, not a location — resolve it to the arrived saved-place coord
  // (the "live position" proxy for the pass; the real live position never persists). Then run the pass.
  private fun onEnter(regionId: String) {
    runBackgroundNotificationPass(placeLocationForRegion(regionId))
  }

  @OptIn(ExperimentalForeignApi::class)
  private fun onLocation(loc: CLLocation) {
    val places = pendingNearest
    if (places.isEmpty()) return
    pendingNearest = emptyList()
    loc.coordinate().useContents {
      register(geoRegionsFor(places, DeviceLocation(latitude, longitude), IOS_REGION_CAP))
    }
  }

  private class Delegate(private val owner: IosGeofenceController) : NSObject(), CLLocationManagerDelegateProtocol {
    override fun locationManager(manager: CLLocationManager, didEnterRegion: CLRegion) {
      owner.onEnter(didEnterRegion.identifier)
    }

    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
      (didUpdateLocations.lastOrNull() as? CLLocation)?.let { owner.onLocation(it) }
    }
  }
}
