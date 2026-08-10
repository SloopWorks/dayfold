package com.sloopworks.dayfold.client

// CAL-9 (ADR 0063 §1/§3) — the pure pieces of the iOS EventKit adapter, pulled out of IosCalendarPort
// so they're unit-testable from commonMain/desktopTest without linking EventKit. IosCalendarPort reads
// the OS objects (thin, untested) and hands their primitive fields to these functions.

// Mirrors Apple's EKAuthorizationStatus raw values (NotDetermined=0, Restricted=1, Denied=2,
// FullAccess=3 — same raw value as the deprecated Authorized case, WriteOnly=4). WriteOnly is the
// iOS 17+ "add only" rung (ADR 0063 §1): it lets the editor present, but observeEvents still needs
// full read access, so it maps to NotRequested for reconcile purposes, same as NotDetermined.
internal fun calendarPermissionFromEventKitStatus(rawValue: Long): CalendarPermission = when (rawValue) {
  3L -> CalendarPermission.Granted
  1L -> CalendarPermission.Restricted
  2L -> CalendarPermission.Denied
  else -> CalendarPermission.NotRequested // 0 (notDetermined) and 4 (writeOnly)
}

// ADR 0063 §3 data-minimization — the raw calendar source/account title never crosses the
// CalendarPort seam. Keeps the first character (a useful visual anchor for "which account is
// this") plus the domain of an email-shaped title, mirroring the "p•••@gmail.com" example in
// CalendarPort.kt's DeviceCalendar doc.
internal fun maskCalendarAccountLabel(rawAccountTitle: String): String {
  val trimmed = rawAccountTitle.trim()
  if (trimmed.isEmpty()) return trimmed
  val at = trimmed.indexOf('@')
  return if (at > 0) "${trimmed[0]}•••${trimmed.substring(at)}" else "${trimmed[0]}•••"
}

// EKCalendar exposes its color as a UIColor; IosCalendarPort reads its RGB components (0.0-1.0)
// and hands them here for the "#RRGGBB" formatting DeviceCalendar.color documents.
internal fun rgbComponentsToHex(red: Double, green: Double, blue: Double): String {
  fun channel(v: Double) = (v.coerceIn(0.0, 1.0) * 255).toInt().toString(16).padStart(2, '0')
  return "#${channel(red)}${channel(green)}${channel(blue)}".uppercase()
}

// EKEvent.structuredLocation?.title can be nil OR an empty string depending on how the event was
// authored — both mean "no location", not a location with a blank label.
internal fun calendarLocationFromTitle(title: String?): CandidateLocation? =
  title?.trim()?.takeIf { it.isNotEmpty() }?.let { CandidateLocation(label = it) }
