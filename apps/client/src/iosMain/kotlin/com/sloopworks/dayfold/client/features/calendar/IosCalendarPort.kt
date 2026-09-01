package com.sloopworks.dayfold.client

import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.toKotlinInstant
import kotlinx.datetime.toNSDate
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import platform.EventKit.*
import platform.EventKitUI.*
import platform.Foundation.*
import platform.UIKit.*
import platform.darwin.NSObject

// CAL-9 (ADR 0063 §1/§3) — the iOS CalendarPort adapter over EventKit + the EventKitUI editor
// handoff. Strictly typed-field-only on both directions, mirroring CalendarPort.kt's contract:
// observeEvents never reads notes/attendees/organizer/alarms; openEventEditor never prefills
// attendees/alarms. Thin and untested by design (see CalendarPortMapping.kt for the pure pieces
// this delegates to, which ARE unit-tested) — the EventKit/EventKitUI calls themselves can't run
// outside a simulator/device.
@OptIn(ExperimentalForeignApi::class)
class IosCalendarPort : CalendarPort {
  private val store = EKEventStore()

  // Strong references for in-flight editor delegates — EKEventEditViewController.editViewDelegate
  // is a weak property, and a locally-scoped delegate would be deallocated before the OS calls back.
  private val activeDelegates = mutableSetOf<EditDelegate>()

  override fun permissionState(): CalendarPermission =
    calendarPermissionFromEventKitStatus(EKEventStore.authorizationStatusForEntityType(EKEntityType.EKEntityTypeEvent))

  // iOS 17+ only (ADR 0063 §1) — the primer copy already says "where supported"; pre-17 devices
  // never reach this because the primer's Continue is the only caller (WI-447).
  override fun requestPermission() { requestPermission {} }

  override fun requestPermission(onResult: (CalendarPermission) -> Unit) {
    store.requestFullAccessToEventsWithCompletion { _, _ -> onResult(permissionState()) }
  }

  override suspend fun listCalendars(): List<DeviceCalendar> = withContext(Dispatchers.Default) {
    store.calendarsForEntityType(EKEntityType.EKEntityTypeEvent).map { calendar ->
      calendar as EKCalendar
      DeviceCalendar(
        id = calendar.calendarIdentifier,
        displayName = calendar.title,
        color = calendarColorHex(calendar),
        accountLabel = maskCalendarAccountLabel(calendar.source?.title ?: calendar.title),
      )
    }
  }

  override suspend fun observeEvents(calendarIds: Set<String>, horizonDays: Int): List<CalendarEventObservation> {
    if (calendarIds.isEmpty()) return emptyList()
    return withContext(Dispatchers.Default) {
      val calendars = store.calendarsForEntityType(EKEntityType.EKEntityTypeEvent)
        .map { it as EKCalendar }
        .filter { it.calendarIdentifier in calendarIds }
      if (calendars.isEmpty()) return@withContext emptyList()

      val start = NSDate()
      val end = NSDate.dateWithTimeIntervalSinceNow(horizonDays * SECONDS_PER_DAY)
      val predicate = store.predicateForEventsWithStartDate(start, end, calendars)
      store.eventsMatchingPredicate(predicate).mapNotNull { any ->
        val event = any as EKEvent
        val eventId = event.eventIdentifier ?: return@mapNotNull null
        val calendarId = event.calendar?.calendarIdentifier ?: return@mapNotNull null
        val eventZone = event.timeZone?.name
          ?.let { runCatching { TimeZone.of(it) }.getOrNull() }
          ?: TimeZone.currentSystemDefault()
        val startInstant = event.startDate?.toKotlinInstant() ?: return@mapNotNull null
        val endInstant = event.endDate?.toKotlinInstant()
        CalendarEventObservation(
          platformEventId = eventId,
          calendarId = calendarId,
          title = event.title ?: "",
          startAt = if (event.allDay) startInstant.toLocalDateTime(eventZone).date.toString() else startInstant.toString(),
          endAt = endInstant?.let { if (event.allDay) it.toLocalDateTime(eventZone).date.toString() else it.toString() },
          allDay = event.allDay,
          timezone = if (event.allDay) eventZone.id else (event.timeZone?.name ?: eventZone.id),
          location = calendarLocationFromTitle(event.structuredLocation?.title),
          isRecurring = event.hasRecurrenceRules,
          recurrenceId = if (event.hasRecurrenceRules) event.calendarItemIdentifier else null,
        )
      }
    }
  }

