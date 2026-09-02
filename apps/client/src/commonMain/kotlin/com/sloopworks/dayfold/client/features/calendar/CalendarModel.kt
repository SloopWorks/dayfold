package com.sloopworks.dayfold.client

// ADR 0063 §3 — the local decision projection persisted in calendar_binding. DEVICE-LOCAL,
// NEVER synced (not in applyDelta/outbox/Changes; ADR 0024). Holds only the mechanical state a
// stable reconciliation needs: which platform event a Dayfold subject matched (if any), the
// fingerprint that proved it, and the local relation/notification-owner/review decision. Raw
// event fields never live here — see CalendarEventObservation's exclusions.
data class CalendarBinding(
  // Local row identity (entityRef + factRef for ADR 0067; legacy subject key before migration).
  val subjectKey: String,
  val subjectRef: String = subjectKey,
  val entityRef: String? = null,
  val factRef: String? = null,
  val sourceVersion: String,
  val platformEventId: String? = null,
  val calendarId: String? = null,
  val fingerprint: String? = null,
  val lastSeenAt: String? = null,
  val relation: CalendarRelation = CalendarRelation.NEEDS_REVIEW,
  val notificationOwner: CalendarNotificationOwner = CalendarNotificationOwner.CALENDAR,
  val reviewState: String? = null,
  val createdAt: String,
  val updatedAt: String,
)

// ADR 0063 §4 reconciliation outcomes for one subject.
enum class CalendarRelation {
  MATCHED, IGNORED, NEEDS_REVIEW, MISSING;

  val wire: String get() = if (this == NEEDS_REVIEW) "needs_review" else name.lowercase()

  companion object {
    fun of(s: String): CalendarRelation = when (s) {
      "matched" -> MATCHED
      "ignored" -> IGNORED
      "missing" -> MISSING
      else -> NEEDS_REVIEW
    }
  }
}

// ADR 0063 §7 — who owns the generic event-time alert for this subject. Reversible per binding.
enum class CalendarNotificationOwner {
  CALENDAR, DAYFOLD;

  val wire: String get() = name.lowercase()

  companion object {
    fun of(s: String): CalendarNotificationOwner = if (s == "dayfold") DAYFOLD else CALENDAR
  }
}

// ADR 0063 §1 — the device-local, never-synced Calendar Check settings (calendar_settings,
// single row). Same posture as NotifConfig: a device preference, not tenant content.
data class CalendarSettings(
  val featureEnabled: Boolean = false,
  val selectedCalendarIds: Set<String> = emptySet(),
  val lastCheckAt: String? = null,
)

// CAL-4 (ADR 0063 §5, acceptance gate 3) — the bounded future horizon a Calendar Check
// compares against. Dogfood default; the operator ratifies the exact value at ADR acceptance.
// The single named constant — every horizon-bounded read (candidate filtering, CalendarPort
// .observeEvents) uses this, no other literal.
const val CALENDAR_CHECK_HORIZON_DAYS = 14

// CAL-4 (ADR 0063 §4/§5) — a reviewed resolution for one diverging structured field.
enum class FieldResolution { KEEP_DAYFOLD, USE_CALENDAR, LEAVE_BOTH }

// CAL-4 (ADR 0063 §4/§5) — the reconciler engine's run state + review-action results. Distinct
// from CalendarSettings (the opt-in/selected-calendars preference, DB-fed): this slice is the
// in-memory outcome of the last StartCalendarCheck pass plus local, undoable review decisions.
// Nothing here is DB-fed except indirectly via CalendarCheckEngine persisting calendar_binding
// rows as a side effect — the reducer itself never touches the DB (ADR 0058 effect-ownership).
data class CalendarCheckState(
  val permission: CalendarPermission = CalendarPermission.NotRequested,
  val lastCheckAt: String? = null,
  val checkInProgress: Boolean = false,
  // True when the last completed pass could not do a real comparison (permission not granted,
  // feature off, or no calendars selected) — the results are empty, not "all clear".
  val stale: Boolean = false,
  val results: ReconcileResult = ReconcileResult(),
  // Local, undoable dismissals (ADR 0063 §5). Keyed by subjectKey for candidate-based review
  // items (dayfoldOnly/suggested/ambiguous/differs/recurringNotices) or "calendarEvent:<id>" for
  // a bare calendar-only observation, which has no subjectKey.
  val ignored: Set<String> = emptySet(),
  // Ignored keys, oldest first — drives the Ignored screen's most-recent-first list
  // (ignoredKeysMostRecentFirst). UndoIgnore removes a specific key, not just the last one: the
  // Ignored screen gives every row its own Undo.
  val ignoreHistory: List<String> = emptyList(),
  val notificationOwnerOverrides: Map<String, CalendarNotificationOwner> = emptyMap(),
  // CAL-9 — the most recent platform editor handoff's completion outcome (Native-Handoff.dc.html
  // §11 return states). Set by CalendarEditorReturned; replaced by the next editor session.
  val editorReturn: CalendarEditorOutcome? = null,
)

// WI-447 (ADR 0063 §1) — the chooser's candidate list: calendars this device exposes, re-read
// from CalendarPort on demand (OS-owned truth, not persisted — mirrors the permission slices).
data class CalendarState(
  val settings: CalendarSettings = CalendarSettings(),
  val check: CalendarCheckState = CalendarCheckState(),
  val availableCalendars: List<DeviceCalendar> = emptyList(),
  // CAL-10 (ADR 0063 §6) — the reviewed Calendar→Dayfold import wizard's in-progress state.
  // Ephemeral like `check`: resets on family switch/sign-out (Reducer.kt), never synced.
  val importState: ImportProposalState = ImportProposalState.None,
  // Safe, normalized destination previews loaded on demand for the reviewed import. These contain
  // Dayfold Hub/member display data only — never a native calendar identifier or observation.
  val availableImportDestinations: List<CalendarImportDestinationOption> = emptyList(),
)
