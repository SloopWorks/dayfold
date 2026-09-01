package com.sloopworks.dayfold.client

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import androidx.core.database.getIntOrNull
import androidx.core.database.getLongOrNull
import androidx.core.database.getStringOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// CAL-8 (ADR 0063 §1/§3) — the real Android adapter over the Calendar Provider. Context-only
// (mirrors AndroidLocationPermissionController/AndroidNotificationPermissionController): the OS
// permission PROMPT and the native editor's Activity result need an Activity, so those two go
// through [CalendarActivityBridge] rather than a captured Activity/launcher — this port is
// constructed once and retained across Activity recreation (see CalendarActivityBridge kdoc).
class AndroidCalendarPort(context: Context) : CalendarPort {
  private val app = context.applicationContext
  private val resolver get() = app.contentResolver

  // OS permission truth has no NotRequested/Denied distinction to read back (checkSelfPermission
  // only answers granted/not-granted, and telling "never asked" from "asked and refused" needs an
  // Activity's shouldShowRequestPermissionRationale). This device-process-lifetime flag is enough
  // for the primer's own flow (it always calls requestPermission() before caring which is which);
  // a cold process restart before ever asking again just re-shows NotRequested, which is harmless.
  @Volatile private var everRequested = false

  override fun permissionState(): CalendarPermission {
    val granted = ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    return when {
      granted -> CalendarPermission.Granted
      everRequested -> CalendarPermission.Denied
      else -> CalendarPermission.NotRequested
    }
  }

  override fun requestPermission() {
    everRequested = true
    CalendarActivityBridge.launchPermissionRequest?.invoke()
  }

  override fun requestPermission(onResult: (CalendarPermission) -> Unit) {
    everRequested = true
    val launcher = CalendarActivityBridge.launchPermissionRequest
    if (launcher == null) {
      onResult(permissionState())
      return
    }
    CalendarActivityBridge.onPermissionResult = onResult
    launcher()
  }

