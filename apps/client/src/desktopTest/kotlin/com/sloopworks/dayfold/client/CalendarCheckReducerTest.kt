package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR 0063 §4/§5 — pure transitions over state.calendar.check. Every action's effect on
 * checkInProgress/results/ignored is asserted with no DB/port involved (the engine owns that).
 */
class CalendarCheckReducerTest {
  private fun candidate(subjectKey: String = "hub:h1") =
    DayfoldEventCandidate(subjectKey, "Ski trip", "2026-07-10T09:00:00Z", null, false, "UTC", null, "v1", null)

  private fun observation(platformEventId: String = "evt-1") =
    CalendarEventObservation(platformEventId, "cal-1", "Ski trip", "2026-07-10T09:00:00Z", null, false, "UTC", null, false, null)

  @Test fun `StartCalendarCheck sets checkInProgress`() {
    val next = rootReducer(AppState(), StartCalendarCheck)
    assertTrue(next.calendar.check.checkInProgress)
  }

  @Test fun `CalendarCheckCompleted clears checkInProgress and publishes results`() {
    val results = ReconcileResult(dayfoldOnly = listOf(candidate()))
    val started = rootReducer(AppState(), StartCalendarCheck)
    val next = rootReducer(started, CalendarCheckCompleted(results, CalendarPermission.Granted, "2026-08-09T12:00:00Z"))
    assertFalse(next.calendar.check.checkInProgress)
    assertEquals(results, next.calendar.check.results)
    assertEquals(CalendarPermission.Granted, next.calendar.check.permission)
    assertEquals("2026-08-09T12:00:00Z", next.calendar.check.lastCheckAt)
    assertFalse(next.calendar.check.stale)
  }

  @Test fun `CalendarCheckCompleted carries the stale marker through`() {
    val next = rootReducer(AppState(), CalendarCheckCompleted(ReconcileResult(), CalendarPermission.Denied, "t", stale = true))
    assertTrue(next.calendar.check.stale)
  }

  @Test fun `ConfirmMatch drops the subject from suggested`() {
    val c = candidate()
    val obs = observation()
    val state = AppState(calendar = CalendarState(check = CalendarCheckState(results = ReconcileResult(suggested = listOf(SuggestedMatch(c, obs, emptyList()))))))
    val next = rootReducer(state, ConfirmMatch("hub:h1", "evt-1"))
    assertTrue(next.calendar.check.results.suggested.isEmpty())
  }

  @Test fun `KeepSeparate returns the candidate to the plain dayfold-only gap`() {
    val c = candidate()
    val obs = observation()
    val state = AppState(calendar = CalendarState(check = CalendarCheckState(results = ReconcileResult(suggested = listOf(SuggestedMatch(c, obs, emptyList()))))))
    val next = rootReducer(state, KeepSeparate("hub:h1"))
    assertTrue(next.calendar.check.results.suggested.isEmpty())
    assertEquals(listOf(c), next.calendar.check.results.dayfoldOnly)
  }

  @Test fun `ResolveAmbiguous drops the subject from ambiguous`() {
    val c = candidate()
    val obsA = observation("evt-a")
    val obsB = observation("evt-b")
    val state = AppState(calendar = CalendarState(check = CalendarCheckState(results = ReconcileResult(ambiguous = listOf(AmbiguousMatch(c, listOf(obsA, obsB)))))))
    val next = rootReducer(state, ResolveAmbiguous("hub:h1", "evt-a"))
    assertTrue(next.calendar.check.results.ambiguous.isEmpty())
  }

  @Test fun `IgnoreItem is local, removes the item from results, and is undoable`() {
    val c = candidate()
    val state = AppState(calendar = CalendarState(check = CalendarCheckState(results = ReconcileResult(dayfoldOnly = listOf(c)))))
    val ignored = rootReducer(state, IgnoreItem("hub:h1"))
    assertEquals(setOf("hub:h1"), ignored.calendar.check.ignored)
    assertTrue(ignored.calendar.check.results.dayfoldOnly.isEmpty())

    val undone = rootReducer(ignored, UndoIgnore("hub:h1"))
    assertTrue(undone.calendar.check.ignored.isEmpty())
  }

  @Test fun `IgnoreItem addresses a calendar-only observation by its prefixed key`() {
    val obs = observation("evt-9")
    val state = AppState(calendar = CalendarState(check = CalendarCheckState(results = ReconcileResult(calendarOnly = listOf(obs)))))
    val next = rootReducer(state, IgnoreItem(calendarOnlyItemKey("evt-9")))
    assertTrue(next.calendar.check.results.calendarOnly.isEmpty())
    assertEquals(setOf(calendarOnlyItemKey("evt-9")), next.calendar.check.ignored)
  }