  override fun openEventEditor(prefill: EventPrefill, onResult: (CalendarEditorOutcome) -> Unit) {
    val presenter = topViewController()
    if (presenter == null) {
      onResult(CalendarEditorOutcome.CANCELED)
      return
    }

    val zone = runCatching { TimeZone.of(prefill.timezone) }.getOrElse { TimeZone.currentSystemDefault() }
    val start = parseInstantFlexible(prefill.startAt, zone)
    if (start == null) {
      onResult(CalendarEditorOutcome.CANCELED)
      return
    }
    // EventKit requires an end. Match calendar-app defaults when Dayfold has only a start:
    // one whole date for all-day events, one hour for timed events; the native editor remains
    // authoritative and lets the member change either before saving.
    val end = prefill.endAt?.let { parseInstantFlexible(it, zone) }
      ?: start + if (prefill.allDay) 1.days else 1.hours
    val event = EKEvent.eventWithEventStore(store)
    event.title = prefill.title
    event.startDate = start.toNSDate()
    event.endDate = end.toNSDate()
    event.allDay = prefill.allDay
    event.timeZone = NSTimeZone.timeZoneWithName(prefill.timezone)
    (prefill.location?.label ?: prefill.location?.address)?.let { label ->
      event.structuredLocation = EKStructuredLocation.locationWithTitle(label)
    }
    event.notes = prefill.notes ?: eventEditorDescription(prefill.deepLink)
    // Deliberately never set: attendees, alarms (ADR 0063 §2 — saving with attendees can send
    // invitations, an external action outside this seam's authority; the destination calendar's
    // reminder defaults stay authoritative). event.calendar is also left unset so the editor's own
    // picker lets the user choose the destination calendar.

    val editController = EKEventEditViewController()
    editController.event = event
    editController.eventStore = store
    val delegate = EditDelegate(onResult)
    activeDelegates += delegate
    editController.editViewDelegate = delegate
    presenter.presentViewController(editController, animated = true, completion = null)
  }

  override suspend fun openEvent(platformEventId: String) {
    val event = withContext(Dispatchers.Default) { store.eventWithIdentifier(platformEventId) } ?: return
    withContext(Dispatchers.Main) {
      val presenter = topViewController() ?: return@withContext
      val editController = EKEventEditViewController()
      editController.event = event
      editController.eventStore = store
      val delegate = EditDelegate {}
      activeDelegates += delegate
      editController.editViewDelegate = delegate
      presenter.presentViewController(editController, animated = true, completion = null)
    }
  }

  override suspend fun readEventDescription(platformEventId: String): String? =
    withContext(Dispatchers.Default) {
      store.eventWithIdentifier(platformEventId)?.notes?.trim()?.takeIf(String::isNotEmpty)
    }

  private inner class EditDelegate(
    private val onResult: (CalendarEditorOutcome) -> Unit,
  ) : NSObject(), EKEventEditViewDelegateProtocol {
    // EKEventEditViewAction bridges to a plain Long (kotlinx-cinterop NSInteger typealias), not a
    // Kotlin enum — the EKEventEditViewAction* constants below are its named Long values.
    override fun eventEditViewController(controller: EKEventEditViewController, didCompleteWithAction: Long) {
      controller.dismissViewControllerAnimated(true, completion = null)
      activeDelegates -= this
      onResult(
        when (didCompleteWithAction) {
          EKEventEditViewActionSaved -> CalendarEditorOutcome.SAVED
          EKEventEditViewActionDeleted -> CalendarEditorOutcome.DELETED
          else -> CalendarEditorOutcome.CANCELED
        },
      )
    }
  }

  private fun calendarColorHex(calendar: EKCalendar): String? {
    val cgColor = calendar.CGColor ?: return null
    return memScoped {
      val uiColor = UIColor(cGColor = cgColor)
      val r = alloc<DoubleVar>()
      val g = alloc<DoubleVar>()
      val b = alloc<DoubleVar>()
      val a = alloc<DoubleVar>()
      if (uiColor.getRed(r.ptr, g.ptr, b.ptr, a.ptr)) rgbComponentsToHex(r.value, g.value, b.value) else null
    }
  }

  private companion object {
    const val SECONDS_PER_DAY = 24.0 * 60.0 * 60.0
  }
}

// Finds the frontmost presented controller off the key window's root — MainViewController() IS
// that root (embedded directly by apps/iosApp's ContentView), so this always resolves to the live
// Compose host without any app-side registration for a "current controller" handle.
@Suppress("DEPRECATION")
private fun topViewController(): UIViewController? {
  var top = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return null
  while (true) top = top.presentedViewController ?: return top
}
