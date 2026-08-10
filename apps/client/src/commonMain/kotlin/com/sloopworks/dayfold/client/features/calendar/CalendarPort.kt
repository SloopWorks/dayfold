package com.sloopworks.dayfold.client

// ADR 0063 §1/§3 — the calendar device-glue SEAM. Mirrors NotifSeams.kt's established pattern:
// a commonMain interface injected at the platform entry (NOT expect/actual), so every target
// stays green without half-built actuals and the reconciler that will consume this stays unit-
// testable with a fake. Real impls (EventKit iOS / CalendarContract Android) are a later WI.

// OS calendar-access authorization. Distinct from location/notification permission axes
// (ADR 0044) — a separate state machine, checked before [CalendarPort.observeEvents] is called.
enum class CalendarPermission { NotRequested, Granted, Denied, Restricted, Unavailable }

// A calendar the OS exposes on this device. accountLabel is ALWAYS masked (e.g. "p•••@gmail.com")
// — the raw account name never crosses this seam (ADR 0063 §3 data-minimization).
data class DeviceCalendar(
  val id: String,
  val displayName: String,
  val color: String? = null,   // #RRGGBB decorative seed, as elsewhere in :client
  val accountLabel: String,
)

// What Dayfold hands the platform's native, prefilled event editor (ADR 0063 §1 "add only" /
// §2). Mirrors DayfoldEventCandidate's renderable fields exactly — never attendees (saving with
// attendees can send invitations, an external action outside this seam's authority) and never a
// reminder (the destination calendar's defaults and the user's own edit stay authoritative).
data class EventPrefill(
  val title: String,
  val startAt: String,
  val endAt: String?,
  val allDay: Boolean,
  val timezone: String,
  val location: CandidateLocation? = null,
  // CAL-8 — the candidate's own deep-link target, folded into the editor's description (short
  // note + link back to the Hub) so a saved event can point back to Dayfold. Null candidates
  // (e.g. a bare card with no hub target) simply get no link line. Android's ACTION_INSERT
  // adapter reads this to build the description extra; iOS's EventKitUI adapter has no
  // description field to set directly (see [notes]).
  val deepLink: DeepLinkTarget? = null,
  // Optional short note the caller may prefill (e.g. a plain-text pointer back to the source
  // Hub). No caller populates this yet — CalendarSelectors.toEventPrefill() leaves it null; the
  // adapter's job is only to plumb whatever is here into the editor's notes field.
  val notes: String? = null,
)

// The iOS EKEventEditViewController delegate's completion action (RELIABLE on iOS — unlike
// Android's ACTION_INSERT handoff, which cannot promise a result at all). Shared across platforms
// so CalendarCheckEngine's routing stays one code path even though Android's adapter (a later WI)
// may never be able to report SAVED/DELETED and will just not call back.
enum class CalendarEditorOutcome { SAVED, CANCELED, DELETED }

// One observed platform calendar event (ADR 0063 §3), bounded + in-memory only — never persisted
// raw. Strictly typed-field-only, mirroring DayfoldEventCandidate's discipline in the other
// direction: NO attendees, description/notes, meeting links, organizer, or reminders — those
// fields must not exist on this type, full stop (ADR 0063 §2/§3).
data class CalendarEventObservation(
  val platformEventId: String,
  val calendarId: String,
  val title: String,
  val startAt: String,
  val endAt: String?,
  val allDay: Boolean,
  val timezone: String,
  val location: CandidateLocation? = null,
  val isRecurring: Boolean,
  // The series/occurrence identity input the ADR 0063 §4 strict fingerprint normalizes over,
  // alongside title/start/end/allDay/timezone/location. Null for a genuinely one-off event.
  val recurrenceId: String? = null,
)

interface CalendarPort {
  /** Bounded, in-memory observation of [calendarIds] over the next [horizonDays]. Never persisted raw. */
  suspend fun observeEvents(calendarIds: Set<String>, horizonDays: Int): List<CalendarEventObservation>

  /** The calendars this device exposes, with a masked account label — never the raw account name. */
  suspend fun listCalendars(): List<DeviceCalendar>

  /** Current OS calendar-access authorization. Re-queried, never DB-cached (mirrors LocationPermissionController). */
  fun permissionState(): CalendarPermission

  /** Fire the native event editor prefilled from [prefill]. [onResult] carries the platform's
   *  completion outcome when the platform can reliably report one (iOS EventKitUI's delegate
   *  always calls back; a future Android ACTION_INSERT adapter may never invoke it). */
  fun openEventEditor(prefill: EventPrefill, onResult: (CalendarEditorOutcome) -> Unit = {})

  /** Fire the OS calendar-access permission prompt (WI-447 primer "Continue"). Real Android/iOS
   *  prompts are a later platform WI; this seam exists now so the primer has something to call. */
  fun requestPermission()
}

// The default for desktop + tests: no device calendars, no-op editor launch. Real platform
// adapters (EventKit / CalendarContract) are injected at the platform entry in a later WI.
object NoOpCalendarPort : CalendarPort {
  override suspend fun observeEvents(calendarIds: Set<String>, horizonDays: Int): List<CalendarEventObservation> = emptyList()
  override suspend fun listCalendars(): List<DeviceCalendar> = emptyList()
  override fun permissionState(): CalendarPermission = CalendarPermission.Unavailable
  override fun openEventEditor(prefill: EventPrefill, onResult: (CalendarEditorOutcome) -> Unit) = onResult(CalendarEditorOutcome.CANCELED)
  override fun requestPermission() {}
}
