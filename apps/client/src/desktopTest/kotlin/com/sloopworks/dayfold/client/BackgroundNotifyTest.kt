package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ADR 0044 Phase B · S3 — the headless notification PASS. No engine fork: builds a minimal AppState from
 * a synchronous snapshot and runs the SAME nowFeed() + selectNotifications the foreground uses. Verifies
 * default-off, the daily cap rollover from the log, within-day dedup, and foreground-shown suppression —
 * all pure, with the clock + live location injected (the live position never persists).
 */
class BackgroundNotifyTest {
  private val zone = TimeZone.UTC
  private val noon = "2026-06-30T12:00:00Z"

  @Test fun `notification admission holds the full pass as one critical section`() {
    val coordinator = NotificationPassAdmissionCoordinator()
    val firstEntered = CountDownLatch(1)
    val releaseFirst = CountDownLatch(1)
    val secondCaptured = CountDownLatch(1)
    val secondEntered = CountDownLatch(1)
    val first = Thread {
      coordinator.runIfAdmitted(orElse = {}, action = {
        firstEntered.countDown()
        releaseFirst.await()
      })
    }
    val second = Thread {
      firstEntered.await()
      coordinator.runIfAdmitted(
        orElse = {},
        afterAdmissionCaptured = { secondCaptured.countDown() },
        action = { secondEntered.countDown() },
      )
    }

    first.start()
    second.start()
    secondCaptured.await()
    assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS))
    releaseFirst.countDown()
    first.join()
    second.join()
    assertEquals(0L, secondEntered.count)
  }

  @Test fun `family cleanup rejects a queued old-family pass and admits fresh work only after reopen`() {
    val coordinator = NotificationPassAdmissionCoordinator()
    val activeEntered = CountDownLatch(1)
    val releaseActive = CountDownLatch(1)
    val queuedCaptured = CountDownLatch(1)
    val queuedRan = CountDownLatch(1)
    val admissionClosed = CountDownLatch(1)
    val cleanupRan = CountDownLatch(1)
    val active = Thread {
      coordinator.runIfAdmitted(orElse = {}, action = {
        activeEntered.countDown()
        releaseActive.await()
      })
    }
    val queued = Thread {
      activeEntered.await()
      coordinator.runIfAdmitted(
        orElse = {},
        afterAdmissionCaptured = { queuedCaptured.countDown() },
        action = { queuedRan.countDown() },
      )
    }
    val cleanup = Thread {
      queuedCaptured.await()
      coordinator.closeAndDrain(
        blocker = NotificationAdmissionBlocker.FAMILY,
        afterAdmissionClosed = { admissionClosed.countDown() },
        cleanup = { cleanupRan.countDown() },
      )
    }

    active.start()
    queued.start()
    cleanup.start()
    admissionClosed.await()
    assertFalse(cleanupRan.await(100, TimeUnit.MILLISECONDS))
    releaseActive.countDown()
    active.join()
    queued.join()
    cleanup.join()

    assertEquals(1L, queuedRan.count)
    assertEquals(0L, cleanupRan.count)
    coordinator.runIfAdmitted(orElse = {}, action = { queuedRan.countDown() })
    assertEquals(1L, queuedRan.count)
    coordinator.openAdmission(NotificationAdmissionBlocker.FAMILY)
    coordinator.runIfAdmitted(orElse = {}, action = { queuedRan.countDown() })
    assertEquals(0L, queuedRan.count)
  }

  @Test fun `a stale enabled reconcile cannot re-arm after disable cleanup and reopen`() {
    val coordinator = NotificationPassAdmissionCoordinator()
    val activeEntered = CountDownLatch(1)
    val releaseActive = CountDownLatch(1)
    val staleReconcileCaptured = CountDownLatch(1)
    val admissionClosed = CountDownLatch(1)
    val staleRearmed = CountDownLatch(1)
    val active = Thread {
      coordinator.runIfAdmitted(orElse = {}, action = {
        activeEntered.countDown()
        releaseActive.await()
      })
    }
    val staleReconcile = Thread {
      activeEntered.await()
      coordinator.runIfAdmitted(
        orElse = {},
        afterAdmissionCaptured = { staleReconcileCaptured.countDown() },
        action = { staleRearmed.countDown() },
      )
    }
    val cleanup = Thread {
      staleReconcileCaptured.await()
      coordinator.closeAndDrain(
        blocker = NotificationAdmissionBlocker.CONFIG,
        afterAdmissionClosed = { admissionClosed.countDown() },
        cleanup = {},
      )
      coordinator.openAdmission(NotificationAdmissionBlocker.CONFIG)
    }

    active.start()
    staleReconcile.start()
    cleanup.start()
    admissionClosed.await()
    releaseActive.countDown()
    active.join()
    staleReconcile.join()
    cleanup.join()

    assertEquals(1L, staleRearmed.count)
  }

  @Test fun `config enable cannot reopen a family cleanup before its success hook`() {
    val coordinator = NotificationPassAdmissionCoordinator()
    val workRan = CountDownLatch(1)

    coordinator.closeAndDrain(blocker = NotificationAdmissionBlocker.CONFIG, cleanup = {})
    coordinator.closeAndDrain(blocker = NotificationAdmissionBlocker.FAMILY, cleanup = {})
    coordinator.openAdmission(NotificationAdmissionBlocker.CONFIG)
    coordinator.runIfAdmitted(orElse = {}, action = { workRan.countDown() })
    assertEquals(1L, workRan.count)

    coordinator.openAdmission(NotificationAdmissionBlocker.FAMILY)
    coordinator.runIfAdmitted(orElse = {}, action = { workRan.countDown() })
    assertEquals(0L, workRan.count)
  }

  @Test fun `planExactSchedules wakes at when-at plus offset, drops a past folded wake (issue 299)`() {
    val cfg = NotifConfig(enabled = true)
    val soon = Card(id = "c1", kind = "action", title = "Firestone", provenance = Provenance("claude"),
      triggers = listOf(BlockTrigger(whenTrigger = TriggerWhen(at = "2026-07-08T10:00:00-07:00", alertOffset = "-PT1H"))))
    val past = Card(id = "c2", kind = "action", title = "Gone", provenance = Provenance("claude"),
      triggers = listOf(BlockTrigger(whenTrigger = TriggerWhen(at = "2026-07-08T08:15:00-07:00", alertOffset = "-PT1H")))) // wake 07:15 < now
    val out = planExactSchedules(NotifSnapshot(cards = listOf(soon, past), config = cfg), nowIso = "2026-07-08T08:00:00-07:00")
    assertEquals(1, out.size)
    assertEquals(Instant.parse("2026-07-08T09:00:00-07:00"), Instant.parse(out.single().atIso))
  }

  // an authored card that surfaces as a current (NOW-band) item; subjectKey = "card:c1" (no target).
  private val card = Card(id = "c1", title = "Soccer at 4pm — pack jackets", bodyMd = "Rain at kickoff", notBefore = noon, provenance = Provenance("claude"))
  private fun snap(
    config: NotifConfig = NotifConfig(enabled = true),
    log: List<NotifLogRow> = emptyList(),
    surfacing: Map<String, SurfacingRecord> = emptyMap(),
    responses: List<ContentResponse> = emptyList(),
  ) = NotifSnapshot(
    cards = listOf(card), config = config, log = log, surfacing = surfacing,
    responses = responses,
  )

  private val done = ContentResponse(
    id = "r1", kind = ResponseKind.DONE, subjectRef = "card:c1",
    matchScope = MatchScope.SUBJECT, audienceScope = AudienceScope.FAMILY,
    userId = null, createdBy = "u2", label = "Soccer at 4pm — pack jackets",
  )

  @Test fun `default-off posts nothing`() {
    val plan = planBackgroundNotifications(snap(config = NotifConfig(enabled = false)), noon, null, zone)
    assertTrue(plan.toPost.isEmpty() && plan.held.isEmpty() && plan.capped.isEmpty())
  }

  @Test fun `an enabled pass plans the current item as a local notification`() {
    val plan = planBackgroundNotifications(snap(), noon, null, zone)
    assertEquals(listOf("card:c1"), plan.toPost.map { it.subjectKey })
  }

  @Test fun `a Done response suppresses a stale source card`() {
    val plan = planBackgroundNotifications(snap(responses = listOf(done)), noon, null, zone)
    assertTrue(plan.toPost.isEmpty())
  }

  @Test fun `a fired exact source remains relevant while its card is active and unread`() {
    val subjects = activeExactNotificationSubjects(snap(), "2026-07-01T12:00:00Z", zone)
    assertEquals(setOf("card:c1"), subjects)
  }

  @Test fun `a response or source deletion makes a fired exact subject stale`() {
    assertTrue(activeExactNotificationSubjects(snap(responses = listOf(done)), noon, zone).isEmpty())
    assertTrue(
      activeExactNotificationSubjects(
        NotifSnapshot(config = NotifConfig(enabled = true)),
        noon,
        zone,
      ).isEmpty(),
    )
  }

  @Test fun `an expired card no longer preserves a delivered exact notification`() {
    val expired = card.copy(expiresAt = "2026-06-30T11:59:59Z")
    val subjects = activeExactNotificationSubjects(
      NotifSnapshot(cards = listOf(expired), config = NotifConfig(enabled = true)),
      noon,
      zone,
    )
    assertTrue(subjects.isEmpty())
  }

  @Test fun `clearing a card time source makes its delivered exact notification stale`() {
    val untimed = card.copy(notBefore = null, triggers = emptyList())
    val subjects = activeExactNotificationSubjects(
      NotifSnapshot(cards = listOf(untimed), config = NotifConfig(enabled = true)),
      noon,
      zone,
    )
    assertTrue(subjects.isEmpty())
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