  @Test fun `UndoIgnore for a key that was never ignored is a no-op`() {
    val next = rootReducer(AppState(), UndoIgnore("hub:h1"))
    assertTrue(next.calendar.check.ignored.isEmpty())
  }

  @Test fun `UndoIgnore targets the row the user tapped, not just the most recent ignore`() {
    // The Ignored screen (§22) renders per-row Undo on every row, most-recent-first — tapping an
    // OLDER row's Undo must restore that item, not silently pop whatever was ignored last.
    var state = AppState()
    state = rootReducer(state, IgnoreItem("hub:h1"))
    state = rootReducer(state, IgnoreItem("hub:h2"))
    val undone = rootReducer(state, UndoIgnore("hub:h1"))
    assertEquals(setOf("hub:h2"), undone.calendar.check.ignored)
    assertEquals(listOf("hub:h2"), undone.calendar.check.ignoreHistory)
  }

  @Test fun `a double-tapped IgnoreItem doesn't duplicate ignoreHistory, so one Undo fully clears it`() {
    var state = AppState()
    state = rootReducer(state, IgnoreItem("hub:h1"))
    state = rootReducer(state, IgnoreItem("hub:h1")) // fast double-tap before the row disappears
    assertEquals(listOf("hub:h1"), state.calendar.check.ignoreHistory)
    val undone = rootReducer(state, UndoIgnore("hub:h1"))
    assertTrue(undone.calendar.check.ignored.isEmpty())
    assertTrue(undone.calendar.check.ignoreHistory.isEmpty())
  }

  @Test fun `FieldChoice KEEP_DAYFOLD resolves the field without recording a pendingWrite`() {
    val c = candidate()
    val obs = observation()
    val diff = DetailsDiffer("hub:h1", c, obs, listOf(FieldDiff("title", "a", "b")))
    val state = AppState(calendar = CalendarState(check = CalendarCheckState(results = ReconcileResult(differs = listOf(diff)))))
    val next = rootReducer(state, FieldChoice("hub:h1", "title", FieldResolution.KEEP_DAYFOLD))
    assertTrue(next.calendar.check.results.differs.isEmpty())
    assertTrue(next.calendar.check.pendingWrites.isEmpty())
  }

  @Test fun `FieldChoice USE_CALENDAR resolves the field and records a pendingWrite with the calendar value`() {
    val c = candidate()
    val obs = observation()
    val diff = DetailsDiffer("hub:h1", c, obs, listOf(FieldDiff("title", "Dayfold title", "Calendar title")))
    val state = AppState(calendar = CalendarState(check = CalendarCheckState(results = ReconcileResult(differs = listOf(diff)))))
    val next = rootReducer(state, FieldChoice("hub:h1", "title", FieldResolution.USE_CALENDAR))
    assertTrue(next.calendar.check.results.differs.isEmpty())
    val pending = next.calendar.check.pendingWrites.single()
    assertEquals("hub:h1", pending.subjectKey)
    assertEquals("title", pending.field)
    assertEquals("Calendar title", pending.calendarValue)
  }

  @Test fun `FieldChoice LEAVE_BOTH resolves only the chosen field, leaving the rest of the diff`() {
    val c = candidate()
    val obs = observation()
    val diff = DetailsDiffer("hub:h1", c, obs, listOf(FieldDiff("title", "a", "b"), FieldDiff("start", "x", "y")))
    val state = AppState(calendar = CalendarState(check = CalendarCheckState(results = ReconcileResult(differs = listOf(diff)))))
    val next = rootReducer(state, FieldChoice("hub:h1", "title", FieldResolution.LEAVE_BOTH))
    assertEquals(listOf(FieldDiff("start", "x", "y")), next.calendar.check.results.differs.single().diffs)
  }

  @Test fun `KeepSeriesCalendarOnly dismisses the recurring notice and ignores it going forward`() {
    val c = candidate()
    val obs = observation()
    val state = AppState(calendar = CalendarState(check = CalendarCheckState(results = ReconcileResult(recurringNotices = listOf(RecurringNotice(c, obs))))))
    val next = rootReducer(state, KeepSeriesCalendarOnly("hub:h1"))
    assertTrue(next.calendar.check.results.recurringNotices.isEmpty())
    assertEquals(setOf("hub:h1"), next.calendar.check.ignored)
  }

  @Test fun `ResetLocalMatches clears bindings-adjacent state - results, ignores, and history`() {
    val c = candidate()
    var state = AppState(calendar = CalendarState(check = CalendarCheckState(results = ReconcileResult(dayfoldOnly = listOf(c)))))
    state = rootReducer(state, IgnoreItem("hub:h2"))
    val next = rootReducer(state, ResetLocalMatches)
    assertEquals(CalendarCheckState(), next.calendar.check)
  }

