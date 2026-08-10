package com.sloopworks.dayfold.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * WI-451 (CAL-11, ADR 0063 acceptance gate 6) — permanent tripwires proving raw calendar
 * identifiers/fingerprints/observations cannot leave the device via the outbox/sync egress path
 * (§2) or via any [Log] call the calendar engines make (§3). Every test here is salted with
 * distinctive strings that exist ONLY on the binding/observation side of the boundary — if any of
 * them ever shows up downstream, that IS the leak, not a coincidence.
 *
 * §1 (SWIP timeline recorder) and §4/§5 (analytics + RingDebugSink inspector) are proven in
 * `:swip-wiring`'s `DayfoldLeakTest` — the recorder/analytics/inspector pipelines are wired there,
 * not here.
 */
class CalendarPrivacyLeakGuardTest {
  @AfterTest fun resetLog() { Log.sink = null }

  private fun bodyText(req: HttpRequestData): String =
    (req.body as? io.ktor.http.content.TextContent)?.text ?: ""

  private val jsonHdr = headersOf("content-type", listOf("application/json"))

  private fun idSeq(): () -> String { var n = 0; return { "id-${n++}" } }

  private fun awaitState(store: org.reduxkotlin.Store<AppState>, pred: (ImportProposalState) -> Boolean) {
    val deadline = System.currentTimeMillis() + 5000
    while (System.currentTimeMillis() < deadline) {
      if (pred(store.state.calendar.importState)) return
      Thread.sleep(10)
    }
    throw AssertionError("timed out; importState=${store.state.calendar.importState}")
  }

  private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private fun importEngine(
    store: org.reduxkotlin.Store<AppState>,
    cs: ContentStore,
    calendarPort: CalendarPort,
    captured: MutableList<Pair<String, String>>,   // path to body
  ): CalendarImportEngine {
    val sc = SyncClient(
      "https://api.test",
      HttpClient(
        MockEngine { req ->
          val path = req.url.encodedPath
          val body = bodyText(req)
          if (body.isNotEmpty()) captured += path to body
          if (path.endsWith("/sync")) {
            respond("""{"changes":{},"tombstones":[],"has_more":false}""", HttpStatusCode.OK, jsonHdr)
          } else {
            respond("""{"version":1}""", HttpStatusCode.OK, jsonHdr)
          }
        },
      ),
    )
    val syncEngine = SyncEngine(store, cs, sc, nowProvider = { "2026-08-09T10:00:00Z" })
    syncEngine.resume(engineScope)
    return CalendarImportEngine(
      store = store, contentStore = cs, syncEngine = syncEngine,
      calendarPort = calendarPort, loadHubAudience = { null }, isOnline = { true },
      scope = engineScope, nowProvider = { "2026-08-09T10:00:00Z" }, idProvider = idSeq(),
      pollIntervalMs = 20L, pollMaxAttempts = 100,
    )
  }

  // ── §2: outbox/sync payloads never carry calendar_binding fields, observation fields, platform
  // event ids, or fingerprints — only the import's normalized allowlisted fields serialize. ──

