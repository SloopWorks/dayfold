package com.sloopworks.dayfold.client

// ADR 0063 §3 — the local decision projection persisted in calendar_binding. DEVICE-LOCAL,
// NEVER synced (not in applyDelta/outbox/Changes; ADR 0024). Holds only the mechanical state a
// stable reconciliation needs: which platform event a Dayfold subject matched (if any), the
// fingerprint that proved it, and the local relation/notification-owner/review decision. Raw
// event fields never live here — see CalendarEventObservation's exclusions.
data class CalendarBinding(
  val subjectKey: String,
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

data class CalendarState(val settings: CalendarSettings = CalendarSettings())
