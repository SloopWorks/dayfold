package com.sloopworks.dayfold.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncEngineTest {
  private fun freshStore() = ContentStore.create(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
  private fun syncClient(engine: MockEngine) =
    SyncClient("https://api.test", { "fam1" }, { "sec" }, HttpClient(engine))
  private fun engine(cs: ContentStore, sc: SyncClient) =
    SyncEngine(createAppStore(debug = false), cs, sc, nowProvider = { "2026-06-18T10:00:00Z" })

  // poll the store until predicate or timeout (the bridge dispatches asynchronously)
  private fun await(store: org.reduxkotlin.Store<AppState>, pred: (AppState) -> Boolean) {
    val deadline = System.currentTimeMillis() + 3000
    while (System.currentTimeMillis() < deadline) { if (pred(store.state)) return; Thread.sleep(20) }
    throw AssertionError("timed out; state=${store.state}")
  }

  @Test fun `cold start renders cached DB with zero network`() {
    val cs = freshStore()
    cs.applyDelta(listOf(Card("cached", title = "Cached")), emptyList(), emptyList(), emptyList(), emptyList(), "c0", "2026-06-18T09:00:00Z")
    var hit = false
    val sc = syncClient(MockEngine { hit = true; respond("", HttpStatusCode.OK) })
    val store = createAppStore(debug = false)
    SyncEngine(store, cs, sc).start()                 // bridge only — no sync
    await(store) { it.cards.map { c -> c.id } == listOf("cached") }
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
    val store = createAppStore(debug = false)
    val e = SyncEngine(store, cs, sc, nowProvider = { "2026-06-18T10:00:00Z" })
    e.start(); e.syncNow()
    assertEquals(listOf("a", "b"), cs.activeCards().map { it.id })
    assertEquals("p2", cs.cursor())
    assertEquals(listOf(null, "p1"), seen)             // page 2 carried since=p1
    await(store) { it.cards.map { c -> c.id } == listOf("a", "b") }
    assertFalse(store.state.syncing)
  }

  @Test fun `tombstone removes from DB and store`() = runBlocking {
    val cs = freshStore()
    cs.applyDelta(listOf(Card("a", title = "A")), emptyList(), emptyList(), emptyList(), emptyList(), "c0", "2026-06-18T09:00:00Z")
    val sc = syncClient(MockEngine {
      respond("""{"changes":{"cards":[]},"tombstones":[{"type":"card","id":"a"}],"next_cursor":"c1","has_more":false}""",
        HttpStatusCode.OK)
    })
    val store = createAppStore(debug = false)
    val e = SyncEngine(store, cs, sc, nowProvider = { "2026-06-18T10:00:00Z" })
    e.start(); e.syncNow()
    assertTrue(cs.activeCards().isEmpty())
    await(store) { it.cards.isEmpty() }
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
    val store = createAppStore(debug = false)
    val e = SyncEngine(store, cs, sc, nowProvider = { "t" })
    e.syncNow()
    assertFalse(store.state.syncing)
    assertEquals("HTTP 500", store.state.error)
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
    val store = createAppStore(debug = false)
    val e = SyncEngine(store, cs, sc, nowProvider = { "2026-06-18T10:00:00Z" })

    e.syncNow()                                                    // drains p1, then page 2 500s
    assertEquals(listOf("a"), cs.activeCards().map { it.id })      // page-1 rows survived
    assertEquals("p1", cs.cursor())                                // cursor advanced to p1 only — not reset, not skipped
    assertEquals("HTTP 500", store.state.error)                    // failure surfaced

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
    val store = createAppStore(debug = false)
    val e = SyncEngine(store, cs, sc, nowProvider = { "t" })
    e.start(); e.syncNow()
    assertTrue(cs.activeCards().isEmpty())                       // cache wiped
    assertEquals(null, cs.cursor())                             // cursor cleared → re-sync clean
    await(store) { it.route == Route.SignIn && it.cards.isEmpty() }  // signed out
  }

  @Test fun `non-member (404) also wipes the cache`() = runBlocking {
    val cs = freshStore()
    cs.applyDelta(listOf(Card("a", title = "A")), emptyList(), emptyList(), emptyList(), emptyList(), "c0", "2026-06-18T09:00:00Z")
    val sc = syncClient(MockEngine { respond("nope", HttpStatusCode.NotFound) })
    val store = createAppStore(debug = false)
    SyncEngine(store, cs, sc, nowProvider = { "t" }).syncNow()
    assertTrue(cs.activeCards().isEmpty())
  }

  @Test fun `a 401 does NOT wipe the cache (token problem, left to refresh)`() = runBlocking {
    val cs = freshStore()
    cs.applyDelta(listOf(Card("a", title = "A")), emptyList(), emptyList(), emptyList(), emptyList(), "c0", "2026-06-18T09:00:00Z")
    val sc = syncClient(MockEngine { respond("unauthorized", HttpStatusCode.Unauthorized) })
    val store = createAppStore(debug = false)
    SyncEngine(store, cs, sc, nowProvider = { "t" }).syncNow()
    assertEquals(listOf("a"), cs.activeCards().map { it.id })   // cache intact
    assertEquals("HTTP 401", store.state.error)
  }

  // ── Task 5 TDD: hub list is DB-fed via the SyncEngine bridge ──────────────────

  // (a) syncNow writes hubs to the DB and the bridge surfaces HubsLoaded
  @Test fun `syncNow writes hubs to the DB and the bridge surfaces HubsLoaded`(): Unit = runBlocking {
    val cs = freshStore()
    val appStore = createAppStore(debug = false)
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
    await(appStore) { it.hubs.map { h -> h.id } == listOf("h1") }
    assertFalse(appStore.state.hubsBusy)
  }

  // (b) hub tombstone removes from the store + prunes currentHubId
  @Test fun `hub tombstone removes from store and prunes currentHubId`(): Unit = runBlocking {
    val cs = freshStore()
    cs.applyDelta(emptyList(), listOf(Hub("h1", title = "Party")), emptyList(), emptyList(), emptyList(), "c0", "2026-06-18T09:00:00Z")
    val appStore = createAppStore(debug = false)
    val sc = syncClient(MockEngine {
      respond(
        """{"changes":{"cards":[],"hubs":[]},"tombstones":[{"type":"hub","id":"h1"}],"next_cursor":"c1","has_more":false}""",
        HttpStatusCode.OK
      )
    })
    val e = SyncEngine(appStore, cs, sc, nowProvider = { "2026-06-18T10:00:00Z" })
    e.start()
    // Let the bridge populate hubs first
    await(appStore) { it.hubs.map { h -> h.id } == listOf("h1") }
    // Simulate the user having opened hub h1
    appStore.dispatch(OpenHub("h1"))
    assertEquals("h1", appStore.state.currentHubId)
    // Sync delivers the tombstone
    e.syncNow()
    // Bridge emits [] → reducer prunes currentHubId
    await(appStore) { it.hubs.isEmpty() && it.currentHubId == null }
  }

  // (c) 403 wipes hubs too — bridge surfaces empty HubsLoaded + signs out
  @Test fun `403 wipes hubs and the bridge surfaces empty HubsLoaded then signs out`(): Unit = runBlocking {
    val cs = freshStore()
    cs.applyDelta(
      listOf(Card("a", title = "A")),
      listOf(Hub("h1", title = "Party")),
      emptyList(), emptyList(), emptyList(), "c0", "2026-06-18T09:00:00Z"
    )
    val appStore = createAppStore(debug = false)
    val sc = syncClient(MockEngine { respond("forbidden", HttpStatusCode.Forbidden) })
    val e = SyncEngine(appStore, cs, sc, nowProvider = { "t" })
    e.start()
    // Let the bridge emit the initial h1
    await(appStore) { it.hubs.map { h -> h.id } == listOf("h1") }
    // 403 → wipe() → bridge re-emits [] → SignedOut
    e.syncNow()
    await(appStore) { it.route == Route.SignIn && it.hubs.isEmpty() }
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
    val appStore = createAppStore(debug = false)
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
    val appStore = createAppStore(debug = false)
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
    val store = createAppStore(AppState(session = Session("stale", "r1"), activeFamilyId = "fam1"), debug = false)
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
    val sc = SyncClient("https://api.test", { "fam1" }, { store.state.session?.access }, HttpClient(mock))
    val e = SyncEngine(store, cs, sc, nowProvider = { "t" }, authClient = AuthClient("https://api.test", HttpClient(mock)), tokenStore = ts)
    e.syncNow()
    assertEquals(2, syncCalls)                                  // retried after refresh
    assertEquals(Session("fresh", "r2"), store.state.session)   // rotated into state
    assertEquals(Session("fresh", "r2"), ts.s)                  // and persisted
    assertEquals(listOf("a"), cs.activeCards().map { it.id })   // the retry page landed
    assertNull(store.state.error)
    assertFalse(store.state.syncing)
  }

  @Test fun `syncNow 401 with a failed refresh surfaces SyncFailed and keeps the cache`() = runBlocking<Unit> {
    val cs = freshStore()
    cs.applyDelta(listOf(Card("old", title = "Old")), emptyList(), emptyList(), emptyList(), emptyList(), "c0", "t0")
    val store = createAppStore(AppState(session = Session("stale", "r1"), activeFamilyId = "fam1"), debug = false)
    val mock = MockEngine { req ->
      when (req.url.encodedPath) {
        "/families/fam1/sync" -> respond("unauth", HttpStatusCode.Unauthorized)
        "/auth/refresh" -> respond("nope", HttpStatusCode.Unauthorized)   // refresh also fails
        else -> respond("", HttpStatusCode.NotFound)
      }
    }
    val sc = SyncClient("https://api.test", { "fam1" }, { store.state.session?.access }, HttpClient(mock))
    SyncEngine(store, cs, sc, nowProvider = { "t" }, authClient = AuthClient("https://api.test", HttpClient(mock))).syncNow()
    assertEquals("HTTP 401", store.state.error)                 // surfaced (non-destructive)
    assertEquals(listOf("old"), cs.activeCards().map { it.id }) // cache intact — NOT wiped
    assertEquals(Session("stale", "r1"), store.state.session)   // unchanged
  }
}