  @Test fun `calendar import outbox payloads never carry binding or observation-only fields`() = runBlocking<Unit> {
    val cs = ContentStore.create(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
    val store = createTestAppStore(
      AppState(session = SessionState(session = Session("sec", "refresh", userId = "importer-uid"), activeFamilyId = "fam1")),
      debug = false,
    )

    // A binding for an UNRELATED subject, present in state at the same time as the import — proves
    // no accidental read-through/join into the outbox path materialize() feeds.
    cs.upsertCalendarBinding(
      CalendarBinding(
        subjectKey = "hub:other-hub", sourceVersion = "v1",
        platformEventId = "BINDEVT-aa11bb", calendarId = "BINDCAL-cc22dd", fingerprint = "FPSALT-ee33ff",
        lastSeenAt = "2026-08-09T09:00:00Z", relation = CalendarRelation.MATCHED,
        notificationOwner = CalendarNotificationOwner.CALENDAR, reviewState = null,
        createdAt = "2026-08-09T09:00:00Z", updatedAt = "2026-08-09T09:00:00Z",
      ),
    )

    // The source observation for THIS import — every field here except the ones the proposal type
    // can actually carry (title/start/end/allDay/timezone/location) must never reach the wire.
    val observation = CalendarEventObservation(
      platformEventId = "PLATEVT-9f2a1c", calendarId = "PLATCAL-7b3d2e",
      title = "Ellie's Birthday Party", startAt = "2026-09-12T16:00:00-07:00", endAt = "2026-09-12T18:00:00-07:00",
      allDay = false, timezone = "America/Los_Angeles",
      location = CandidateLocation(label = "Skate Palace", address = "42 Rink Rd"),
      isRecurring = true, recurrenceId = "RECUR-55ee11",
    )

    val captured = mutableListOf<Pair<String, String>>()
    val port = object : CalendarPort {
      override suspend fun observeEvents(calendarIds: Set<String>, horizonDays: Int) = listOf(observation)
      override suspend fun listCalendars() = emptyList<DeviceCalendar>()
      override fun permissionState() = CalendarPermission.Granted
      override fun openEventEditor(prefill: EventPrefill, onResult: (CalendarEditorOutcome) -> Unit) {}
      override fun requestPermission() {}
    }
    val e = importEngine(store, cs, port, captured)

    e.startImport(observation)
    e.chooseDestination(ImportDestination.NewHub(HubVisibilityChoice.RESTRICTED, listOf("importer-uid")))
    e.proceedToAudience()
    e.proceedToConfirm()
    e.confirm()
    awaitState(store) { it is ImportProposalState.Saved }

    assertTrue(captured.isNotEmpty(), "the import must actually PUT something for this test to be non-vacuous")
    val allBodies = captured.joinToString("\n") { it.second }

    // Observation-only fields (never on CalendarImportProposal) — banned outright.
    for (banned in listOf(
      "PLATEVT-9f2a1c", "PLATCAL-7b3d2e", "RECUR-55ee11",
      // A recurring, un-normalized isRecurring flag has no allowlisted destination either.
      "isRecurring", "recurrenceId", "platformEventId", "recurrence",
      // The unrelated binding's fields — proves no cross-subject leak either.
      "BINDEVT-aa11bb", "BINDCAL-cc22dd", "FPSALT-ee33ff", "fingerprint", "calendar_binding",
      "calendarId", "platform_event_id",
    )) {
      assertFalse(allBodies.contains(banned), "outbox payload leaked banned field/value '$banned':\n$allBodies")
    }

    // The normalized, reviewed fields DO serialize — the test isn't vacuously passing.
    assertTrue(allBodies.contains("Ellie's Birthday Party"), "the reviewed title must still serialize")
    assertTrue(allBodies.contains("Skate Palace"), "the reviewed location must still serialize")
    assertTrue(allBodies.contains("42 Rink Rd"), "the reviewed address must still serialize")

    // Exactly the allowed key set on the hub op body (§4.1) — nothing extra rode along.
    val hubBody = captured.first { (path, _) -> path.contains("/hubs/") }.second
    assertEquals(
      setOf("id", "type", "title", "visibility", "audience", "start_at", "end_at"),
      Json.parseToJsonElement(hubBody).jsonObject.keys,
    )
  }

  // ── §3: the calendar engines' own Log calls never carry event content, even when a sink is
  // installed and actually captures everything (not just the redux action-log's class name). ──

  @Test fun `calendar check and import engine log paths never emit event content`() = runBlocking<Unit> {
    val seen = mutableListOf<String>()
    Log.sink = { level, tag, message, context, error ->
      seen += "$level/$tag: $message ${context ?: ""} ${error?.message ?: ""}"
    }

    val secretTitle = "Underground Poker Night"
    val secretLocation = "The Vault, 12 Secret Ln"
    val obs = CalendarEventObservation(
      platformEventId = "LOGEVT-3311aa", calendarId = "LOGCAL-4422bb",
      title = secretTitle, startAt = "2026-07-10T21:00:00Z", endAt = null, allDay = false, timezone = "UTC",
      location = CandidateLocation(label = secretLocation), isRecurring = false, recurrenceId = null,
    )
    val port = object : CalendarPort {
      override suspend fun observeEvents(calendarIds: Set<String>, horizonDays: Int) = listOf(obs)
      override suspend fun listCalendars() = emptyList<DeviceCalendar>()
      override fun permissionState() = CalendarPermission.Granted
      override fun openEventEditor(prefill: EventPrefill, onResult: (CalendarEditorOutcome) -> Unit) {}
      override fun requestPermission() {}
    }

    val checkCs = ContentStore.create(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
    checkCs.applyDelta(emptyList(), listOf(Hub(id = "h1", title = secretTitle, startAt = "2026-07-10T21:00:00Z")), tombstones = emptyList(), nextCursor = "c1", nowIso = "2026-07-01T00:00:00Z")
    checkCs.setCalendarSettings(CalendarSettings(featureEnabled = true, selectedCalendarIds = setOf("LOGCAL-4422bb")))
    val checkStore = createTestAppStore(AppState(), debug = false)
    val checkEngine = CalendarCheckEngine(
      store = checkStore, contentStore = checkCs, calendarPort = port,
      nowProvider = { "2026-07-05T12:00:00Z" }, zoneProvider = { TimeZone.UTC },
    )
    checkEngine.runCheck()

    val importCs = ContentStore.create(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
    val importStore = createTestAppStore(
      AppState(session = SessionState(session = Session("sec", "refresh", userId = "importer-uid"), activeFamilyId = "fam1")),
      debug = false,
    )
    val captured = mutableListOf<Pair<String, String>>()
    val importEngine = importEngine(importStore, importCs, port, captured)
    importEngine.startImport(obs)
    importEngine.chooseDestination(ImportDestination.NewHub(HubVisibilityChoice.RESTRICTED, listOf("importer-uid")))
    importEngine.proceedToAudience()
    importEngine.proceedToConfirm()
    importEngine.confirm()
    awaitState(importStore) { it is ImportProposalState.Saved }

    val log = seen.joinToString("\n")
    for (banned in listOf(secretTitle, secretLocation, "LOGEVT-3311aa", "LOGCAL-4422bb")) {
      assertFalse(log.contains(banned), "a Log call leaked '$banned':\n$log")
    }
  }
}
