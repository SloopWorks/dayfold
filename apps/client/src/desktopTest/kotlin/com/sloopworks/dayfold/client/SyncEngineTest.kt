package com.sloopworks.dayfold.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.reduxkotlin.Store
import org.reduxkotlin.StoreEnhancer

class SyncEngineTest {
  private fun freshStore() = ContentStore.create(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
  private fun syncClient(engine: MockEngine) =
    SyncClient("https://api.test", HttpClient(engine))
  private fun readyStore() = createTestAppStore(
    AppState(session = SessionState(session = Session("sec", "refresh"), activeFamilyId = "fam1")),
    debug = false,
  )
  private fun engine(cs: ContentStore, sc: SyncClient) =
    SyncEngine(readyStore(), cs, sc, nowProvider = { "2026-06-18T10:00:00Z" })

  private fun recordingStore(initial: AppState, actions: MutableList<Any>): Store<AppState> {
    val enhancer: StoreEnhancer<AppState> = { creator ->
      { reducer, state, nestedEnhancer ->
        val created = creator(reducer, state, nestedEnhancer)
        val downstream = created.dispatch
        created.dispatch = { action ->
          actions += action
          downstream(action)
        }
        created
      }
    }
    return createTestAppStore(initial = initial, debug = false, extraEnhancer = enhancer)
  }

  // poll the store until predicate or timeout (the bridge dispatches asynchronously)
  private fun await(store: org.reduxkotlin.Store<AppState>, pred: (AppState) -> Boolean) {
    val deadline = System.currentTimeMillis() + 3000
    while (System.currentTimeMillis() < deadline) { if (pred(store.state)) return; Thread.sleep(20) }
    throw AssertionError("timed out; state=${store.state}")
  }

  // Slice 6 (ADR 0040 §3) — a full_resync directive wipes the SYNCED cache + rebuilds from
  // the page, but PRESERVES the outbox (a staleness reset must not drop queued member writes)
  // and the local-only hidden set (the re-synced entities keep their personal hide). This is
  // NOT the tenancy-revocation wipe.
  @Test fun `full_resync rebuilds synced content but preserves the outbox and hidden set`() = runBlocking {
    val cs = freshStore()
    // pre-existing cached state: a stale (ghost) card + a checklist block + a hidden id + a queued write
    cs.applyDelta(
      listOf(Card("stale", title = "Ghost")), emptyList(), emptyList(),
      listOf(HubBlock(id = "b1", sectionId = "s1", type = "checklist", version = 1,
        payload = BlockPayload(items = listOf(ChecklistItem(id = "i1", text = "x", done = false))))),
      emptyList(), "oldcur", "2026-06-18T09:00:00Z",
    )
    cs.hide("h_local", "2026-06-18T09:00:00Z")
    cs.enqueueBlockToggle("b1", "i1", done = true, doneBy = "mom", nowIso = "2026-06-18T09:05:00Z", opId = "op1")
    assertEquals(1, cs.pendingOpCount())

    val sc = syncClient(MockEngine { req ->
      when {
        req.url.encodedPath.endsWith("/sync") ->
          respond("""{"changes":{"cards":[{"id":"fresh","title":"Rebuilt"}]},"tombstones":[],"next_cursor":"newcur","has_more":false,"full_resync":true}""",
            HttpStatusCode.OK, io.ktor.http.headersOf("content-type", "application/json"))
        else -> respond("", HttpStatusCode.InternalServerError)   // PUT 500 → op backs off, stays pending
      }
    })
    engine(cs, sc).syncNow()

    assertEquals(listOf("fresh"), cs.activeCards().map { it.id })   // ghost gone, rebuilt from the page
    assertEquals(1, cs.pendingOpCount())                            // outbox preserved → the write still flushes
    assertEquals(setOf("h_local"), cs.hiddenIdsFlow().first())      // local hide preserved (not a revocation)
  }

  @Test fun `cold start renders cached DB with zero network`() {
    val cs = freshStore()
    cs.applyDelta(listOf(Card("cached", title = "Cached")), emptyList(), emptyList(), emptyList(), emptyList(), "c0", "2026-06-18T09:00:00Z")
    var hit = false
    val sc = syncClient(MockEngine { hit = true; respond("", HttpStatusCode.OK) })
    val store = readyStore()
    SyncEngine(store, cs, sc).start()                 // bridge only — no sync
    await(store) { it.content.cards.map { c -> c.id } == listOf("cached") }
    assertFalse(hit)                                   // network never touched
  }

  @Test fun `syncNow drains pages, writes DB, surfaces in store, advances cursor with since`() = runBlocking {
    val cs = freshStore()
    val seen = mutableListOf<String?>()
    val sc = syncClient(MockEngine { req ->
      seen += req.url.parameters["since"]
      if (seen.size == 1)
        respond("""{"changes":{"cards":[{"id":"a","title":"A"}]},"tombstones":[],"next_cursor":"p1","has_more":true}""",
          HttpStatusCode.OK)
      else
        respond("""{"changes":{"cards":[{"id":"b","title":"B"}]},"tombstones":[],"next_cursor":"p2","has_more":false}""",
          HttpStatusCode.OK)
    })
    val store = readyStore()
    val e = SyncEngine(store, cs, sc, nowProvider = { "2026-06-18T10:00:00Z" })
    e.start(); e.syncNow()
    assertEquals(listOf("a", "b"), cs.activeCards().map { it.id })
    assertEquals("p2", cs.cursor())
    assertEquals(listOf(null, "p1"), seen)             // page 2 carried since=p1
    await(store) { it.content.cards.map { c -> c.id } == listOf("a", "b") }
    assertFalse(store.state.content.syncing)
  }

  @Test fun `tombstone removes from DB and store`() = runBlocking {
    val cs = freshStore()
    cs.applyDelta(listOf(Card("a", title = "A")), emptyList(), emptyList(), emptyList(), emptyList(), "c0", "2026-06-18T09:00:00Z")
    val sc = syncClient(MockEngine {
      respond("""{"changes":{"cards":[]},"tombstones":[{"type":"card","id":"a"}],"next_cursor":"c1","has_more":false}""",
        HttpStatusCode.OK)
    })
    val store = readyStore()
    val e = SyncEngine(store, cs, sc, nowProvider = { "2026-06-18T10:00:00Z" })
    e.start(); e.syncNow()
    assertTrue(cs.activeCards().isEmpty())
    await(store) { it.content.cards.isEmpty() }
  }

  // Data-boundary regression (the reported "butler hub" leak): the DB→store bridge
  // is the sole writer of state.hubs, so signing out must WIPE the local DB — not just
  // reset redux — or the next session re-projects the previous tenant's hubs. End-to-end:
  // seed a cached hub → bridge projects it → signOut(clearCache = cs.wipe) → DB is empty.
  @Test fun `sign-out wipes the local DB cache so no stale tenant hub leaks`() = runBlocking {
    val cs = freshStore()
    cs.applyDelta(emptyList(), listOf(Hub("butler", title = "Butler")), emptyList(), emptyList(), emptyList(), "c0", "2026-06-18T09:00:00Z")
    val store = createTestAppStore(AppState(session = SessionState(session = Session("a1", "r1"))), debug = false)
    SyncEngine(store, cs, syncClient(MockEngine { respond("", HttpStatusCode.OK) })).start()
    await(store) { it.hubs.hubs.map { h -> h.id } == listOf("butler") }   // bridge projected the cached hub

    val noopTokens = object : TokenStore {
      override fun load(): Session? = null
      override fun save(session: Session) {}
      override fun clear() {}
    }
    AuthEngine(
      store, AuthClient("https://api.test", HttpClient(MockEngine { respond("", HttpStatusCode.NoContent) })),
      tokenStore = noopTokens, clearCache = { cs.wipe() },
    ).signOut()

    // The DB itself is cleared — without the fix the cached "butler" hub persists here
    // (redux is reset by SignedOut regardless, so the DB is the assertion that matters).
    assertTrue(cs.activeHubsFlow().first().isEmpty())
  }

  @Test fun `cursor survives a restart (file DB reopen)`() {
    val f = File.createTempFile("fad-sync", ".db").apply { delete(); deleteOnExit() }
    val url = "jdbc:sqlite:${f.absolutePath}"
    val d1 = JdbcSqliteDriver(url); val s1 = ContentStore.create(d1)
    s1.applyDelta(listOf(Card("a", title = "A")), emptyList(), emptyList(), emptyList(), emptyList(), "cur42", "2026-06-18T10:00:00Z")
    d1.close()
    val d2 = JdbcSqliteDriver(url); val s2 = ContentStore(d2)   // reopen, no Schema.create
    assertEquals("cur42", s2.cursor())
    assertEquals(listOf("a"), s2.activeCards().map { it.id })
    d2.close()
  }

  @Test fun `syncNow surfaces failure as error status`() = runBlocking {
    val cs = freshStore()
    val sc = syncClient(MockEngine { respond("nope", HttpStatusCode.InternalServerError) })
    val store = readyStore()
    val e = SyncEngine(store, cs, sc, nowProvider = { "t" })
    e.syncNow()
    assertFalse(store.state.content.syncing)
    assertEquals("HTTP 500", store.state.content.error)
  }

  // ADR 0020: each page is its own atomic applyDelta, so a multi-page drain that
  // fails on a LATER page must keep the progress already committed — page 1's rows
  // + cursor survive, and the NEXT sync resumes from that cursor (no data lost, none
  // re-fetched from scratch). Only the failure is surfaced.
  @Test fun `syncNow preserves committed progress on mid-drain failure and resumes from it`() = runBlocking {
    val cs = freshStore()
    val seen = mutableListOf<String?>()
    val sc = syncClient(MockEngine { req ->
      seen += req.url.parameters["since"]
      when (seen.size) {
        1 -> respond("""{"changes":{"cards":[{"id":"a","title":"A"}]},"tombstones":[],"next_cursor":"p1","has_more":true}""",
          HttpStatusCode.OK)                                       // page 1 commits
        2 -> respond("boom", HttpStatusCode.InternalServerError)   // page 2 fails mid-drain
        else -> respond("""{"changes":{"cards":[{"id":"b","title":"B"}]},"tombstones":[],"next_cursor":"p2","has_more":false}""",
          HttpStatusCode.OK)                                       // resume delivers page 2
      }
    })
    val store = readyStore()
    val e = SyncEngine(store, cs, sc, nowProvider = { "2026-06-18T10:00:00Z" })

    e.syncNow()                                                    // drains p1, then page 2 500s
    assertEquals(listOf("a"), cs.activeCards().map { it.id })      // page-1 rows survived
    assertEquals("p1", cs.cursor())                                // cursor advanced to p1 only — not reset, not skipped
    assertEquals("HTTP 500", store.state.content.error)                    // failure surfaced

    e.syncNow()                                                    // resume
    assertEquals(listOf("a", "b"), cs.activeCards().map { it.id }) // page 2 now applied on top
    assertEquals("p2", cs.cursor())
    assertEquals(listOf(null, "p1", "p1"), seen)                   // resumed from p1, never re-fetched from scratch
  }

  // ADR 0030 round-1 P0-2: a removed member (403) / non-member (404) must not retain
  // family content — the cache is wiped and the session signs out.
  @Test fun `tenancy revocation (403) wipes the local cache and signs out`() = runBlocking {
    val cs = freshStore()
    cs.applyDelta(listOf(Card("a", title = "A")), emptyList(), emptyList(), emptyList(), emptyList(), "c0", "2026-06-18T09:00:00Z")
    assertEquals(listOf("a"), cs.activeCards().map { it.id })   // cache populated
    val sc = syncClient(MockEngine { respond("forbidden", HttpStatusCode.Forbidden) })
    val store = readyStore()
    val e = SyncEngine(store, cs, sc, nowProvider = { "t" })
    e.start(); e.syncNow()
    assertTrue(cs.activeCards().isEmpty())                       // cache wiped
    assertEquals(null, cs.cursor())                             // cursor cleared → re-sync clean
    await(store) { it.navigation.route == Route.SignIn && it.content.cards.isEmpty() }  // signed out
  }

  @Test fun `non-member (404) also wipes the cache`() = runBlocking {
    val cs = freshStore()
    cs.applyDelta(listOf(Card("a", title = "A")), emptyList(), emptyList(), emptyList(), emptyList(), "c0", "2026-06-18T09:00:00Z")
    val sc = syncClient(MockEngine { respond("nope", HttpStatusCode.NotFound) })
    val store = readyStore()
    SyncEngine(store, cs, sc, nowProvider = { "t" }).syncNow()
    assertTrue(cs.activeCards().isEmpty())
  }

  @Test fun `a stale old-family 403 cannot terminate the current family`() = runBlocking<Unit> {
    val requestStarted = CompletableDeferred<Unit>()
    val releaseRequest = CompletableDeferred<Unit>()
    val session = Session("access", "refresh", "user")
    val store = createTestAppStore(
      AppState(session = SessionState(session = session, activeFamilyId = "family-a"), navigation = NavigationState(route = Route.Feed)),
      debug = false,
    )
    val coordinator = SessionCoordinator(
      refreshScope = this,
      refreshSession = { error("refresh not expected") },
      commitRotation = {},
    )
    val auth = coordinator.install(session)
    coordinator.selectFamily(auth, "family-a")
    var invalidations = 0
    val engine = SyncEngine(
      store = store,
      contentStore = freshStore(),
      syncClient = syncClient(MockEngine {
        requestStarted.complete(Unit)
        releaseRequest.await()
        respond("forbidden", HttpStatusCode.Forbidden)
      }),
      suppliedSessionCoordinator = coordinator,
      onSessionInvalidated = { _, _ -> invalidations++ },
    )

    val sync = async { engine.syncNow() }
    requestStarted.await()
    coordinator.selectFamily(auth, "family-b")
    releaseRequest.complete(Unit)
    sync.await()

    assertEquals(0, invalidations)
    assertEquals(session, store.state.session.session)
  }

  @Test fun `a 401 does NOT wipe the cache (token problem, left to refresh)`() = runBlocking {
    val cs = freshStore()
    cs.applyDelta(listOf(Card("a", title = "A")), emptyList(), emptyList(), emptyList(), emptyList(), "c0", "2026-06-18T09:00:00Z")
    val sc = syncClient(MockEngine { respond("unauthorized", HttpStatusCode.Unauthorized) })
    val store = readyStore()
    SyncEngine(store, cs, sc, nowProvider = { "t" }).syncNow()
    assertEquals(listOf("a"), cs.activeCards().map { it.id })   // cache intact
    assertEquals("HTTP 401", store.state.content.error)
  }

  // ── Task 5 TDD: hub list is DB-fed via the SyncEngine bridge ──────────────────

  // (a) syncNow writes hubs to the DB and the bridge surfaces HubsLoaded
  @Test fun `syncNow writes hubs to the DB and the bridge surfaces HubsLoaded`(): Unit = runBlocking {
    val cs = freshStore()
    val appStore = readyStore()
    val sc = syncClient(MockEngine {
      respond(
        """{"changes":{"cards":[],"hubs":[{"id":"h1","title":"Party","visibility":"family"}]},"tombstones":[],"next_cursor":"p1","has_more":false}""",
        HttpStatusCode.OK
      )
    })
    val e = SyncEngine(appStore, cs, sc, nowProvider = { "2026-06-18T10:00:00Z" })
    e.start()
    e.syncNow()
    // Bridge surfaces HubsLoaded into the store (h1 in hubs list)
    await(appStore) { it.hubs.hubs.map { h -> h.id } == listOf("h1") }
    assertFalse(appStore.state.hubs.busy)
  }

  // (b) hub tombstone removes from the store + prunes currentHubId
  @Test fun `hub tombstone removes from store and prunes currentHubId`(): Unit = runBlocking {
    val cs = freshStore()
    cs.applyDelta(emptyList(), listOf(Hub("h1", title = "Party")), emptyList(), emptyList(), emptyList(), "c0", "2026-06-18T09:00:00Z")
    val appStore = readyStore()
    val sc = syncClient(MockEngine {
      respond(
        """{"changes":{"cards":[],"hubs":[]},"tombstones":[{"type":"hub","id":"h1"}],"next_cursor":"c1","has_more":false}""",
        HttpStatusCode.OK
      )
    })
    val e = SyncEngine(appStore, cs, sc, nowProvider = { "2026-06-18T10:00:00Z" })
    e.start()
    // Let the bridge populate hubs first
    await(appStore) { it.hubs.hubs.map { h -> h.id } == listOf("h1") }
    // Simulate the user having opened hub h1
    appStore.dispatch(OpenHub("h1", HubRequestKey(HubTenantGeneration(1L, 1L), 1L)))
    assertEquals("h1", appStore.state.hubs.currentHubId)
    // Sync delivers the tombstone
    e.syncNow()
    // Bridge emits [] → reducer prunes currentHubId
    await(appStore) { it.hubs.hubs.isEmpty() && it.hubs.currentHubId == null }
  }

  // (c) 403 wipes hubs too — bridge surfaces empty HubsLoaded + signs out
  @Test fun `403 wipes hubs and the bridge surfaces empty HubsLoaded then signs out`(): Unit = runBlocking {
    val cs = freshStore()
    cs.applyDelta(
      listOf(Card("a", title = "A")),
      listOf(Hub("h1", title = "Party")),
      emptyList(), emptyList(), emptyList(), "c0", "2026-06-18T09:00:00Z"
    )
    val appStore = readyStore()
    val sc = syncClient(MockEngine { respond("forbidden", HttpStatusCode.Forbidden) })
    val e = SyncEngine(appStore, cs, sc, nowProvider = { "t" })
    e.start()
    // Let the bridge emit the initial h1
    await(appStore) { it.hubs.hubs.map { h -> h.id } == listOf("h1") }
    // 403 → wipe() → bridge re-emits [] → SignedOut
    e.syncNow()
    await(appStore) { it.navigation.route == Route.SignIn && it.hubs.hubs.isEmpty() }
    // DB hub table must be empty (the flow is now empty)
    var dbHubs: List<Hub> = emptyList()
    val job = GlobalScope.launch {
      cs.activeHubsFlow().collect { dbHubs = it; throw CancellationException() }
    }
    delay(200); job.cancel()
    assertTrue(dbHubs.isEmpty())
  }

  // ── Task 11 TDD: section/block DB-fed tree ──────────────────────────────────

  @Test fun `syncNow with sections+blocks surfaces in hubTreeFlow`(): Unit = runBlocking {
    val cs = freshStore()
    val appStore = readyStore()
    val sc = syncClient(MockEngine {
      respond(
        """{"changes":{"cards":[],"hubs":[{"id":"h1","title":"Party","visibility":"family"}],"sections":[{"id":"s1","hub_id":"h1","title":"Details"}],"blocks":[{"id":"b1","section_id":"s1","type":"text","body_md":"hello"}]},"tombstones":[],"next_cursor":"p1","has_more":false}""",
        HttpStatusCode.OK
      )
    })
    val e = SyncEngine(appStore, cs, sc, nowProvider = { "2026-06-24T00:00:00Z" })
    e.start()
    e.syncNow()
    // DB should have sections + blocks now
    val tree = cs.hubTreeFlow("h1").first()
    assertNotNull(tree)
    assertEquals("h1", tree!!.hub.id)
    assertEquals(listOf("s1"), tree.sections.map { it.id })
    assertEquals(listOf("b1"), tree.blocks.map { it.id })
    assertEquals("hello", tree.blocks.first().bodyMd)
  }

  @Test fun `revoke tombstones hub+sections+blocks, flow clears`(): Unit = runBlocking {
    val cs = freshStore()
    cs.applyDelta(
      changedCards = emptyList(),
      changedHubs = listOf(Hub("h1", title = "Trip")),
      changedSections = listOf(HubSection("s1", hubId = "h1", title = "Info")),
      changedBlocks = listOf(HubBlock("b1", sectionId = "s1", type = "text")),
      tombstones = emptyList(), nextCursor = "c0", nowIso = "t0",
    )
    val tree0 = cs.hubTreeFlow("h1").first()
    assertNotNull(tree0)
    assertEquals(1, tree0!!.sections.size)

    // 403 → wipe() → everything gone
    val appStore = readyStore()
    val sc2 = syncClient(MockEngine { respond("forbidden", HttpStatusCode.Forbidden) })
    val e = SyncEngine(appStore, cs, sc2, nowProvider = { "t" })
    e.start(); e.syncNow()
    // After wipe, tree is null
    assertNull(cs.hubTreeFlow("h1").first())
  }

  // Root cause of "Couldn't refresh — showing saved cards": the 5-min access token
  // expires → /sync 401 → previously SyncFailed forever (no refresh). Now syncNow
  // refreshes + retries once, mirroring AuthEngine/HubEngine.
  @Test fun `syncNow refreshes the access token on 401 and retries`() = runBlocking<Unit> {
    val cs = freshStore()
    val store = createTestAppStore(AppState(session = SessionState(session = Session("stale", "r1"), activeFamilyId = "fam1")), debug = false)
    val ts = object : TokenStore { var s: Session? = null; override fun load() = s; override fun save(session: Session) { s = session }; override fun clear() { s = null } }
    var syncCalls = 0
    val mock = MockEngine { req ->
      when (req.url.encodedPath) {
        "/families/fam1/sync" -> {
          syncCalls++
          if (syncCalls == 1) respond("unauth", HttpStatusCode.Unauthorized)
          else respond("""{"changes":{"cards":[{"id":"a","title":"A"}]},"tombstones":[],"next_cursor":"p1","has_more":false}""", HttpStatusCode.OK)
        }
        "/auth/refresh" -> respond("""{"access":"fresh","refresh":"r2"}""", HttpStatusCode.OK)
        else -> respond("", HttpStatusCode.NotFound)
      }
    }
    val sc = SyncClient("https://api.test", HttpClient(mock))
    val e = SyncEngine(store, cs, sc, nowProvider = { "t" }, authClient = AuthClient("https://api.test", HttpClient(mock)), tokenStore = ts)
    e.syncNow()
    assertEquals(2, syncCalls)                                  // retried after refresh
    assertEquals(Session("fresh", "r2"), store.state.session.session)   // rotated into state
    assertEquals(Session("fresh", "r2"), ts.s)                  // and persisted
    assertEquals(listOf("a"), cs.activeCards().map { it.id })   // the retry page landed
    assertNull(store.state.content.error)
    assertFalse(store.state.content.syncing)
  }

  @Test fun `syncNow 401 with a rejected refresh expires the session and clears tenant cache`() = runBlocking<Unit> {
    val cs = freshStore()
    cs.applyDelta(listOf(Card("old", title = "Old")), emptyList(), emptyList(), emptyList(), emptyList(), "c0", "t0")
    val store = createTestAppStore(AppState(session = SessionState(session = Session("stale", "r1"), activeFamilyId = "fam1")), debug = false)
    val mock = MockEngine { req ->
      when (req.url.encodedPath) {
        "/families/fam1/sync" -> respond("unauth", HttpStatusCode.Unauthorized)
        "/auth/refresh" -> respond("nope", HttpStatusCode.Unauthorized)   // refresh also fails
        else -> respond("", HttpStatusCode.NotFound)
      }
    }
    val sc = SyncClient("https://api.test", HttpClient(mock))
    SyncEngine(store, cs, sc, nowProvider = { "t" }, authClient = AuthClient("https://api.test", HttpClient(mock))).syncNow()
    assertNull(store.state.session.session)
    assertEquals(Route.SignIn, store.state.navigation.route)
    assertEquals("Your session expired — please sign in again.", store.state.session.authError)
    assertTrue(cs.activeCards().isEmpty())
  }

  @Test fun `empty resume and conflated rerun emit no sync status actions`() = runBlocking<Unit> {
    val actions = Collections.synchronizedList(mutableListOf<Any>())
    val store = recordingStore(
      AppState(session = SessionState(session = Session("access", "refresh"), activeFamilyId = "fam1")),
      actions,
    )
    val firstRequest = CompletableDeferred<Unit>()
    val releaseFirst = CompletableDeferred<Unit>()
    var requests = 0
    val client = syncClient(MockEngine {
      requests++
      if (requests == 1) {
        firstRequest.complete(Unit)
        releaseFirst.await()
      }
      respond(
        """{"changes":{},"tombstones":[],"has_more":false}""",
        HttpStatusCode.OK,
      )
    })
    val engine = SyncEngine(store, freshStore(), client)
    val owner = SupervisorJob()
    val ownerScope = CoroutineScope(owner + Dispatchers.Default)
    val finished = Channel<Unit>(Channel.UNLIMITED)
    val coordinator = SyncCoordinator(syncPass = { reason, rerun ->
      engine.syncNow(reason, rerun)
      finished.send(Unit)
    }, pollIntervalMs = Long.MAX_VALUE)

    coordinator.resume(ownerScope)
    firstRequest.await()
    coordinator.requestSync(SyncReason.MANUAL_REFRESH)
    releaseFirst.complete(Unit)
    withTimeout(2_000) {
      finished.receive()
      finished.receive()
    }
    coordinator.pause()
    coordinator.close()
    owner.cancelAndJoin()

    assertEquals(2, requests)
    assertTrue(actions.none { it is SyncStarted || it is SyncSucceeded || it is SyncStopped })
  }

  @Test fun `a material poll emits one started and succeeded pair`() = runBlocking<Unit> {
    val actions = mutableListOf<Any>()
    val store = recordingStore(
      AppState(session = SessionState(session = Session("access", "refresh"), activeFamilyId = "fam1")),
      actions,
    )
    val client = syncClient(MockEngine {
      respond(
        """{"changes":{"cards":[{"id":"new","title":"New"}]},"tombstones":[],"has_more":false}""",
        HttpStatusCode.OK,
      )
    })

    SyncEngine(store, freshStore(), client).syncNow(SyncReason.POLL)

    assertEquals(1, actions.count { it is SyncStarted })
    assertEquals(1, actions.count { it is SyncSucceeded })
    assertEquals(0, actions.count { it is SyncFailed || it is SyncStopped })
  }

  @Test fun `cancellation after started clears busy without failure`() = runBlocking<Unit> {
    val actions = mutableListOf<Any>()
    val store = recordingStore(
      AppState(session = SessionState(session = Session("access", "refresh"), activeFamilyId = "fam1")),
      actions,
    )
    val requestStarted = CompletableDeferred<Unit>()
    val client = syncClient(MockEngine {
      requestStarted.complete(Unit)
      CompletableDeferred<Unit>().await()
      respond("", HttpStatusCode.OK)
    })
    val engine = SyncEngine(store, freshStore(), client)

    val running = async { engine.syncNow(SyncReason.MANUAL_REFRESH) }
    requestStarted.await()
    assertTrue(store.state.content.syncing)
    running.cancelAndJoin()

    assertFalse(store.state.content.syncing)
    assertEquals(1, actions.count { it is SyncStarted })
    assertEquals(1, actions.count { it is SyncStopped })
    assertEquals(0, actions.count { it is SyncFailed })
  }

  @Test fun `family invalidation before cancellation still clears the owned busy status`() = runBlocking<Unit> {
    val actions = mutableListOf<Any>()
    val session = Session("access", "refresh", "user")
    val store = recordingStore(
      AppState(session = SessionState(session = session, activeFamilyId = "family-a")),
      actions,
    )
    val sessionCoordinator = SessionCoordinator(
      refreshScope = this,
      refreshSession = { error("refresh not expected") },
      commitRotation = {},
    )
    val auth = sessionCoordinator.install(session)
    sessionCoordinator.selectFamily(auth, "family-a")
    val oldRequestStarted = CompletableDeferred<Unit>()
    val client = syncClient(MockEngine { request ->
      if (request.url.encodedPath.contains("family-a")) {
        oldRequestStarted.complete(Unit)
        CompletableDeferred<Unit>().await()
      }
      respond(
        """{"changes":{},"tombstones":[],"has_more":false}""",
        HttpStatusCode.OK,
      )
    })
    val engine = SyncEngine(
      store = store,
      contentStore = freshStore(),
      syncClient = client,
      suppliedSessionCoordinator = sessionCoordinator,
    )

    val oldPass = async { engine.syncNow(SyncReason.MANUAL_REFRESH) }
    oldRequestStarted.await()
    assertTrue(store.state.content.syncing)
    sessionCoordinator.selectFamily(auth, "family-b")
    store.dispatch(
      MembershipsLoaded(
        listOf(FamilyMembership("family-b", "Family B", role = "owner", status = "active")),
      ),
    )
    oldPass.cancelAndJoin()

    assertFalse(store.state.content.syncing)
    // Family replacement clears ContentState synchronously; the old request's
    // cancellation is stale and must not publish into the new family.
    assertEquals(0, actions.count { it is SyncStopped })
    assertEquals(0, actions.count { it is SyncFailed })
  }

  @Test fun `an old family cancellation cannot clear a newer family status`() = runBlocking<Unit> {
    val actions = mutableListOf<Any>()
    val session = Session("access", "refresh", "user")
    val store = recordingStore(
      AppState(session = SessionState(session = session, activeFamilyId = "family-a")),
      actions,
    )
    val sessionCoordinator = SessionCoordinator(
      refreshScope = this,
      refreshSession = { error("refresh not expected") },
      commitRotation = {},
    )
    val auth = sessionCoordinator.install(session)
    sessionCoordinator.selectFamily(auth, "family-a")
    val oldRequestStarted = CompletableDeferred<Unit>()
    val newRequestStarted = CompletableDeferred<Unit>()
    val client = syncClient(MockEngine { request ->
      when {
        request.url.encodedPath.contains("family-a") -> {
          oldRequestStarted.complete(Unit)
          CompletableDeferred<Unit>().await()
        }
        request.url.encodedPath.contains("family-b") -> {
          newRequestStarted.complete(Unit)
          CompletableDeferred<Unit>().await()
        }
      }
      respond("", HttpStatusCode.OK)
    })
    val engine = SyncEngine(
      store = store,
      contentStore = freshStore(),
      syncClient = client,
      suppliedSessionCoordinator = sessionCoordinator,
    )

    val oldPass = async { engine.syncNow(SyncReason.MANUAL_REFRESH) }
    oldRequestStarted.await()
    sessionCoordinator.selectFamily(auth, "family-b")
    store.dispatch(
      MembershipsLoaded(
        listOf(FamilyMembership("family-b", "Family B", role = "owner", status = "active")),
      ),
    )
    val newPass = async { engine.syncNow(SyncReason.MANUAL_REFRESH) }
    newRequestStarted.await()
    val stopsBeforeOldCancellation = actions.count { it is SyncStopped }

    oldPass.cancelAndJoin()
    assertTrue(store.state.content.syncing)
    assertEquals(stopsBeforeOldCancellation, actions.count { it is SyncStopped })

    newPass.cancelAndJoin()
    assertFalse(store.state.content.syncing)
    assertEquals(stopsBeforeOldCancellation + 1, actions.count { it is SyncStopped })
    assertEquals(0, actions.count { it is SyncFailed })
  }
}
