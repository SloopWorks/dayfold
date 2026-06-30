package com.sloopworks.dayfold.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone

/**
 * ADR 0044 Phase B — Slice 1: the device-local, NEVER-synced notification state (ADR 0024).
 * notif_config (single-row config, default-off) + notification_log (per-post rows → daily cap by
 * local date + same-day dedup). Both wiped on wipe() (tenancy revoke) but PRESERVED on
 * wipeForResync() (device-personal, like surfacing_state). Plus the pure DB→store reducer bridges.
 */
class NotifStateTest {
  private val zone = TimeZone.UTC
  private fun store() = ContentStore.create(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))

  @Test fun `notif config defaults to disabled when never set`() {
    assertEquals(NotifConfig(), store().notifConfig())
  }

  @Test fun `setNotifConfig round-trips`() {
    val s = store()
    val c = NotifConfig(enabled = true, quietStartMinuteOfDay = 21 * 60, quietEndMinuteOfDay = 7 * 60, dailyCap = 5)
    s.setNotifConfig(c)
    assertEquals(c, s.notifConfig())
  }

  @Test fun `wipe resets notif config to default-off`() {
    val s = store()
    s.setNotifConfig(NotifConfig(enabled = true, dailyCap = 5))
    s.wipe()
    assertEquals(NotifConfig(), s.notifConfig())
  }

  @Test fun `wipeForResync preserves notif config (device-personal)`() {
    val s = store()
    val c = NotifConfig(enabled = true, dailyCap = 5)
    s.setNotifConfig(c)
    s.wipeForResync()
    assertEquals(c, s.notifConfig())
  }

  @Test fun `notifLedger counts today's posts and dedups subjects`() {
    val s = store()
    s.logNotification("hub:h1", "2026-06-30T01:00:00Z")
    s.logNotification("hub:h2", "2026-06-30T09:00:00Z")
    s.logNotification("hub:h1", "2026-06-30T11:00:00Z")     // same subject again today
    val ledger = s.notifLedger("2026-06-30T12:00:00Z", zone)
    assertEquals(3, ledger.postedToday)                      // cap counts every post
    assertEquals(setOf("hub:h1", "hub:h2"), ledger.notifiedSubjects)  // dedup set for re-post guard
  }

  @Test fun `notifLedger ignores posts from other local dates`() {
    val s = store()
    s.logNotification("hub:h1", "2026-06-29T23:00:00Z")     // yesterday
    val ledger = s.notifLedger("2026-06-30T12:00:00Z", zone)
    assertEquals(0, ledger.postedToday)
    assertTrue(ledger.notifiedSubjects.isEmpty())
  }

  @Test fun `wipe clears the notification log`() {
    val s = store()
    s.logNotification("hub:h1", "2026-06-30T01:00:00Z")
    s.wipe()
    assertEquals(0, s.notifLedger("2026-06-30T12:00:00Z", zone).postedToday)
  }

  @Test fun `wipeForResync preserves the notification log (device-personal)`() {
    val s = store()
    s.logNotification("hub:h1", "2026-06-30T01:00:00Z")
    s.wipeForResync()
    assertEquals(1, s.notifLedger("2026-06-30T12:00:00Z", zone).postedToday)
  }

  @Test fun `NotifConfigLoaded sets the config slice`() {
    val c = NotifConfig(enabled = true, dailyCap = 1)
    assertEquals(c, rootReducer(AppState(), NotifConfigLoaded(c)).notifConfig)
  }

  @Test fun `LocationPermissionLoaded sets the permission slice`() {
    assertEquals(LocationPermission.Always, rootReducer(AppState(), LocationPermissionLoaded(LocationPermission.Always)).locationPermission)
  }

  @Test fun `NotificationPermissionLoaded sets the permission slice`() {
    assertEquals(NotificationPermission.Granted, rootReducer(AppState(), NotificationPermissionLoaded(NotificationPermission.Granted)).notificationPermission)
  }

  @Test fun `permission slices default to denied`() {
    assertEquals(LocationPermission.Denied, AppState().locationPermission)
    assertEquals(NotificationPermission.Denied, AppState().notificationPermission)
  }
}
