package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone

/**
 * ADR 0044 Phase B · S3 — the headless notification PASS. No engine fork: builds a minimal AppState from
 * a synchronous snapshot and runs the SAME nowFeed() + selectNotifications the foreground uses. Verifies
 * default-off, the daily cap rollover from the log, within-day dedup, and foreground-shown suppression —
 * all pure, with the clock + live location injected (the live position never persists).
 */
class BackgroundNotifyTest {
  private val zone = TimeZone.UTC
  private val noon = "2026-06-30T12:00:00Z"

  // an authored card that surfaces as a current (NOW-band) item; subjectKey = "card:c1" (no target).
  private val card = Card(id = "c1", title = "Soccer at 4pm — pack jackets", bodyMd = "Rain at kickoff", notBefore = noon, provenance = Provenance("claude"))
  private fun snap(
    config: NotifConfig = NotifConfig(enabled = true),
    log: List<NotifLogRow> = emptyList(),
    surfacing: Map<String, SurfacingRecord> = emptyMap(),
  ) = NotifSnapshot(cards = listOf(card), config = config, log = log, surfacing = surfacing)

  @Test fun `default-off posts nothing`() {
    val plan = planBackgroundNotifications(snap(config = NotifConfig(enabled = false)), noon, null, zone)
    assertTrue(plan.toPost.isEmpty() && plan.held.isEmpty() && plan.capped.isEmpty())
  }

  @Test fun `an enabled pass plans the current item as a local notification`() {
    val plan = planBackgroundNotifications(snap(), noon, null, zone)
    assertEquals(listOf("card:c1"), plan.toPost.map { it.subjectKey })
  }

  @Test fun `a subject already notified today is deduped`() {
    val plan = planBackgroundNotifications(snap(log = listOf(NotifLogRow("card:c1", "2026-06-30T09:00:00Z"))), noon, null, zone)
    assertTrue(plan.toPost.isEmpty())
  }

  @Test fun `the daily cap rolls over by local date from the log`() {
    val todayLog = listOf(
      NotifLogRow("x", "2026-06-30T01:00:00Z"),
      NotifLogRow("y", "2026-06-30T08:00:00Z"),
      NotifLogRow("z", "2026-06-30T11:00:00Z"),
    )
    val plan = planBackgroundNotifications(snap(config = NotifConfig(enabled = true, dailyCap = 3), log = todayLog), noon, null, zone)
    assertTrue(plan.toPost.isEmpty())                 // 3 already posted today → at cap
    assertEquals(listOf("card:c1"), plan.capped.map { it.subjectKey })
  }

  @Test fun `yesterday's notifications don't count against today's cap`() {
    val yesterdayLog = List(3) { NotifLogRow("old$it", "2026-06-29T20:00:00Z") }
    val plan = planBackgroundNotifications(snap(config = NotifConfig(enabled = true, dailyCap = 3), log = yesterdayLog), noon, null, zone)
    assertEquals(listOf("card:c1"), plan.toPost.map { it.subjectKey })
  }

  @Test fun `a subject shown in-feed within the window is suppressed`() {
    val recentlyShown = mapOf("card:c1" to SurfacingRecord(subjectKey = "card:c1", lastShownAtIso = "2026-06-30T11:50:00Z"))
    val plan = planBackgroundNotifications(snap(surfacing = recentlyShown), noon, null, zone)
    assertTrue(plan.toPost.isEmpty())
  }

  @Test fun `a subject shown long ago is NOT suppressed`() {
    val staleShown = mapOf("card:c1" to SurfacingRecord(subjectKey = "card:c1", lastShownAtIso = "2026-06-30T06:00:00Z"))
    val plan = planBackgroundNotifications(snap(surfacing = staleShown), noon, null, zone)
    assertEquals(listOf("card:c1"), plan.toPost.map { it.subjectKey })
  }
}
