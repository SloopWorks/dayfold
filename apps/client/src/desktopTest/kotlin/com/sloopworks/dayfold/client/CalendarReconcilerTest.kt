package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/**
 * ADR 0063 §4 — the deterministic, conservative matching ladder. This is the core-logic test
 * budget for CAL-4: every rung individually, the never-auto-bind guarantees on the fuzzy rungs,
 * the recurring-series guard, and determinism/purity.
 */
class CalendarReconcilerTest {
  private val now = Instant.parse("2026-08-09T12:00:00Z")

  private fun candidate(
    subjectKey: String = "hub:h1",
    title: String = "Ski trip",
    startAt: String = "2026-07-10T09:00:00Z",
    endAt: String? = null,
    allDay: Boolean = false,
    timezone: String = "UTC",
    location: CandidateLocation? = null,
    sourceVersion: String = "v1",
  ) = DayfoldEventCandidate(subjectKey, title, startAt, endAt, allDay, timezone, location, sourceVersion, deepLink = null)

  private fun observation(
    platformEventId: String = "evt-1",
    calendarId: String = "cal-1",
    title: String = "Ski trip",
    startAt: String = "2026-07-10T09:00:00Z",
    endAt: String? = null,
    allDay: Boolean = false,
    timezone: String = "UTC",
    location: CandidateLocation? = null,
    isRecurring: Boolean = false,
    recurrenceId: String? = null,
  ) = CalendarEventObservation(platformEventId, calendarId, title, startAt, endAt, allDay, timezone, location, isRecurring, recurrenceId)

  private fun binding(
    subjectKey: String = "hub:h1",
    sourceVersion: String = "v1",
    platformEventId: String? = "evt-1",
    calendarId: String? = "cal-1",
    fingerprint: String? = "stale-fp",
    relation: CalendarRelation = CalendarRelation.MATCHED,
  ) = CalendarBinding(
    subjectKey = subjectKey, sourceVersion = sourceVersion, platformEventId = platformEventId, calendarId = calendarId,
    fingerprint = fingerprint, lastSeenAt = "2026-08-01T00:00:00Z", relation = relation,
    notificationOwner = CalendarNotificationOwner.CALENDAR, reviewState = null,
    createdAt = "2026-08-01T00:00:00Z", updatedAt = "2026-08-01T00:00:00Z",
  )

  // ── rung a: still-valid explicit binding ──────────────────────────────────────

  @Test fun `a still-valid binding whose event is still observed and unchanged is matched, no gap`() {
    val c = candidate()
    val obs = observation()
    val b = binding(fingerprint = fingerprintOfObservation(obs))
    val result = CalendarReconciler.reconcile(listOf(c), listOf(obs), listOf(b), now)

    assertTrue(result.dayfoldOnly.isEmpty())
    assertTrue(result.calendarOnly.isEmpty())
    assertTrue(result.suggested.isEmpty())
    assertTrue(result.ambiguous.isEmpty())
    assertTrue(result.differs.isEmpty())
    val bound = result.autoBindings.single()
    assertEquals("hub:h1", bound.subjectKey)
    assertEquals(CalendarRelation.MATCHED, bound.relation)
  }

  @Test fun `a binding whose event is no longer observed is not still-valid - falls through the ladder`() {
    val c = candidate()
    val b = binding(platformEventId = "gone-evt")
    // No observations at all — falls through to a plain dayfold-only gap, not a crash/false match.
    val result = CalendarReconciler.reconcile(listOf(c), emptyList(), listOf(b), now)
    assertEquals(listOf(c), result.dayfoldOnly)
    assertTrue(result.autoBindings.isEmpty())
  }

  @Test fun `a bound event whose fields drifted becomes details-differ, never a silent overwrite`() {
    val c = candidate(title = "Ski trip", startAt = "2026-07-10T09:00:00Z")
    val obs = observation(title = "Ski trip (rescheduled)", startAt = "2026-07-10T09:00:00Z")
    val b = binding(fingerprint = "whatever-was-true-before")
    val result = CalendarReconciler.reconcile(listOf(c), listOf(obs), listOf(b), now)

    assertTrue(result.autoBindings.isEmpty(), "a divergent bound pair must never be silently re-written")
    val diff = result.differs.single()
    assertEquals("hub:h1", diff.subjectKey)
    assertEquals(listOf(FieldDiff("title", "Ski trip", "Ski trip (rescheduled)")), diff.diffs)
  }

  // ── rung b: strict fingerprint ─────────────────────────────────────────────────

  @Test fun `exactly one strict fingerprint hit auto-binds`() {
    val c = candidate()
    val obs = observation()
    val result = CalendarReconciler.reconcile(listOf(c), listOf(obs), emptyList(), now)

    val bound = result.autoBindings.single()
    assertEquals("hub:h1", bound.subjectKey)
    assertEquals("evt-1", bound.platformEventId)
    assertEquals(CalendarRelation.MATCHED, bound.relation)
    assertTrue(result.dayfoldOnly.isEmpty())
    assertTrue(result.calendarOnly.isEmpty())
    assertTrue(result.ambiguous.isEmpty())
  }

