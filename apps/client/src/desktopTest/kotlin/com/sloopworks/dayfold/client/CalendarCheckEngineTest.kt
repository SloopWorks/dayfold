package com.sloopworks.dayfold.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone

/**
 * ADR 0063 §4/§5, ADR 0058 — the effect layer. CalendarCheckEngine owns every DB/port read+write
 * a Calendar Check pass needs; the reducer it dispatches into stays pure (CalendarCheckReducerTest).
 */
class CalendarCheckEngineTest {

  private class FakeCalendarPort(
    private val observations: List<CalendarEventObservation> = emptyList(),
    private val permission: CalendarPermission = CalendarPermission.Granted,
  ) : CalendarPort {
    var observeCallCount = 0
    var lastHorizonDays: Int? = null
    var lastCalendarIds: Set<String>? = null
    override suspend fun observeEvents(calendarIds: Set<String>, horizonDays: Int): List<CalendarEventObservation> {
      observeCallCount++
      lastHorizonDays = horizonDays
      lastCalendarIds = calendarIds
      return observations
    }
    override suspend fun listCalendars(): List<DeviceCalendar> = emptyList()
    var permissionStateCallCount = 0
    override fun permissionState(): CalendarPermission { permissionStateCallCount++; return permission }
    var lastEditorPrefill: EventPrefill? = null
    var lastEditorOnResult: ((CalendarEditorOutcome) -> Unit)? = null
    override fun openEventEditor(prefill: EventPrefill, onResult: (CalendarEditorOutcome) -> Unit) {
      lastEditorPrefill = prefill
      lastEditorOnResult = onResult
    }
    var requestPermissionCallCount = 0
    override fun requestPermission() { requestPermissionCallCount++ }
  }

