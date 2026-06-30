package com.sloopworks.dayfold.client

import kotlinx.coroutines.flow.Flow

// ADR 0044 Phase B · S3 — the device-glue SEAMS. Per the plan's "thin glue, logic in commonMain": the
// platform-specific bits (NotificationCompat / GeofencingClient / AlarmManager on Android; UN* / CL* /
// UNCalendarNotificationTrigger on iOS; AWT SystemTray on desktop) implement these INTERFACES, while all
// decision logic — what to post, which regions to register, the daily cap, suppression — stays here in
// commonMain (pure, testable with fakes). Interfaces (not expect/actual) keep every target green without
// half-built actuals and make the orchestration unit-testable; the real impls are verified on-device (S4).

// A geofence region the OS monitors. Selected from saved places by nearest-N (iOS cap 20 / Android ~100);
// the live position never leaves the device — only the saved-place coordinates (already family content)
// are handed to the OS geofencing API. radiusM defaults applied by [geoRegionsFor].
data class GeoRegion(val id: String, val lat: Double, val lng: Double, val radiusM: Double)

// One LOCAL notification to post. Built purely from a ranked NowItem. [subtext] is the honest provenance
// line that rides INTO the notification ("Matched on your device" for geo/derived; "Added by Claude" for
// authored). [target] drives the tap deep-link → CardAction.OpenHub. [urgent] = NOW-band/geo (bypassed
// quiet hours upstream). [group] threads grouped digests (Android setGroup / iOS threadIdentifier).
data class NotificationSpec(
  val subjectKey: String,
  val title: String,
  val body: String,
  val subtext: String,
  val target: DeepLinkTarget?,
  val urgent: Boolean,
  val group: String = DAYFOLD_NOTIF_GROUP,
)

const val DAYFOLD_NOTIF_GROUP = "dayfold.now"
const val DEFAULT_GEOFENCE_RADIUS_M = 150.0
const val IOS_REGION_CAP = 20
const val ANDROID_REGION_CAP = 100

// ---- the seams (implemented per platform; faked in tests) ----

// Posts LOCAL notifications (no FCM/APNs — dumb-server invariant). ensureChannel is idempotent (Android
// channel / iOS category). postGroup posts a grouped digest with the on-device subtext + a deep-link
// action. cancel/cancelAll clear items now visible in the foreground (foreground-surfaced suppression).
interface LocalNotifier {
  fun ensureChannel()
  fun postGroup(specs: List<NotificationSpec>)
  fun cancel(subjectKey: String)
  fun cancelAll()
}

// Registers OS geofences for PRE-SELECTED regions; deregisterAll on disable / permission-downgrade /
// tenancy wipe. Re-registration triggers (boot, app-update, place change, significant-location-change)
// call register again with a freshly-computed nearest-N set.
interface GeofenceController {
  fun register(regions: List<GeoRegion>)
  fun deregisterAll()
}

// Schedules an EXACT local notification at a known future instant (when.at / countdown / milestone) —
// AlarmManager.setExactAndAllowWhileIdle / UNCalendarNotificationTrigger. NOT a periodic worker (those
// can't fire on time under Doze / iOS BGTask opportunism).
interface ExactNotificationScheduler {
  fun schedule(atIso: String, spec: NotificationSpec)
  fun cancel(subjectKey: String)
}

// Location permission is OS-owned truth (never DB-cached). The Flow bridges OS changes → a
// *PermissionLoaded action (re-read on resume; Android emits no change broadcast). request* drive the OS
// prompts (progressive: whenInUse before always); openOsSettings deep-links to the app's settings page.
interface LocationPermissionController {
  val state: Flow<LocationPermission>
  fun currentState(): LocationPermission
  fun requestWhenInUse()
  fun requestAlways()
  fun openOsSettings()
}

// Notification authorization is a SEPARATE axis (Android 13 POST_NOTIFICATIONS / iOS UN auth). Blocked =
// granted-then-disabled (channel importance NONE) — detected, never overridden.
interface NotificationPermissionController {
  val state: Flow<NotificationPermission>
  fun currentState(): NotificationPermission
  fun request()
  fun openOsSettings()
}

// ---- pure mappers (the logic the glue calls) ----

// The honest provenance subtext that rides into the notification (designs/triggers/Notifications §1).
fun notificationSubtext(item: NowItem): String = when {
  item.geoActive || item.reasonKind == ReasonKind.GEO -> "Matched on your device"
  item.reasonKind == ReasonKind.CLAUDE || item.authoredSource == "claude" -> "Added by Claude"
  item.reasonKind == ReasonKind.EMAIL || item.authoredSource == "email" -> "From your email"
  else -> "On your device"
}

// One ranked item → its notification spec. body uses the computed "why" (one useful sentence).
fun NowItem.toNotificationSpec(): NotificationSpec = NotificationSpec(
  subjectKey = subjectKey,
  title = title,
  body = why,
  subtext = notificationSubtext(this),
  target = target,
  urgent = geoActive,
)

// The geofences to register: the nearest-N saved places to the live position, capped per platform. Pure;
// the live position is used only to choose + is never persisted. radiusM falls back to a calm default.
fun geoRegionsFor(places: List<Place>, location: DeviceLocation, cap: Int): List<GeoRegion> =
  nearestNPlaces(places, location, cap).map {
    GeoRegion(it.id, it.lat, it.lng, it.radiusM?.toDouble() ?: DEFAULT_GEOFENCE_RADIUS_M)
  }