  @Test fun `multiple strict fingerprint hits are ambiguous, never auto-bound`() {
    val c = candidate()
    val obsA = observation(platformEventId = "evt-a")
    val obsB = observation(platformEventId = "evt-b")
    val result = CalendarReconciler.reconcile(listOf(c), listOf(obsA, obsB), emptyList(), now)

    assertTrue(result.autoBindings.isEmpty())
    val am = result.ambiguous.single()
    assertEquals("hub:h1", am.candidate.subjectKey)
    assertEquals(setOf("evt-a", "evt-b"), am.observations.map { it.platformEventId }.toSet())
    // Claimed by the ambiguity, so neither also leaks out as a duplicate calendar-only gap.
    assertTrue(result.calendarOnly.isEmpty())
  }

  @Test fun `a title-only difference never strict-matches`() {
    val c = candidate(title = "Ski trip")
    val obs = observation(title = "Something else entirely")
    val result = CalendarReconciler.reconcile(listOf(c), listOf(obs), emptyList(), now)
    assertTrue(result.autoBindings.isEmpty())
    assertEquals(listOf(c), result.dayfoldOnly)
    assertEquals(listOf(obs), result.calendarOnly)
  }

  // ── rung c: high-confidence suggestion — NEVER auto-binds/suppresses ───────────

  @Test fun `same start plus significant title overlap is a suggestion, never an auto-bind`() {
    val c = candidate(title = "Ski trip to Tahoe", startAt = "2026-07-10T09:00:00Z", endAt = "2026-07-10T17:00:00Z")
    val obs = observation(title = "Ski Trip Tahoe (family)", startAt = "2026-07-10T09:00:00Z", endAt = null)
    val result = CalendarReconciler.reconcile(listOf(c), listOf(obs), emptyList(), now)

    assertTrue(result.autoBindings.isEmpty(), "a fuzzy match must never write a binding")
    val suggestion = result.suggested.single()
    assertEquals("hub:h1", suggestion.candidate.subjectKey)
    assertEquals("evt-1", suggestion.observation.platformEventId)
    assertTrue(suggestion.evidence.isNotEmpty())
    // Not silently dropped nor duplicated as a bare gap on either side.
    assertTrue(result.dayfoldOnly.isEmpty())
    assertTrue(result.calendarOnly.isEmpty())
  }

  @Test fun `same start but no meaningful title overlap is not a suggestion - stays two gaps`() {
    val c = candidate(title = "Ski trip", startAt = "2026-07-10T09:00:00Z")
    val obs = observation(title = "Dentist", startAt = "2026-07-10T09:00:00Z")
    val result = CalendarReconciler.reconcile(listOf(c), listOf(obs), emptyList(), now)
    assertTrue(result.suggested.isEmpty())
    assertEquals(listOf(c), result.dayfoldOnly)
    assertEquals(listOf(obs), result.calendarOnly)
  }

  // ── recurring: single-occurrence notice, never a flattened match ───────────────

  @Test fun `a recurring bound event is a notice, not a match, even when fields still agree`() {
    val c = candidate()
    val obs = observation(isRecurring = true, recurrenceId = "series-1")
    val b = binding(fingerprint = fingerprintOfObservation(obs))
    val result = CalendarReconciler.reconcile(listOf(c), listOf(obs), listOf(b), now)

    assertTrue(result.autoBindings.isEmpty())
    assertTrue(result.differs.isEmpty())
    val notice = result.recurringNotices.single()
    assertEquals("hub:h1", notice.candidate.subjectKey)
    assertEquals("evt-1", notice.observation.platformEventId)
  }

  @Test fun `a recurring observation that would strict-match by fields is a notice, not an auto-bind`() {
    val c = candidate()
    val obs = observation(isRecurring = true, recurrenceId = "series-1")
    val result = CalendarReconciler.reconcile(listOf(c), listOf(obs), emptyList(), now)

    assertTrue(result.autoBindings.isEmpty())
    assertTrue(result.ambiguous.isEmpty())
    val notice = result.recurringNotices.single()
    assertEquals("hub:h1", notice.candidate.subjectKey)
    assertTrue(result.calendarOnly.isEmpty(), "the recurring occurrence must not also leak out as a bare gap")
  }

  @Test fun `a non-recurring candidate never flattens a whole series into a one-off match`() {
    val c = candidate(title = "Team standup", startAt = "2026-07-10T09:00:00Z")
    val obs = observation(title = "Team standup", startAt = "2026-07-10T09:00:00Z", isRecurring = true, recurrenceId = "occ-42")
    val result = CalendarReconciler.reconcile(listOf(c), listOf(obs), emptyList(), now)
    assertTrue(result.recurringNotices.isNotEmpty())
    assertTrue(result.autoBindings.none { it.subjectKey == "hub:h1" })
  }

