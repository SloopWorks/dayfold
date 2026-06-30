package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.TimeZone

/**
 * ADR 0044 Phase B · S3 — the exact-alarm SCHEDULING pipeline (pure). Known future instants get an exact
 * local notification armed at sync time (a periodic worker can't fire on time under Doze). Keeps the
 * soonest future trigger per subject within the horizon; the fire-time receiver re-runs the full pass so
 * the cap/quiet/dedup are honored then. No engine fork — reuses nowFeed.
 */
class ExactScheduleTest {
  private val zone = TimeZone.UTC
  private val noon = "2026-06-30T12:00:00Z"

  private fun card(id: String, at: String?) =
    Card(id = id, title = "Event $id", bodyMd = "why $id", notBefore = at, provenance = Provenance("claude"))

  private fun snap(vararg cards: Card, enabled: Boolean = true) =
    NotifSnapshot(cards = cards.toList(), config = NotifConfig(enabled = enabled))

  @Test fun `arms the soonest future timed item within the horizon`() {
    val schedules = planExactSchedules(
      snap(
        card("soon", "2026-06-30T14:00:00Z"),   // +2h → armed
        card("far", "2026-07-15T12:00:00Z"),     // beyond 48h → skipped
        card("past", "2026-06-30T11:00:00Z"),    // already passed → skipped
      ),
      noon, zone,
    )
    assertEquals(listOf("card:soon"), schedules.map { it.spec.subjectKey })
    assertEquals("2026-06-30T14:00:00Z", schedules.single().atIso)
  }

  @Test fun `disabled config arms nothing`() {
    assertTrue(planExactSchedules(snap(card("soon", "2026-06-30T14:00:00Z"), enabled = false), noon, zone).isEmpty())
  }

  @Test fun `no future timed items arms nothing`() {
    assertTrue(planExactSchedules(snap(card("past", "2026-06-30T11:00:00Z")), noon, zone).isEmpty())
  }

  @Test fun `a custom horizon narrows the window`() {
    // a 30-min horizon drops the +2h item.
    assertTrue(planExactSchedules(snap(card("soon", "2026-06-30T14:00:00Z")), noon, zone, horizon = 30.minutes).isEmpty())
  }
}