  override suspend fun listCalendars(): List<DeviceCalendar> = withContext(Dispatchers.IO) {
    if (permissionState() != CalendarPermission.Granted) return@withContext emptyList()
    val projection = arrayOf(
      CalendarContract.Calendars._ID,
      CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
      CalendarContract.Calendars.CALENDAR_COLOR,
      CalendarContract.Calendars.ACCOUNT_NAME,
    )
    val out = mutableListOf<DeviceCalendar>()
    resolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)?.use { cursor ->
      val idIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
      val nameIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
      val colorIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_COLOR)
      val accountIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)
      while (cursor.moveToNext()) {
        out += DeviceCalendar(
          id = cursor.getLong(idIdx).toString(),
          displayName = cursor.getStringOrNull(nameIdx).orEmpty(),
          color = cursor.getIntOrNull(colorIdx)?.let(::colorIntToHex),
          // ADR 0063 §3 — the raw account name never crosses this seam, masked right here.
          accountLabel = maskAccountLabel(cursor.getStringOrNull(accountIdx).orEmpty()),
        )
      }
    }
    out
  }

  override suspend fun observeEvents(calendarIds: Set<String>, horizonDays: Int): List<CalendarEventObservation> =
    withContext(Dispatchers.IO) {
      if (permissionState() != CalendarPermission.Granted || calendarIds.isEmpty()) return@withContext emptyList()
      val begin = System.currentTimeMillis()
      val end = begin + horizonDays * DAY_MILLIS
      val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
        ContentUris.appendId(it, begin)
        ContentUris.appendId(it, end)
      }.build()
      val projection = arrayOf(
        CalendarContract.Instances._ID,
        CalendarContract.Instances.EVENT_ID,
        CalendarContract.Instances.CALENDAR_ID,
        CalendarContract.Instances.TITLE,
        CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.END,
        CalendarContract.Instances.ALL_DAY,
        CalendarContract.Instances.EVENT_TIMEZONE,
        CalendarContract.Instances.EVENT_LOCATION,
        CalendarContract.Instances.RRULE,
      )
      val selection = "${CalendarContract.Instances.CALENDAR_ID} IN (${calendarIds.joinToString(",") { "?" }})"
      val rows = mutableListOf<CalendarInstanceRow>()
      resolver.query(uri, projection, selection, calendarIds.toTypedArray(), null)?.use { cursor ->
        rows += cursor.readInstanceRows()
      }
      rows.map(::mapCalendarInstanceRow)
    }

  // ADR 0063 §1/§8 — the ACTION_INSERT handoff cannot promise a result at all (unlike iOS's
  // EventKitUI delegate), so [onResult] is accepted for interface conformance but never invoked;
  // the caller relies on a post-return rescan instead (CalendarCheckEngine/CalendarActivityBridge).
  override fun openEventEditor(prefill: EventPrefill, onResult: (CalendarEditorOutcome) -> Unit) {
    val spec = eventEditorIntentSpec(prefill)
    val intent = Intent(spec.action, Uri.parse(spec.dataUri))
    for ((key, value) in spec.extras) {
      when (value) {
        is String -> intent.putExtra(key, value)
        is Long -> intent.putExtra(key, value)
        is Boolean -> intent.putExtra(key, value)
      }
    }
    CalendarActivityBridge.launchEditorIntent?.invoke(intent)
  }

  override suspend fun openEvent(platformEventId: String) {
    val eventId = withContext(Dispatchers.IO) {
      if (permissionState() != CalendarPermission.Granted) return@withContext null
      val now = System.currentTimeMillis()
      val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
        ContentUris.appendId(it, now - DAY_MILLIS)
        ContentUris.appendId(it, now + CALENDAR_CHECK_HORIZON_DAYS * DAY_MILLIS)
      }.build()
      resolver.query(
        uri,
        arrayOf(CalendarContract.Instances.EVENT_ID),
        "${CalendarContract.Instances._ID} = ?",
        arrayOf(platformEventId),
        null,
      )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)) else null
      }
    } ?: return
    val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
    CalendarActivityBridge.launchEditorIntent?.invoke(Intent(Intent.ACTION_VIEW, uri))
  }

  override suspend fun readEventDescription(platformEventId: String): String? = withContext(Dispatchers.IO) {
    if (permissionState() != CalendarPermission.Granted) return@withContext null
    val eventId = eventIdForInstance(platformEventId) ?: return@withContext null
    resolver.query(
      ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
      arrayOf(CalendarContract.Events.DESCRIPTION),
      null,
      null,
      null,
    )?.use { cursor ->
      if (cursor.moveToFirst()) cursor.getStringOrNull(cursor.getColumnIndexOrThrow(CalendarContract.Events.DESCRIPTION))
        ?.trim()?.takeIf(String::isNotEmpty)
      else null
    }
  }

  private fun eventIdForInstance(platformEventId: String): Long? {
    val now = System.currentTimeMillis()
    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
      ContentUris.appendId(it, now - DAY_MILLIS)
      ContentUris.appendId(it, now + CALENDAR_CHECK_HORIZON_DAYS * DAY_MILLIS)
    }.build()
    return resolver.query(
      uri,
      arrayOf(CalendarContract.Instances.EVENT_ID),
      "${CalendarContract.Instances._ID} = ?",
      arrayOf(platformEventId),
      null,
    )?.use { cursor ->
      if (cursor.moveToFirst()) cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)) else null
    }
  }
}

private const val DAY_MILLIS = 24L * 60 * 60 * 1000

private fun colorIntToHex(color: Int): String = "#" + (color and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()

// Thin, untested Cursor extraction — the actual field mapping is [mapCalendarInstanceRow]
// (commonMain, unit-tested with a fake [CalendarInstanceRow]).
private fun Cursor.readInstanceRows(): List<CalendarInstanceRow> {
  val instanceIdIdx = getColumnIndexOrThrow(CalendarContract.Instances._ID)
  val eventIdIdx = getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
  val calendarIdIdx = getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_ID)
  val titleIdx = getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
  val beginIdx = getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
  val endIdx = getColumnIndexOrThrow(CalendarContract.Instances.END)
  val allDayIdx = getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
  val tzIdx = getColumnIndexOrThrow(CalendarContract.Instances.EVENT_TIMEZONE)
  val locationIdx = getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
  val rruleIdx = getColumnIndexOrThrow(CalendarContract.Instances.RRULE)
  val out = mutableListOf<CalendarInstanceRow>()
  while (moveToNext()) {
    out += CalendarInstanceRow(
      instanceId = getLong(instanceIdIdx).toString(),
      eventId = getLong(eventIdIdx).toString(),
      calendarId = getLong(calendarIdIdx).toString(),
      title = getStringOrNull(titleIdx),
      beginMillis = getLong(beginIdx),
      endMillis = getLongOrNull(endIdx),
      allDay = (getIntOrNull(allDayIdx) ?: 0) != 0,
      eventTimezone = getStringOrNull(tzIdx),
      eventLocation = getStringOrNull(locationIdx),
      rrule = getStringOrNull(rruleIdx),
    )
  }
  return out
}
