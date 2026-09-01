package com.sloopworks.dayfold.client

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

// CAL-8 (ADR 0063 §1/§3) — pure, platform-agnostic helpers behind AndroidCalendarPort. No Android
// unit-test source set exists in this repo (see apps/androidApp — androidTest is instrumented-
// only), so every bit of logic worth testing lives here in commonMain, exercised by :client
// desktopTest with a fake row/cursor stand-in. The real android.database.Cursor read in
// AndroidCalendarPort stays a thin, untested extraction into [CalendarInstanceRow].

/**
 * A platform-agnostic view of one row from Android's `CalendarContract.Instances` query — kept
 * separate from the Cursor read so the row→observation mapping is unit-testable without an
 * Android runtime. [instanceId] is the per-occurrence identity (Instances._id, unique even across
 * two occurrences of the same recurring event within the horizon); [eventId] is the underlying
 * Events._id (stable across occurrences of the same series).
 */
data class CalendarInstanceRow(
  val instanceId: String,
  val eventId: String,
  val calendarId: String,
  val title: String?,
  val beginMillis: Long,
  val endMillis: Long?,
  val allDay: Boolean,
  val eventTimezone: String?,
  val eventLocation: String?,
  val rrule: String?,
)

/** RRULE presence is the only signal Android gives for "this occurrence belongs to a series". */
internal fun isRecurringRrule(rrule: String?): Boolean = !rrule.isNullOrBlank()

// ADR 0063 §3 — mask the raw account name before it crosses the CalendarPort seam. An email-
// shaped label keeps its first character + domain ("pat@gmail.com" → "p•••@gmail.com"); anything
// else keeps only its first character ("Work" → "W•••").
internal fun maskAccountLabel(raw: String): String {
  if (raw.isBlank()) return raw
  val at = raw.indexOf('@')
  return if (at > 0) raw.take(1) + "•••" + raw.substring(at) else raw.take(1) + "•••"
}

// Android's Calendar Provider reports all-day begin/end as UTC-midnight epoch millis regardless
// of the device's local zone (and EVENT_TIMEZONE is meaningless for all-day rows) — format those
// as a bare "YYYY-MM-DD" in UTC, matching deriveEventCandidates' isDateOnly convention. A timed
// instance's begin/end is an absolute instant — format as a full ISO-8601 string, matching what
// parseInstantFlexible/Instant.parse already expect elsewhere in this package.
internal fun epochMillisToObservationIso(millis: Long, allDay: Boolean): String =
  if (allDay) {
    Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date.toString()
  } else {
    Instant.fromEpochMilliseconds(millis).toString()
  }

/** [CalendarInstanceRow] → [CalendarEventObservation]. Never reads/produces attendees, a
 *  description, or reminders — those columns are never even projected by the real query. */
fun mapCalendarInstanceRow(row: CalendarInstanceRow): CalendarEventObservation {
  val recurring = isRecurringRrule(row.rrule)
  return CalendarEventObservation(
    platformEventId = row.instanceId,
    calendarId = row.calendarId,
    title = row.title.orEmpty(),
    startAt = epochMillisToObservationIso(row.beginMillis, row.allDay),
    endAt = row.endMillis?.let { epochMillisToObservationIso(it, row.allDay) },
    allDay = row.allDay,
    timezone = if (row.allDay) "UTC" else (row.eventTimezone ?: "UTC"),
    location = row.eventLocation?.takeIf { it.isNotBlank() }?.let { CandidateLocation(address = it) },
    isRecurring = recurring,
    recurrenceId = if (recurring) row.eventId else null,
  )
}

// ── ACTION_INSERT extras (ADR 0063 §1 "add only") ──────────────────────────────────────────
// A platform-agnostic description of the native event-editor Intent's action/data/extras, built
// as plain string/long/boolean values keyed by the REAL CalendarContract extra names — pure and
// unit-testable without an android.content.Intent. AndroidCalendarPort.openEventEditor merely
// copies these onto a real Intent(ACTION_INSERT, Uri.parse(dataUri)).

const val EVENT_EDITOR_ACTION_INSERT = "android.intent.action.INSERT"
const val EVENT_EDITOR_EVENTS_URI = "content://com.android.calendar/events"

data class EventEditorIntentSpec(
  val action: String = EVENT_EDITOR_ACTION_INSERT,
  val dataUri: String = EVENT_EDITOR_EVENTS_URI,
  val extras: Map<String, Any> = emptyMap(),
)

// Verified app-links domain this app already owns (AndroidManifest's intent-filter host). No /h/
// route exists yet server- or client-side — this is a forward-compatible pointer for a human
// reading the calendar entry, not a functioning in-app deep link until a later WI adds that route.
private const val DAYFOLD_WEB_HOST = "https://family-ai-dashboard.vercel.app"

internal fun eventEditorDescription(deepLink: DeepLinkTarget?): String {
  val note = "Added from Dayfold."
  return if (deepLink == null) note else "$note\nDetails: $DAYFOLD_WEB_HOST/h/${deepLink.hubId}"
}

private fun locationExtraText(location: CandidateLocation?): String? = when {
  location == null -> null
  location.address != null -> location.address
  location.label != null -> location.label
  location.lat != null && location.lng != null -> "${location.lat},${location.lng}"
  else -> null
}

/** [EventPrefill] → the extras the native event editor needs. Never an attendee or reminder
 *  extra (ADR 0063 §1/§2 — saving with attendees can send invitations, an external action this
 *  seam has no authority for; the destination calendar's own reminder defaults stay authoritative). */
fun eventEditorIntentSpec(prefill: EventPrefill): EventEditorIntentSpec {
  // CalendarContract represents all-day boundaries as UTC midnight regardless of the device
  // zone. Parsing a bare date in America/Los_Angeles, for example, would shift the provider row
  // by eight hours and can display the previous/next day in another calendar client.
  val parseZone = if (prefill.allDay) TimeZone.UTC else TimeZone.of(prefill.timezone)
  val startMillis = parseInstantFlexible(prefill.startAt, parseZone)?.toEpochMilliseconds()
  val endMillis = prefill.endAt?.let { parseInstantFlexible(it, parseZone)?.toEpochMilliseconds() }
  val extras = buildMap<String, Any> {
    put("title", prefill.title)
    startMillis?.let { put("beginTime", it) }
    endMillis?.let { put("endTime", it) }
    put("allDay", prefill.allDay)
    put("eventTimezone", prefill.timezone)
    locationExtraText(prefill.location)?.let { put("eventLocation", it) }
    put("description", eventEditorDescription(prefill.deepLink))
  }
  return EventEditorIntentSpec(extras = extras)
}
