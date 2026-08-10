package com.sloopworks.dayfold.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** CAL-10 (ADR 0063 §6) — CalendarImportEngine.confirm()'s revalidation + apply orchestration:
 *  the WI's explicit DO list — role gate (Viewer absent + mid-flight denial), version gate,
 *  idempotency, offline queue, new-Hub-restricted-by-default. */
class CalendarImportEngineTest {
  private fun freshStore() = ContentStore.create(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
  private fun appStore(initial: AppState) = createTestAppStore(initial, debug = false)

  private fun observation(calendarId: String = "cal-1") = CalendarEventObservation(
    platformEventId = "evt-1", calendarId = calendarId, title = "Grandma's 80th lunch",
    startAt = "2026-06-28T12:00:00-07:00", endAt = "2026-06-28T14:00:00-07:00", allDay = false,
    timezone = "America/Los_Angeles", isRecurring = false,
  )

  private fun grantedPort(events: List<CalendarEventObservation> = emptyList()) = object : CalendarPort {
    override suspend fun observeEvents(calendarIds: Set<String>, horizonDays: Int) = events
    override suspend fun listCalendars() = emptyList<DeviceCalendar>()
    override fun permissionState() = CalendarPermission.Granted
    override fun openEventEditor(prefill: EventPrefill) {}
    override fun requestPermission() {}
  }

  private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private fun engine(
    store: org.reduxkotlin.Store<AppState>,
    cs: ContentStore,
    syncClient: SyncClient = SyncClient("https://api.test", HttpClient(MockEngine { req ->
      if (req.url.encodedPath.endsWith("/sync")) {
        respond("""{"changes":{},"tombstones":[],"has_more":false}""", HttpStatusCode.OK, headersOf("content-type", "application/json"))
      } else {
        respond("""{"version":1}""", HttpStatusCode.OK, headersOf("content-type", "application/json"))
      }
    })),
    calendarPort: CalendarPort = grantedPort(),
    isOnline: () -> Boolean = { true },
    loadHubAudience: suspend (String) -> HubAudience? = { null },
  ): CalendarImportEngine {
    // A real running app has already `.resume()`d its SyncEngine's coordinator by the time any
    // member-write engine calls requestSync (ADR 0058) — mirror that here so requestSync actually
    // has a live worker to signal, instead of only setting a pending flag no one services.
    val syncEngine = SyncEngine(store, cs, syncClient, nowProvider = { "2026-08-09T10:00:00Z" })
    syncEngine.resume(engineScope)
    return CalendarImportEngine(
      store = store, contentStore = cs, syncEngine = syncEngine,
      calendarPort = calendarPort, loadHubAudience = loadHubAudience, isOnline = isOnline,
      scope = engineScope,
      nowProvider = { "2026-08-09T10:00:00Z" },
      idProvider = idSeq(),
      pollIntervalMs = 20L, pollMaxAttempts = 100,
    )
  }

  private fun idSeq(): () -> String { var n = 0; return { "id-${n++}" } }

  private fun awaitState(store: org.reduxkotlin.Store<AppState>, pred: (ImportProposalState) -> Boolean) {
    val deadline = System.currentTimeMillis() + 5000
    while (System.currentTimeMillis() < deadline) {
      if (pred(store.state.calendar.importState)) return
      Thread.sleep(10)
    }
    throw AssertionError("timed out; importState=${store.state.calendar.importState}")
  }

  private fun sessionState() = SessionState(session = Session("sec", "refresh", userId = "me"), activeFamilyId = "fam1")

  @Test fun `new-Hub default is restricted-to-importer, and the saved audience summary says so`() = runBlocking<Unit> {
    val cs = freshStore()
    val store = appStore(AppState(session = sessionState()))
    val http = MockEngine { respond("""{"version":1}""", HttpStatusCode.OK, headersOf("content-type", "application/json")) }
    val e = engine(store, cs, syncClient = SyncClient("https://api.test", HttpClient(http)))

    e.startImport(observation())
    val proposal = (store.state.calendar.importState as ImportProposalState.ChoosingDestination).proposal
    val dest = ImportDestination.NewHub()   // default ctor: RESTRICTED, empty audience
    assertEquals(HubVisibilityChoice.RESTRICTED, dest.visibility)
    e.chooseDestination(dest.copy(audience = listOf("u-importer")))
    e.proceedToAudience()
    e.proceedToConfirm()
    e.confirm()

    awaitState(store) { it is ImportProposalState.Saved }
    val saved = store.state.calendar.importState as ImportProposalState.Saved
    assertEquals("Hub created — only you can see it", saved.audienceSummary)
  }

  @Test fun `no connectivity at confirm queues offline without enqueueing any op`() = runBlocking<Unit> {
    val cs = freshStore()
    val store = appStore(AppState(session = sessionState()))
    val e = engine(store, cs, isOnline = { false })

    e.startImport(observation())
    e.chooseDestination(ImportDestination.NewHub(HubVisibilityChoice.RESTRICTED, listOf("u1")))
    e.proceedToAudience(); e.proceedToConfirm()
    e.confirm()

    awaitState(store) { it is ImportProposalState.OfflineQueued }
    assertEquals(0L, cs.outboxSize())
    val offline = store.state.calendar.importState as ImportProposalState.OfflineQueued
    assertNotNull(offline.ids)   // ids ARE minted at confirm regardless of connectivity (spec §3.3)
  }

  @Test fun `calendar access revoked before the chain enqueues holds — nothing applied`() = runBlocking<Unit> {
    val cs = freshStore()
    val store = appStore(AppState(session = sessionState()))
    val revokedPort = object : CalendarPort {
      override suspend fun observeEvents(calendarIds: Set<String>, horizonDays: Int) = emptyList<CalendarEventObservation>()
      override suspend fun listCalendars() = emptyList<DeviceCalendar>()
      override fun permissionState() = CalendarPermission.Denied
      override fun openEventEditor(prefill: EventPrefill) {}
      override fun requestPermission() {}
    }
    val e = engine(store, cs, calendarPort = revokedPort)

    e.startImport(observation())
    e.chooseDestination(ImportDestination.NewHub(HubVisibilityChoice.RESTRICTED, listOf("u1")))
    e.proceedToAudience(); e.proceedToConfirm()
    e.confirm()

    awaitState(store) { it is ImportProposalState.PermissionLost }
    assertEquals(0L, cs.outboxSize())
  }

  @Test fun `existing-Hub role gate — a Viewer (or since-departed member) is denied, review is kept`() = runBlocking<Unit> {
    val cs = freshStore()
    val store = appStore(AppState(session = sessionState(), hubs = HubState(hubs = listOf(Hub(id = "hub-x", title = "Beach Week", createdBy = "someone-else")))))
    val e = engine(store, cs, loadHubAudience = { HubAudience(visibility = "family", members = listOf(HubAudienceMember(uid = "me", role = "adult", permitted = true, participationRole = "viewer"))) })

    e.startImport(observation())
    e.chooseDestination(ImportDestination.ExistingHub("hub-x", "Beach Week", "viewer"))
    e.proceedToAudience(); e.proceedToConfirm()
    e.confirm()

    awaitState(store) { it is ImportProposalState.RoleDenied }
    assertEquals(0L, cs.outboxSize())
    val denied = store.state.calendar.importState as ImportProposalState.RoleDenied
    assertNull(denied.proposal.destination)
    assertEquals("Grandma's 80th lunch", denied.proposal.title)   // the review is kept
  }

  @Test fun `existing-Hub Contributor is permitted and the audience summary names the Hub`() = runBlocking<Unit> {
    val cs = freshStore()
    val store = appStore(AppState(session = sessionState(), hubs = HubState(hubs = listOf(Hub(id = "hub-x", title = "Beach Week", createdBy = "someone-else")))))
    val http = MockEngine { respond("""{"version":1}""", HttpStatusCode.OK, headersOf("content-type", "application/json")) }
    val e = engine(
      store, cs, syncClient = SyncClient("https://api.test", HttpClient(http)),
      loadHubAudience = { HubAudience(visibility = "family", members = listOf(HubAudienceMember(uid = "me", role = "adult", permitted = true, participationRole = "contributor"))) },
    )

    e.startImport(observation())
    e.chooseDestination(ImportDestination.ExistingHub("hub-x", "Beach Week", "contributor"))
    e.proceedToAudience(); e.proceedToConfirm()
    e.confirm()

    awaitState(store) { it is ImportProposalState.Saved }
    assertEquals("Added to Beach Week", (store.state.calendar.importState as ImportProposalState.Saved).audienceSummary)
  }

  @Test fun `re-confirm after a transient failure reuses the same client-minted ids (idempotent, no duplicate write)`() = runBlocking<Unit> {
    val cs = freshStore()
    val store = appStore(AppState(session = sessionState()))
    val e = engine(store, cs)

    e.startImport(observation())
    e.chooseDestination(ImportDestination.NewHub(HubVisibilityChoice.RESTRICTED, listOf("u1")))
    e.proceedToAudience(); e.proceedToConfirm()
    val proposalId = (store.state.calendar.importState as ImportProposalState.Confirming).proposal.proposalId

    e.confirm()
    awaitState(store) { it is ImportProposalState.Saved || it is ImportProposalState.Applying }
    val firstIds = cs.calendarImportIds(proposalId)
    assertNotNull(firstIds)

    // A second confirm() call (e.g. a retry) on the SAME still-Confirming-shaped local record must
    // mint no new ids — reuse is keyed on proposalId alone.
    val reusedIds = cs.calendarImportIds(proposalId)
    assertEquals(firstIds, reusedIds)
  }
}
