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

  private fun item(
    id: String, subject: String = id, geo: Boolean = false, hub: String? = null, block: String? = null,
    reasonKind: String = ReasonKind.WHEN, origin: Origin = Origin.DERIVED, isEventStartAlert: Boolean = false,
  ) =
    NowItem(
      id = id, origin = origin, reasonKind = reasonKind, title = id, why = id,
      subjectKey = subject, target = hub?.let { DeepLinkTarget(it, blockId = block) }, geoActive = geo,
      isEventStartAlert = isEventStartAlert,
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

  // ── ADR 0063 §7 — Calendar-owned start-alert suppression ──

  @Test fun `calendar-owned subject suppresses only the event-start candidate`() {
    val plan = selectNotifications(
      feed(now = listOf(item("start", subject = "hub:h1", reasonKind = ReasonKind.WHEN))), noon, zone, on,
      calendarOwnedSubjects = setOf("hub:h1"),
    )
    assertTrue(plan.toPost.isEmpty())
  }

  // A checklist-type block that ALSO carries a when.at trigger produces a WHEN item and a
  // CHECKLIST item sharing the identical subjectKey (NowDerive.kt) — rank() dedups by EXACT
  // subjectKey, so that pair arrives here as ONE RankedItem: the higher-scoring head, plus the
  // other collapsed into `collapsedWith` (NowRank.kt), never as two separate RankedItems. These
  // tests exercise that real clustered shape, not a hand-built feed of independent RankedItems.
  @Test fun `a checklist peer collapsed under a calendar-owned WHEN head is promoted and still posts`() {
    val head = item("start", subject = "hub:h1", reasonKind = ReasonKind.WHEN)
    val peer = item("checklist", subject = "hub:h1", reasonKind = ReasonKind.CHECKLIST)
    val ranked = RankedItem(head, Band.NOW, 1.0, collapsedWith = listOf(peer))
    val plan = selectNotifications(RankedFeed(now = listOf(ranked)), noon, zone, on, calendarOwnedSubjects = setOf("hub:h1"))
    assertEquals(listOf("checklist"), plan.toPost.map { it.id })
  }

  @Test fun `a weather peer collapsed under a calendar-owned WHEN head is promoted and still posts`() {
    val head = item("start", subject = "hub:h1", reasonKind = ReasonKind.WHEN)
    val peer = item("weather", subject = "hub:h1", reasonKind = ReasonKind.WEATHER)
    val ranked = RankedItem(head, Band.NOW, 1.0, collapsedWith = listOf(peer))
    val plan = selectNotifications(RankedFeed(now = listOf(ranked)), noon, zone, on, calendarOwnedSubjects = setOf("hub:h1"))
    assertEquals(listOf("weather"), plan.toPost.map { it.id })
  }

  @Test fun `a calendar-owned WHEN head with no eligible peer produces nothing for that cluster`() {
    val head = item("start", subject = "hub:h1", reasonKind = ReasonKind.WHEN)
    val ranked = RankedItem(head, Band.NOW, 1.0)
    val plan = selectNotifications(RankedFeed(now = listOf(ranked)), noon, zone, on, calendarOwnedSubjects = setOf("hub:h1"))
    assertTrue(plan.toPost.isEmpty())
  }

  @Test fun `countdown and milestone start candidates are also suppressed for a calendar-owned subject`() {
    val plan = selectNotifications(
      feed(now = listOf(
        item("countdown", subject = "hub:h1", reasonKind = ReasonKind.COUNTDOWN),
        item("milestone", subject = "hub:h2", reasonKind = ReasonKind.MILESTONE),
      )),
      noon, zone, on,
      calendarOwnedSubjects = setOf("hub:h1", "hub:h2"),
    )
    assertTrue(plan.toPost.isEmpty())
  }

  @Test fun `SetNotificationOwner back to dayfold - an empty calendarOwnedSubjects set - restores the start candidate`() {
    val plan = selectNotifications(
      feed(now = listOf(item("start", subject = "hub:h1", reasonKind = ReasonKind.WHEN))), noon, zone, on,
      calendarOwnedSubjects = emptySet(),
    )
    assertEquals(listOf("start"), plan.toPost.map { it.id })
  }

  @Test fun `a calendar-owned subject does not affect an unrelated subject's start candidate`() {
    val plan = selectNotifications(
      feed(now = listOf(item("other", subject = "hub:h2", reasonKind = ReasonKind.WHEN))), noon, zone, on,
      calendarOwnedSubjects = setOf("hub:h1"),
    )
    assertEquals(listOf("other"), plan.toPost.map { it.id })
  }

  // WI-463 follow-up on WI-445 — an AUTHORED card's reasonKind is always its provenance
  // (weather/email/claude/external), never one of EVENT_START_REASON_KINDS, so a calendar-bound
  // authored when.at card relied on isEventStartAlert (cardToNowItem) to be suppressible at all.
  @Test fun `an authored card flagged isEventStartAlert is suppressed for a calendar-owned subject`() {
    val plan = selectNotifications(
      feed(now = listOf(
        item("authored:c1", subject = "hub:h1", reasonKind = ReasonKind.CLAUDE, origin = Origin.AUTHORED, isEventStartAlert = true),
      )),
      noon, zone, on,
      calendarOwnedSubjects = setOf("hub:h1"),
    )
    assertTrue(plan.toPost.isEmpty())
  }

  @Test fun `an authored card without isEventStartAlert still posts for a calendar-owned subject`() {
    val plan = selectNotifications(
      feed(now = listOf(
        item("authored:c2", subject = "hub:h1", reasonKind = ReasonKind.EMAIL, origin = Origin.AUTHORED, isEventStartAlert = false),
      )),
      noon, zone, on,
      calendarOwnedSubjects = setOf("hub:h1"),
    )
    assertEquals(listOf("authored:c2"), plan.toPost.map { it.id })
  }

  @Test fun `an authored isEventStartAlert card for a NOT calendar-owned subject still posts`() {
    val plan = selectNotifications(
      feed(now = listOf(
        item("authored:c3", subject = "hub:h1", reasonKind = ReasonKind.CLAUDE, origin = Origin.AUTHORED, isEventStartAlert = true),
      )),
      noon, zone, on,
      calendarOwnedSubjects = emptySet(),
    )
    assertEquals(listOf("authored:c3"), plan.toPost.map { it.id })
  }

  @Test fun `the CALENDAR_CHECK aggregate is never an OS-notification candidate, calendar-owned or not`() {
    val plan = selectNotifications(
      feed(now = listOf(item("agg", subject = CALENDAR_CHECK_SUBJECT_PREFIX, reasonKind = ReasonKind.CALENDAR_CHECK))),
      noon, zone, on,
    )
    assertTrue(plan.toPost.isEmpty())
    assertTrue(plan.held.isEmpty())
    assertTrue(plan.capped.isEmpty())
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
    assertEquals(CardAction.OpenHub("h1", HubArrival(HubArrivalLevel.BLOCK, "b1", HubArrivalSource.BRIEFING)), act)
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
