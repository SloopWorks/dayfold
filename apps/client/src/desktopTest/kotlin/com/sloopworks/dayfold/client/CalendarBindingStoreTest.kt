package com.sloopworks.dayfold.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR 0063 §3 — the device-local, NEVER-synced calendar reconciliation store. calendar_binding
 * (per-subject match/relation decisions) is wiped on sign-out/family removal but survives a
 * content full-resync; calendar_settings (feature opt-in + selected calendars) is a device
 * preference like notif_config and is untouched by either wipe path. Neither table is ever
 * written to the outbox — the reconciler never becomes a sync source (ADR 0063 §3).
 */
class CalendarBindingStoreTest {
  private fun store() = ContentStore.create(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))

  private fun binding(subjectKey: String, calendarId: String? = "cal-1", relation: CalendarRelation = CalendarRelation.MATCHED) =
    CalendarBinding(
      subjectKey = subjectKey,
      sourceVersion = "v1",
      platformEventId = "evt-1",
      calendarId = calendarId,
      fingerprint = "fp-1",
      lastSeenAt = "2026-08-09T10:00:00Z",
      relation = relation,
      notificationOwner = CalendarNotificationOwner.CALENDAR,
      reviewState = null,
      createdAt = "2026-08-09T10:00:00Z",
      updatedAt = "2026-08-09T10:00:00Z",
    )

  @Test fun `upsert then read by subject key round-trips`() {
    val s = store()
    val b = binding("hub:h1/section:sec1/block:blk1")
    s.upsertCalendarBinding(b)
    assertEquals(b, s.calendarBindingBySubjectKey(b.subjectKey))
  }

  @Test fun `upsert on an existing subject key replaces it (no duplicate row)`() {
    val s = store()
    val b = binding("hub:h1")
    s.upsertCalendarBinding(b)
    val updated = b.copy(relation = CalendarRelation.IGNORED, reviewState = "dismissed", updatedAt = "2026-08-09T11:00:00Z")
    s.upsertCalendarBinding(updated)
    assertEquals(updated, s.calendarBindingBySubjectKey(b.subjectKey))
  }

  @Test fun `byRelation filters to matching rows only`() {
    val s = store()
    s.upsertCalendarBinding(binding("hub:h1", relation = CalendarRelation.MATCHED))
    s.upsertCalendarBinding(binding("hub:h2", relation = CalendarRelation.NEEDS_REVIEW))
    s.upsertCalendarBinding(binding("hub:h3", relation = CalendarRelation.MATCHED))
    val matched = s.calendarBindingsByRelation(CalendarRelation.MATCHED)
    assertEquals(setOf("hub:h1", "hub:h3"), matched.map { it.subjectKey }.toSet())
  }

  @Test fun `deleteForCalendar removes only that calendar's rows`() {
    val s = store()
    s.upsertCalendarBinding(binding("hub:h1", calendarId = "cal-a"))
    s.upsertCalendarBinding(binding("hub:h2", calendarId = "cal-b"))
    s.upsertCalendarBinding(binding("hub:h3", calendarId = "cal-a"))

    s.deleteCalendarBindingsForCalendar("cal-a")

    assertNull(s.calendarBindingBySubjectKey("hub:h1"))
    assertNull(s.calendarBindingBySubjectKey("hub:h3"))
    assertEquals("hub:h2", s.calendarBindingBySubjectKey("hub:h2")?.subjectKey)
  }

  @Test fun `sign-out wipe clears calendar bindings`() {
    val s = store()
    s.upsertCalendarBinding(binding("hub:h1"))
    s.wipe()
    assertNull(s.calendarBindingBySubjectKey("hub:h1"))
  }

  @Test fun `full-resync preserves calendar bindings for the same signed-in family`() {
    val s = store()
    s.upsertCalendarBinding(binding("hub:h1"))
    s.wipeForResync()
    assertEquals("hub:h1", s.calendarBindingBySubjectKey("hub:h1")?.subjectKey)
  }

  @Test fun `a schema-version resync heal also preserves calendar bindings`() {
    val s = store()
    s.upsertCalendarBinding(binding("hub:h1"))
    s.reconcileSchemaVersion(CLIENT_SCHEMA_VERSION + 1)
    assertEquals("hub:h1", s.calendarBindingBySubjectKey("hub:h1")?.subjectKey)
  }

  // ── calendar_settings (device preference, mirrors NotifConfig) ──

  @Test fun `calendar settings default to feature-off with no selected calendars`() {
    assertEquals(CalendarSettings(), store().calendarSettings())
  }

  @Test fun `setCalendarSettings round-trips`() {
    val s = store()
    val settings = CalendarSettings(featureEnabled = true, selectedCalendarIds = setOf("cal-a", "cal-b"), lastCheckAt = "2026-08-09T10:00:00Z")
    s.setCalendarSettings(settings)
    assertEquals(settings, s.calendarSettings())
  }

  @Test fun `sign-out wipe preserves calendar settings (device preference)`() {
    val s = store()
    val settings = CalendarSettings(featureEnabled = true, selectedCalendarIds = setOf("cal-a"))
    s.setCalendarSettings(settings)
    s.wipe()
    assertEquals(settings, s.calendarSettings())
  }

  @Test fun `CalendarSettingsLoaded sets the settings slice`() {
    val settings = CalendarSettings(featureEnabled = true, selectedCalendarIds = setOf("cal-a"))
    assertEquals(settings, rootReducer(AppState(), CalendarSettingsLoaded(settings)).calendar.settings)
  }

  // ── never a sync source (ADR 0063 §3) ──

  @Test fun `writing a calendar binding and settings never enqueues anything to the outbox`() {
    val s = store()
    s.upsertCalendarBinding(binding("hub:h1"))
    s.setCalendarSettings(CalendarSettings(featureEnabled = true, selectedCalendarIds = setOf("cal-a", "cal-b"), lastCheckAt = "2026-08-09T10:00:00Z"))
    assertEquals(0, s.pendingOpCount())
    assertEquals(0L, s.outboxSize())
  }

  @Test fun `a populated calendar settings slice never rides an outbound block payload`() {
    val s = store()
    s.applyDelta(
      emptyList(), emptyList(), emptyList(),
      listOf(HubBlock(id = "b1", sectionId = "s1", type = "checklist", payload = BlockPayload(items = listOf(ChecklistItem(id = "item1", text = "Pack bags"))))),
      emptyList(), "c1", "2026-08-09T10:00:00Z",
    )
    s.setCalendarSettings(CalendarSettings(featureEnabled = true, selectedCalendarIds = setOf("very-distinctive-calendar-id"), lastCheckAt = "2026-08-09T10:00:00Z"))
    s.upsertCalendarBinding(binding("b1", calendarId = "very-distinctive-calendar-id"))

    s.enqueueBlockToggle("b1", "item1", true, "u1", "2026-08-09T10:05:00Z", "op1")
    val op = s.nextPendingOp()
    assertTrue(op != null, "the toggle op was enqueued")
    assertTrue(!op!!.payload.contains("very-distinctive-calendar-id"), "outbound op payload leaked the calendar id")
    assertTrue(!op.payload.contains("selected_calendar_ids"), "outbound op payload leaked a calendar_settings column")
  }
}