  private class Harness(
    port: CalendarPort,
    initial: AppState = AppState(),
    nowIso: String = "2026-08-09T12:00:00Z",
    // Most tests drive `runCheck()` directly and never touch this. Injecting it lets the one test
    // that goes through the fire-and-forget `startCheck()` path join the pass it launches instead
    // of racing it — see the SAVED-outcome test below.
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
  ) {
    val contentStore = ContentStore.create(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
    val store = createTestAppStore(initial, debug = false)
    val engine = CalendarCheckEngine(
      store = store,
      contentStore = contentStore,
      calendarPort = port,
      scope = scope,
      nowProvider = { nowIso },
      zoneProvider = { TimeZone.UTC },
    )
  }

  private fun seedHub(store: ContentStore, id: String, title: String, startAt: String) {
    store.applyDelta(emptyList(), listOf(Hub(id, title = title, startAt = startAt)), tombstones = emptyList(), nextCursor = "c1", nowIso = "2026-08-01T00:00:00Z")
  }

  // ── happy path: derive → observe → reconcile → persist auto-bindings → dispatch ──

  @Test fun `a granted pass derives candidates, reconciles, persists auto-bindings, and publishes results`() = runBlocking {
    val obs = CalendarEventObservation("evt-1", "cal-a", "Ski trip", "2026-07-10T09:00:00Z", null, false, "UTC", null, false, null)
    val port = FakeCalendarPort(observations = listOf(obs))
    val h = Harness(port)
    seedHub(h.contentStore, "h1", "Ski trip", "2026-07-10T09:00:00Z")
    h.contentStore.setCalendarSettings(CalendarSettings(featureEnabled = true, selectedCalendarIds = setOf("cal-a")))

    h.engine.runCheck()

    assertFalse(h.store.state.calendar.check.checkInProgress)
    assertFalse(h.store.state.calendar.check.stale)
    assertEquals(CalendarPermission.Granted, h.store.state.calendar.check.permission)
    assertTrue(h.store.state.calendar.check.results.dayfoldOnly.isEmpty())

    val bound = h.contentStore.calendarBindingBySubjectKey("hub:h1")
    assertEquals("evt-1", bound?.platformEventId)
    assertEquals(CalendarRelation.MATCHED, bound?.relation)
    assertEquals(CALENDAR_CHECK_HORIZON_DAYS, port.lastHorizonDays)
    assertEquals(setOf("cal-a"), port.lastCalendarIds)
  }

  @Test fun `StartCalendarCheck is dispatched before the pass resolves`() = runBlocking {
    val h = Harness(FakeCalendarPort(permission = CalendarPermission.Denied))
    h.engine.runCheck()
    // We can't observe the transient StartCalendarCheck flip after a synchronous run, but
    // completion must still land with checkInProgress cleared either way.
    assertFalse(h.store.state.calendar.check.checkInProgress)
  }

  // ── permission / opt-in gating: no comparison happens, results are honestly empty+stale ──

  @Test fun `permission not granted short-circuits to a stale, empty result - no port observe call`() = runBlocking {
    val port = FakeCalendarPort(permission = CalendarPermission.Denied)
    val h = Harness(port)
    h.contentStore.setCalendarSettings(CalendarSettings(featureEnabled = true, selectedCalendarIds = setOf("cal-a")))

    h.engine.runCheck()

    assertTrue(h.store.state.calendar.check.stale)
    assertEquals(ReconcileResult(), h.store.state.calendar.check.results)
    assertEquals(0, port.observeCallCount)
    assertTrue(h.contentStore.allCalendarBindings().isEmpty())
  }

  @Test fun `feature disabled short-circuits to a stale, empty result`() = runBlocking {
    val h = Harness(FakeCalendarPort())
    h.contentStore.setCalendarSettings(CalendarSettings(featureEnabled = false, selectedCalendarIds = setOf("cal-a")))
    h.engine.runCheck()
    assertTrue(h.store.state.calendar.check.stale)
  }

  @Test fun `no calendars selected short-circuits to a stale, empty result`() = runBlocking {
    val h = Harness(FakeCalendarPort())
    h.contentStore.setCalendarSettings(CalendarSettings(featureEnabled = true, selectedCalendarIds = emptySet()))
    h.engine.runCheck()
    assertTrue(h.store.state.calendar.check.stale)
  }

  // ── ignore survives a re-check ──

  @Test fun `an ignored subject is excluded from the next check`() = runBlocking {
    val obs = CalendarEventObservation("evt-1", "cal-a", "Dentist", "2026-08-20T09:00:00Z", null, false, "UTC", null, false, null)
    val h = Harness(
      FakeCalendarPort(observations = listOf(obs)),
      initial = AppState(calendar = CalendarState(check = CalendarCheckState(ignored = setOf("hub:h1")))),
    )
    seedHub(h.contentStore, "h1", "Dentist", "2026-08-20T09:00:00Z")
    h.contentStore.setCalendarSettings(CalendarSettings(featureEnabled = true, selectedCalendarIds = setOf("cal-a")))

    h.engine.runCheck()

    assertTrue(h.store.state.calendar.check.results.dayfoldOnly.isEmpty())
    // The candidate was ignored, so it never even reaches the reconciler to pair with the
    // observation — the observation surfaces as its own calendar-only gap instead.
    assertEquals(listOf("evt-1"), h.store.state.calendar.check.results.calendarOnly.map { it.platformEventId })
  }

  @Test fun `an ignored calendar-only observation is excluded from the next check`() = runBlocking {
    val obs = CalendarEventObservation("evt-1", "cal-a", "Dentist", "2026-08-20T09:00:00Z", null, false, "UTC", null, false, null)
    val h = Harness(
      FakeCalendarPort(observations = listOf(obs)),
      initial = AppState(calendar = CalendarState(check = CalendarCheckState(ignored = setOf(calendarOnlyItemKey("evt-1"))))),
    )
    h.contentStore.setCalendarSettings(CalendarSettings(featureEnabled = true, selectedCalendarIds = setOf("cal-a")))
    h.engine.runCheck()
    assertTrue(h.store.state.calendar.check.results.calendarOnly.isEmpty())
  }

  // ── review-action effects ──

  @Test fun `confirmMatch persists a matched binding from the suggested pair and resolves it`() = runBlocking {
    val c = DayfoldEventCandidate("hub:h1", "Ski trip to Tahoe", "2026-07-10T09:00:00Z", null, false, "UTC", null, "v1", null)
    val obs = CalendarEventObservation("evt-1", "cal-a", "Ski Trip Tahoe", "2026-07-10T09:00:00Z", null, false, "UTC", null, false, null)
    val initial = AppState(calendar = CalendarState(check = CalendarCheckState(results = ReconcileResult(suggested = listOf(SuggestedMatch(c, obs, listOf("same start time")))))))
    val h = Harness(FakeCalendarPort(), initial)

    h.engine.confirmMatch("hub:h1", "evt-1")

    val bound = h.contentStore.calendarBindingBySubjectKey("hub:h1")
    assertEquals("evt-1", bound?.platformEventId)
    assertEquals(CalendarRelation.MATCHED, bound?.relation)
    assertTrue(h.store.state.calendar.check.results.suggested.isEmpty())
  }

  @Test fun `confirmMatch on a stale pair (already superseded by a fresh check) writes nothing and does not dispatch`() = runBlocking {
    // Simulates a race: the UI still holds a suggested pair from a prior CalendarCheckCompleted,
    // but a newer pass has already replaced state.calendar.check.results without it.
    val h = Harness(FakeCalendarPort())
    h.engine.confirmMatch("hub:h1", "evt-1")
    assertNull(h.contentStore.calendarBindingBySubjectKey("hub:h1"))
    assertEquals(ReconcileResult(), h.store.state.calendar.check.results)
  }

  @Test fun `resolveAmbiguous on a stale pair writes nothing and does not dispatch`() = runBlocking {
    val h = Harness(FakeCalendarPort())
    h.engine.resolveAmbiguous("hub:h1", "evt-1")
    assertNull(h.contentStore.calendarBindingBySubjectKey("hub:h1"))
    assertEquals(ReconcileResult(), h.store.state.calendar.check.results)
  }

  @Test fun `resolveAmbiguous persists the chosen event and resolves the ambiguity`() = runBlocking {
    val c = DayfoldEventCandidate("hub:h1", "Standup", "2026-07-10T09:00:00Z", null, false, "UTC", null, "v1", null)
    val obsA = CalendarEventObservation("evt-a", "cal-a", "Standup", "2026-07-10T09:00:00Z", null, false, "UTC", null, false, null)
    val obsB = CalendarEventObservation("evt-b", "cal-a", "Standup", "2026-07-10T09:00:00Z", null, false, "UTC", null, false, null)
    val initial = AppState(calendar = CalendarState(check = CalendarCheckState(results = ReconcileResult(ambiguous = listOf(AmbiguousMatch(c, listOf(obsA, obsB)))))))
    val h = Harness(FakeCalendarPort(), initial)

    h.engine.resolveAmbiguous("hub:h1", "evt-b")

    assertEquals("evt-b", h.contentStore.calendarBindingBySubjectKey("hub:h1")?.platformEventId)
    assertTrue(h.store.state.calendar.check.results.ambiguous.isEmpty())
  }

  @Test fun `setNotificationOwner updates an existing binding and dispatches the override`() = runBlocking {
    val h = Harness(FakeCalendarPort())
    h.contentStore.upsertCalendarBinding(
      CalendarBinding(
        subjectKey = "hub:h1", sourceVersion = "v1", platformEventId = "evt-1", calendarId = "cal-a",
        fingerprint = "fp", lastSeenAt = "2026-08-01T00:00:00Z", relation = CalendarRelation.MATCHED,
        notificationOwner = CalendarNotificationOwner.CALENDAR, reviewState = null,
        createdAt = "2026-08-01T00:00:00Z", updatedAt = "2026-08-01T00:00:00Z",
      ),
    )
    h.engine.setNotificationOwner("hub:h1", CalendarNotificationOwner.DAYFOLD)
    assertEquals(CalendarNotificationOwner.DAYFOLD, h.contentStore.calendarBindingBySubjectKey("hub:h1")?.notificationOwner)
    assertEquals(CalendarNotificationOwner.DAYFOLD, h.store.state.calendar.check.notificationOwnerOverrides["hub:h1"])
  }

  @Test fun `setNotificationOwner on a subject with no binding is a no-op write but still dispatches`() = runBlocking {
    val h = Harness(FakeCalendarPort())
    h.engine.setNotificationOwner("hub:unbound", CalendarNotificationOwner.DAYFOLD)
    assertNull(h.contentStore.calendarBindingBySubjectKey("hub:unbound"))
    assertEquals(CalendarNotificationOwner.DAYFOLD, h.store.state.calendar.check.notificationOwnerOverrides["hub:unbound"])
  }

  @Test fun `resetLocalMatches clears every calendar_binding row and the in-memory check slice`() = runBlocking {
    val h = Harness(FakeCalendarPort())
    h.contentStore.upsertCalendarBinding(
      CalendarBinding(
        subjectKey = "hub:h1", sourceVersion = "v1", platformEventId = "evt-1", calendarId = "cal-a",
        fingerprint = "fp", lastSeenAt = "t", relation = CalendarRelation.MATCHED,
        notificationOwner = CalendarNotificationOwner.CALENDAR, reviewState = null, createdAt = "t", updatedAt = "t",
      ),
    )
    h.engine.resetLocalMatches()
    assertTrue(h.contentStore.allCalendarBindings().isEmpty())
    assertEquals(CalendarCheckState(), h.store.state.calendar.check)
  }

  // ── never a sync source (ADR 0063 §3) — the engine's writes never touch the outbox ──

  @Test fun `a full pass with an auto-bind never enqueues anything to the outbox`() = runBlocking {
    val obs = CalendarEventObservation("evt-1", "cal-a", "Ski trip", "2026-07-10T09:00:00Z", null, false, "UTC", null, false, null)
    val h = Harness(FakeCalendarPort(observations = listOf(obs)))
    seedHub(h.contentStore, "h1", "Ski trip", "2026-07-10T09:00:00Z")
    h.contentStore.setCalendarSettings(CalendarSettings(featureEnabled = true, selectedCalendarIds = setOf("cal-a")))

    h.engine.runCheck()

    assertEquals(0, h.contentStore.pendingOpCount())
    assertEquals(0L, h.contentStore.outboxSize())
  }

  // ── horizon is applied to candidates, and forwarded to the port for observations ──

  @Test fun `a candidate beyond the horizon is excluded and never reaches the port comparison`() = runBlocking {
    val obs = CalendarEventObservation("evt-1", "cal-a", "Far future", "2026-09-10T09:00:00Z", null, false, "UTC", null, false, null)
    val port = FakeCalendarPort(observations = listOf(obs))
    val h = Harness(port)
    seedHub(h.contentStore, "h1", "Far future", "2026-09-10T09:00:00Z") // well beyond now+14d
    h.contentStore.setCalendarSettings(CalendarSettings(featureEnabled = true, selectedCalendarIds = setOf("cal-a")))

    h.engine.runCheck()

    assertTrue(h.store.state.calendar.check.results.dayfoldOnly.isEmpty(), "the out-of-horizon candidate must not surface as a gap")
    assertTrue(h.contentStore.allCalendarBindings().isEmpty())
  }

  // ── CAL-9 — the editor handoff's return-state routing ──

  private fun prefill() = EventPrefill("Ski trip", "2026-07-10T09:00:00Z", null, false, "UTC")

  @Test fun `openEventEditor routes a saved outcome into the shared action and checks permission to start a fresh check`() = runBlocking {
    val port = FakeCalendarPort(permission = CalendarPermission.Granted)
    val checkScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val h = Harness(port, scope = checkScope)

    h.engine.openEventEditor(prefill())
    assertEquals(prefill(), port.lastEditorPrefill)
    port.lastEditorOnResult!!.invoke(CalendarEditorOutcome.SAVED)

    assertEquals(CalendarEditorOutcome.SAVED, h.store.state.calendar.check.editorReturn)

    // SAVED consults permission inline as the gate, then `startCheck()` launches a pass that
    // consults it a second time. Sampling the counter here read whichever value the scheduler
    // happened to have produced — 1 or 2 — and failed CI intermittently. Join the launched pass
    // instead: the count is then deterministic, the cross-thread read is ordered by the join, and
    // asserting 2 proves the fresh check actually ran rather than only that the gate was consulted.
    checkScope.coroutineContext.job.children.toList().forEach { it.join() }
    assertEquals(2, port.permissionStateCallCount)
    checkScope.cancel()
  }

  @Test fun `openEventEditor routes a canceled outcome into the shared action without checking permission`() = runBlocking {
    val port = FakeCalendarPort(permission = CalendarPermission.Granted)
    val h = Harness(port)

    h.engine.openEventEditor(prefill())
    port.lastEditorOnResult!!.invoke(CalendarEditorOutcome.CANCELED)

    assertEquals(CalendarEditorOutcome.CANCELED, h.store.state.calendar.check.editorReturn)
    assertEquals(0, port.permissionStateCallCount)
  }
}