  @Test fun `SetNotificationOwner records a local override`() {
    val next = rootReducer(AppState(), SetNotificationOwner("hub:h1", CalendarNotificationOwner.DAYFOLD))
    assertEquals(CalendarNotificationOwner.DAYFOLD, next.calendar.check.notificationOwnerOverrides["hub:h1"])
  }

  // ── isolation from unrelated slices (mirrors the settings-vs-check split) ──

  @Test fun `calendar settings are untouched by check actions`() {
    val settings = CalendarSettings(featureEnabled = true, selectedCalendarIds = setOf("cal-a"))
    val state = AppState(calendar = CalendarState(settings = settings))
    val next = rootReducer(state, StartCalendarCheck)
    assertEquals(settings, next.calendar.settings)
  }

  // ── sign-out / family-switch reset (Reducer.kt wiring) ──

  // ── action-log sanity (completion checklist) — the standard middleware renders a
  // `[redux] StartCalendarCheck → …` line with NO new logging, and never prints event content. ──

  @Test fun `dispatching a calendar-check action through the debug store logs the action name only, never content`() {
    val seen = mutableListOf<String>()
    Log.sink = { _, tag, message, _, _ -> if (tag == "redux") seen += message }
    try {
      val store = createTestAppStore(debug = true)
      val secretTitle = "Ski trip to a very secret family location"
      store.dispatch(StartCalendarCheck)
      store.dispatch(
        CalendarCheckCompleted(
          ReconcileResult(dayfoldOnly = listOf(candidate().copy(title = secretTitle))),
          CalendarPermission.Granted,
          "2026-08-09T12:00:00Z",
        ),
      )
      assertTrue(seen.any { it.startsWith("StartCalendarCheck →") })
      assertTrue(seen.any { it.startsWith("CalendarCheckCompleted →") })
      assertTrue(seen.none { it.contains(secretTitle) }, "the action log must never print calendar content")
    } finally {
      Log.sink = null
    }
  }

  @Test fun `sign-out resets calendar check but preserves calendar settings`() {
    val settings = CalendarSettings(featureEnabled = true, selectedCalendarIds = setOf("cal-a"))
    val c = candidate()
    val state = AppState(
      session = SessionState(session = Session(access = "a", refresh = "r", userId = "u1")),
      calendar = CalendarState(settings = settings, check = CalendarCheckState(results = ReconcileResult(dayfoldOnly = listOf(c)))),
    )
    val next = rootReducer(state, SignedOut)
    assertEquals(settings, next.calendar.settings)
    assertEquals(CalendarCheckState(), next.calendar.check)
  }

  @Test fun `CalendarEditorReturned records the editor's completion outcome`() {
    val next = rootReducer(AppState(), CalendarEditorReturned(CalendarEditorOutcome.SAVED))
    assertEquals(CalendarEditorOutcome.SAVED, next.calendar.check.editorReturn)
  }

  @Test fun `CalendarEditorReturned replaces a prior outcome on the next editor session`() {
    val saved = rootReducer(AppState(), CalendarEditorReturned(CalendarEditorOutcome.SAVED))
    val next = rootReducer(saved, CalendarEditorReturned(CalendarEditorOutcome.CANCELED))
    assertEquals(CalendarEditorOutcome.CANCELED, next.calendar.check.editorReturn)
  }

  // ── WI-461 — the Now card's "Review" tap, and the two never-reachable pieces it exposed ──

  @Test fun `OpenCalendarReview and CloseCalendarReview toggle the Feed substate flag`() {
    val opened = rootReducer(AppState(), OpenCalendarReview)
    assertTrue(opened.calendar.check.reviewOpen)
    val closed = rootReducer(opened, CloseCalendarReview)
    assertFalse(closed.calendar.check.reviewOpen)
  }

  @Test fun `OpenCalendarSettings routes to Calendar, CloseCalendarSettings routes back to Account`() {
    val opened = rootReducer(AppState(), OpenCalendarSettings)
    assertEquals(Route.Calendar, opened.navigation.route)
    val closed = rootReducer(opened, CloseCalendarSettings)
    assertEquals(Route.Account, closed.navigation.route)
  }

  @Test fun `calendarCheckAvailable defaults true, the single build-level flag that can flip it off`() {
    assertTrue(calendarCheckAvailable(AppState()))
    assertFalse(calendarCheckAvailable(AppState(calendar = CalendarState(checkAvailable = false))))
  }
}
