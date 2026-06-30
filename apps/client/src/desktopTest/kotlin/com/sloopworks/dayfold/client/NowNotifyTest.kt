package com.sloopworks.dayfold.client

import com.sloopworks.dayfold.client.cards.CardAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlinx.datetime.TimeZone

/**
 * ADR 0044 Phase B — Slice 0: the PURE notification-selection core. No engine fork — selection runs
 * over the RankedFeed the SAME rank()/nowFeed produced. quiet-hours + daily-cap live in a sibling
 * NotifConfig (never RankConfig); both device-local, never-synced (ADR 0024). Urgent (NOW-band /
 * geo-active) bypasses quiet-hours but still counts against the cap (operator-ratified 2026-06-30).
 */
class NowNotifyTest {
  private val zone = TimeZone.UTC

  private fun item(id: String, subject: String = id, geo: Boolean = false, hub: String? = null, block: String? = null) =
    NowItem(
      id = id, origin = Origin.DERIVED, reasonKind = ReasonKind.WHEN, title = id, why = id,
      subjectKey = subject, target = hub?.let { DeepLinkTarget(it, blockId = block) }, geoActive = geo,
    )

  private fun feed(now: List<NowItem> = emptyList(), soon: List<NowItem> = emptyList()) =
    RankedFeed(
      now = now.map { RankedItem(it, Band.NOW, 1.0) },
      soon = soon.map { RankedItem(it, Band.SOON, 0.5) },
      caughtUp = now.isEmpty(),
    )

  private val on = NotifConfig(enabled = true)
  private val noon = "2026-06-30T12:00:00Z"      // outside quiet window
  private val night = "2026-06-30T23:30:00Z"     // inside 22:00–08:00 (wraps midnight)

  @Test fun `disabled config posts nothing`() {
    val plan = selectNotifications(feed(now = listOf(item("a"))), noon, zone, NotifConfig(enabled = false))
    assertTrue(plan.toPost.isEmpty() && plan.held.isEmpty() && plan.capped.isEmpty())
  }

  @Test fun `an eligible NOW item is posted`() {
    val plan = selectNotifications(feed(now = listOf(item("a"))), noon, zone, on)
    assertEquals(listOf("a"), plan.toPost.map { it.id })
  }

  @Test fun `daily cap caps the overflow, posting none when already at cap`() {
    val plan = selectNotifications(
      feed(now = listOf(item("a"))), noon, zone, on.copy(dailyCap = 3), NotifLedger(postedToday = 3),
    )
    assertTrue(plan.toPost.isEmpty())
    assertEquals(listOf("a"), plan.capped.map { it.id })
  }

  @Test fun `cap takes top-K in ranked order, caps the rest`() {
    val f = feed(now = listOf(item("a"), item("b")), soon = listOf(item("c")))
    val plan = selectNotifications(f, noon, zone, on.copy(dailyCap = 2))
    assertEquals(listOf("a", "b"), plan.toPost.map { it.id })   // NOW before SOON, ranked order
    assertEquals(listOf("c"), plan.capped.map { it.id })
  }

  @Test fun `quiet hours holds non-urgent but posts urgent`() {
    val f = feed(now = listOf(item("urgent")), soon = listOf(item("calm")))
    val plan = selectNotifications(f, night, zone, on)
    assertEquals(listOf("urgent"), plan.toPost.map { it.id })   // NOW-band bypasses quiet
    assertEquals(listOf("calm"), plan.held.map { it.id })       // SOON held till morning
  }

  @Test fun `geo-active SOON item is urgent and bypasses quiet hours`() {
    val plan = selectNotifications(feed(soon = listOf(item("g", geo = true))), night, zone, on)
    assertEquals(listOf("g"), plan.toPost.map { it.id })
  }

  @Test fun `urgent still counts against the daily cap`() {
    val f = feed(now = listOf(item("u1"), item("u2")))
    val plan = selectNotifications(f, night, zone, on.copy(dailyCap = 1))
    assertEquals(listOf("u1"), plan.toPost.map { it.id })
    assertEquals(listOf("u2"), plan.capped.map { it.id })
  }

  @Test fun `already-notified subjects are not re-posted (dedup)`() {
    val plan = selectNotifications(
      feed(now = listOf(item("a", subject = "hub:h1"))), noon, zone, on,
      NotifLedger(notifiedSubjects = setOf("hub:h1")),
    )
    assertTrue(plan.toPost.isEmpty())
  }

  @Test fun `foreground-suppressed subjects are not notified`() {
    val plan = selectNotifications(
      feed(now = listOf(item("a", subject = "hub:h1"))), noon, zone, on,
      suppressedSubjects = setOf("hub:h1"),
    )
    assertTrue(plan.toPost.isEmpty())
  }

  @Test fun `nearestNPlaces returns the n closest by haversine`() {
    val here = DeviceLocation(0.0, 0.0)
    val near = Place(id = "near", label = "near", lat = 0.001, lng = 0.0)
    val mid = Place(id = "mid", label = "mid", lat = 0.01, lng = 0.0)
    val far = Place(id = "far", label = "far", lat = 0.1, lng = 0.0)
    assertEquals(listOf("near", "mid"), nearestNPlaces(listOf(far, near, mid), here, 2).map { it.id })
  }

  @Test fun `notificationActionFor maps target to OpenHub with focus block`() {
    val act = notificationActionFor(item("a", hub = "h1", block = "b1"))
    assertEquals(CardAction.OpenHub("h1", "b1"), act)
    assertEquals(null, notificationActionFor(item("a")))   // no target → no action
  }

  @Test fun `postedTodayCount counts only same-local-date notifications`() {
    val notified = listOf("2026-06-30T01:00:00Z", "2026-06-30T20:00:00Z", "2026-06-29T23:00:00Z")
    assertEquals(2, postedTodayCount(notified, "2026-06-30T12:00:00Z", zone))
  }

  @Test fun `quiet hours wraps midnight correctly`() {
    assertTrue(inQuietHours(23 * 60 + 30, on))    // 23:30 inside 22:00–08:00
    assertTrue(inQuietHours(2 * 60, on))          // 02:00 inside
    assertFalse(inQuietHours(12 * 60, on))        // noon outside
    assertFalse(inQuietHours(8 * 60, on))         // 08:00 boundary = end-exclusive → outside
  }
}