  // ── horizon ──────────────────────────────────────────────────────────────────

  @Test fun `horizon excludes a candidate at day 15 and includes one at day 13`() {
    val inHorizon = candidate(subjectKey = "hub:h13", startAt = "2026-08-22T12:00:00Z") // now + 13d
    val outOfHorizon = candidate(subjectKey = "hub:h15", startAt = "2026-08-24T12:00:00Z") // now + 15d
    val kept = candidatesInHorizon(listOf(inHorizon, outOfHorizon), now, CALENDAR_CHECK_HORIZON_DAYS, TimeZone.UTC)
    assertEquals(listOf("hub:h13"), kept.map { it.subjectKey })
  }

  @Test fun `horizon boundary at exactly the cutoff day is included`() {
    val onCutoff = candidate(subjectKey = "hub:h14", startAt = "2026-08-23T12:00:00Z") // now + 14d exactly
    val kept = candidatesInHorizon(listOf(onCutoff), now, CALENDAR_CHECK_HORIZON_DAYS, TimeZone.UTC)
    assertEquals(listOf("hub:h14"), kept.map { it.subjectKey })
  }

  // ── determinism / purity ────────────────────────────────────────────────────────

  @Test fun `reconcile is pure - identical inputs give identical output`() {
    val c = candidate()
    val obs = observation()
    val a = CalendarReconciler.reconcile(listOf(c), listOf(obs), emptyList(), now)
    val b = CalendarReconciler.reconcile(listOf(c), listOf(obs), emptyList(), now)
    assertEquals(a, b)
  }

  @Test fun `reconcile output does not depend on observation list order`() {
    val c = candidate()
    val obsA = observation(platformEventId = "evt-a")
    val obsB = observation(platformEventId = "evt-b")
    val a = CalendarReconciler.reconcile(listOf(c), listOf(obsA, obsB), emptyList(), now)
    val b = CalendarReconciler.reconcile(listOf(c), listOf(obsB, obsA), emptyList(), now)
    assertEquals(a.ambiguous.single().observations.toSet(), b.ambiguous.single().observations.toSet())
  }

  // ── dayfold-only / calendar-only gaps ───────────────────────────────────────────

  @Test fun `an unmatched candidate is a dayfold-only gap`() {
    val c = candidate()
    val result = CalendarReconciler.reconcile(listOf(c), emptyList(), emptyList(), now)
    assertEquals(listOf(c), result.dayfoldOnly)
  }

  @Test fun `an unmatched observation is a calendar-only gap`() {
    val obs = observation()
    val result = CalendarReconciler.reconcile(emptyList(), listOf(obs), emptyList(), now)
    assertEquals(listOf(obs), result.calendarOnly)
  }

  // ── ReconcileResult review-action helpers (consumed by the reducer) ────────────

  @Test fun `withoutSubject drops every bucket addressed by that subject`() {
    val c = candidate()
    val obs = observation()
    val result = ReconcileResult(dayfoldOnly = listOf(c))
    assertTrue(result.withoutSubject("hub:h1").dayfoldOnly.isEmpty())
    val suggestedResult = ReconcileResult(suggested = listOf(SuggestedMatch(c, obs, listOf("same start time"))))
    assertTrue(suggestedResult.withoutSubject("hub:h1").suggested.isEmpty())
  }

  @Test fun `withoutItemKey removes a calendar-only observation by its prefixed key`() {
    val obs = observation(platformEventId = "evt-9")
    val result = ReconcileResult(calendarOnly = listOf(obs))
    val cleared = result.withoutItemKey(calendarOnlyItemKey("evt-9"))
    assertTrue(cleared.calendarOnly.isEmpty())
  }

  @Test fun `resolveField drops one field and removes the entry once every field is resolved`() {
    val c = candidate()
    val obs = observation()
    val diff = DetailsDiffer("hub:h1", c, obs, listOf(FieldDiff("title", "a", "b"), FieldDiff("start", "x", "y")))
    val result = ReconcileResult(differs = listOf(diff))
    val afterOne = result.resolveField("hub:h1", "title")
    assertEquals(listOf(FieldDiff("start", "x", "y")), afterOne.differs.single().diffs)
    val afterBoth = afterOne.resolveField("hub:h1", "start")
    assertTrue(afterBoth.differs.isEmpty())
  }

  @Test fun `candidateFor finds the candidate behind any subjectKey-addressed bucket`() {
    val c = candidate()
    val obs = observation()
    val result = ReconcileResult(suggested = listOf(SuggestedMatch(c, obs, emptyList())))
    assertEquals(c, result.candidateFor("hub:h1"))
    assertNull(result.candidateFor("hub:unknown"))
  }
}
