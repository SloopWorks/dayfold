package com.sloopworks.dayfold.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HubEngineTest {
  private val jsonCt = headersOf(HttpHeaders.ContentType, "application/json")
  private class MemTokenStore(var session: Session? = null) : TokenStore {
    override fun load() = session
    override fun save(session: Session) { this.session = session }
    override fun clear() { session = null }
  }
  // store with an active family + session, so the engine isn't idle.
  private fun readyStore() = createAppStore(
    AppState(session = Session("ax", "rx"), activeFamilyId = "fam1", route = Route.Hubs), debug = false,
  )

  private fun freshContentStore() = ContentStore.create(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))

  private fun engine(
    store: org.reduxkotlin.Store<AppState>,
    handler: MockEngine,
    ts: MemTokenStore = MemTokenStore(Session("ax", "rx")),
    contentStore: ContentStore = freshContentStore(),
    syncEngine: SyncEngine? = null,
  ): HubEngine {
    val cs = contentStore
    val sc = SyncClient("https://api.test", { store.state.activeFamilyId }, { store.state.session?.access }, HttpClient(handler))
    val se = syncEngine ?: SyncEngine(store, cs, sc, nowProvider = { "2026-06-24T00:00:00Z" })
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    return HubEngine(store, HubClient("https://api.test", HttpClient(handler)), AuthClient("https://api.test", HttpClient(handler)), ts, cs, se, scope)
  }

  // poll the store until predicate or timeout
  private fun await(store: org.reduxkotlin.Store<AppState>, pred: (AppState) -> Boolean) {
    val deadline = System.currentTimeMillis() + 3000
    while (System.currentTimeMillis() < deadline) { if (pred(store.state)) return; Thread.sleep(20) }
    throw AssertionError("timed out; state=${store.state}")
  }

  // PR1: loadHubs is a no-op — the hub list is now DB-fed via the SyncEngine hub bridge.
  // The bridge (HubsLoaded from activeHubsFlow) is the sole writer of state.hubs.
  @Test fun `loadHubs is a no-op (hub list is DB-fed via the bridge)`() = runBlocking {
    val store = readyStore()
    var hit = false
    val e = engine(store, MockEngine { hit = true; respond("[]", HttpStatusCode.OK, jsonCt) })
    e.loadHubs()
    assertEquals(false, hit)               // no network call — bridge owns the list
    assertTrue(store.state.hubs.isEmpty()) // unchanged; bridge not started in this test
  }

  // PR2: openHub is now DB-fed. It dispatches OpenHub, triggers a sync, and subscribes
  // to contentStore.hubTreeFlow(hubId) → dispatches HubTreeLoaded when rows arrive.
  @Test fun `openHub dispatches OpenHub then HubTreeLoaded from DB`() = runBlocking {
    val store = readyStore()
    val cs = freshContentStore()
    // Pre-seed the DB with h1 tree
    cs.applyDelta(
      changedCards = emptyList(),
      changedHubs = listOf(Hub("h1", title = "Party", visibility = "family")),
      changedSections = listOf(HubSection("s1", hubId = "h1", title = "Details")),
      changedBlocks = listOf(HubBlock("b1", sectionId = "s1", type = "text", bodyMd = "hi")),
      tombstones = emptyList(), nextCursor = "c1", nowIso = "2026-06-24T00:00:00Z",
    )
    val e = engine(store, MockEngine { respond("{}", HttpStatusCode.OK, jsonCt) }, contentStore = cs)
    e.openHub("h1")
    // openHub dispatches OpenHub immediately
    assertEquals("h1", store.state.currentHubId)
    // The treeJob coroutine should dispatch HubTreeLoaded shortly
    await(store) { it.currentHubTree?.hub?.title == "Party" }
    assertEquals(listOf("s1"), store.state.currentHubTree?.sections?.map { it.id })
    assertEquals(listOf("b1"), store.state.currentHubTree?.blocks?.map { it.id })
  }

  // PR2: when the hub is absent from DB (was never synced or was tombstoned), the
  // flow emits null. HubNotFound is no longer dispatched (the network call is gone);
  // hubsBusy stays true until a sync delivers the tree.
  @Test fun `openHub with hub absent from DB stays busy (no HubNotFound)`() = runBlocking {
    val store = readyStore()
    val cs = freshContentStore()
    val e = engine(store, MockEngine { respond("{}", HttpStatusCode.OK, jsonCt) }, contentStore = cs)
    e.openHub("hX")
    assertEquals("hX", store.state.currentHubId)
    assertTrue(store.state.hubsBusy)  // still busy — no hub in DB yet
    assertNull(store.state.currentHubTree)
    assertNull(store.state.hubError)  // no error dispatched
  }

  @Test fun `closeHub clears the substate AND cancels the tree subscription`() = runBlocking {
    val store = readyStore()
    val cs = freshContentStore()
    cs.applyDelta(
      changedCards = emptyList(), changedHubs = listOf(Hub("h1", title = "Trip", visibility = "family")),
      changedSections = listOf(HubSection("s1", hubId = "h1", title = "X")),
      changedBlocks = listOf(HubBlock("b1", sectionId = "s1", type = "text", bodyMd = "v1")),
      tombstones = emptyList(), nextCursor = "c1", nowIso = "2026-06-24T00:00:00Z",
    )
    val e = engine(store, MockEngine { respond("{}", HttpStatusCode.OK, jsonCt) }, contentStore = cs)
    e.openHub("h1")
    await(store) { it.currentHubTree?.hub?.title == "Trip" }

    e.closeHub()
    assertNull(store.state.currentHubId)
    assertNull(store.state.currentHubTree)
    assertNull(store.state.hubFocusBlockId)

    // the tree subscription must be cancelled — a later DB write to h1 must NOT
    // re-dispatch HubTreeLoaded (else the coroutine leaks per hub open).
    cs.applyDelta(
      changedCards = emptyList(), changedHubs = listOf(Hub("h1", title = "Trip", visibility = "family")),
      changedSections = listOf(HubSection("s1", hubId = "h1", title = "X")),
      changedBlocks = listOf(HubBlock("b1", sectionId = "s1", type = "text", bodyMd = "v2")),
      tombstones = emptyList(), nextCursor = "c2", nowIso = "2026-06-24T00:01:00Z",
    )
    Thread.sleep(250)
    assertNull(store.state.currentHubTree)   // cancelled → no stray re-render
  }

  // Navigating hub1 -> hub2 must cancel hub1's tree subscription (openHub line:
  // `treeJob?.cancel()`), else a later DB write to hub1 would stray-dispatch
  // HubTreeLoaded(hub1) over the hub2 detail the user is now viewing (+ leak a
  // coroutine per navigation). closeHub's cancellation is tested; re-open is not.
  @Test fun `opening a different hub cancels the prior hub's tree subscription`() = runBlocking {
    val store = readyStore()
    val cs = freshContentStore()
    cs.applyDelta(
      changedCards = emptyList(),
      changedHubs = listOf(Hub("h1", title = "H1", visibility = "family"), Hub("h2", title = "H2", visibility = "family")),
      changedSections = listOf(HubSection("s1", hubId = "h1", title = "X"), HubSection("s2", hubId = "h2", title = "Y")),
      changedBlocks = listOf(HubBlock("b1", sectionId = "s1", type = "text", bodyMd = "h1v1"), HubBlock("b2", sectionId = "s2", type = "text", bodyMd = "h2v1")),
      tombstones = emptyList(), nextCursor = "c1", nowIso = "2026-06-24T00:00:00Z",
    )
    val e = engine(store, MockEngine { respond("{}", HttpStatusCode.OK, jsonCt) }, contentStore = cs)
    e.openHub("h1")
    await(store) { it.currentHubTree?.hub?.title == "H1" }
    e.openHub("h2")
    await(store) { it.currentHubTree?.hub?.title == "H2" }
    assertEquals("h2", store.state.currentHubId)

    // a later write to h1 must NOT pull the detail back to h1 — its subscription
    // was cancelled when we opened h2.
    cs.applyDelta(
      changedCards = emptyList(), changedHubs = listOf(Hub("h1", title = "H1", visibility = "family")),
      changedSections = listOf(HubSection("s1", hubId = "h1", title = "X")),
      changedBlocks = listOf(HubBlock("b1", sectionId = "s1", type = "text", bodyMd = "h1v2")),
      tombstones = emptyList(), nextCursor = "c2", nowIso = "2026-06-24T00:01:00Z",
    )
    Thread.sleep(250)
    assertEquals("H2", store.state.currentHubTree?.hub?.title)   // stays on h2; no stray h1 re-dispatch
  }

  @Test fun `openHub with a focus block sets the deep-link arrival highlight`() = runBlocking {
    val store = readyStore()
    val cs = freshContentStore()
    cs.applyDelta(
      changedCards = emptyList(), changedHubs = listOf(Hub("h1", title = "Party", visibility = "family")),
      changedSections = listOf(HubSection("s1", hubId = "h1", title = "X")),
      changedBlocks = listOf(HubBlock("b1", sectionId = "s1", type = "text", bodyMd = "hi")),
      tombstones = emptyList(), nextCursor = "c1", nowIso = "2026-06-24T00:00:00Z",
    )
    val e = engine(store, MockEngine { respond("{}", HttpStatusCode.OK, jsonCt) }, contentStore = cs)
    e.openHub("h1", focusBlockId = "b1")
    await(store) { it.currentHubTree != null }
    assertEquals("b1", store.state.hubFocusBlockId)  // SetHubFocus dispatched + survived HubTreeLoaded
  }

  @Test fun `idle with no family or session is a no-op`() = runBlocking {
    val store = createAppStore(debug = false)            // no session/family
    var hit = false
    val e = engine(store, MockEngine { hit = true; respond("[]", HttpStatusCode.OK, jsonCt) })
    e.loadHubs()
    assertEquals(false, hit)
    assertTrue(store.state.hubs.isEmpty())
  }

  // loadAudience: happy path — GET /audience → HubAudienceLoaded into currentHubAudience.
  @Test fun `loadAudience dispatches HubAudienceLoaded on success`() = runBlocking<Unit> {
    val store = readyStore()
    var calls = 0
    val e = engine(store, MockEngine { req ->
      when (req.url.encodedPath) {
        "/families/fam1/hubs/h1/audience" -> {
          calls++
          respond(
            """{"visibility":"restricted","members":[{"uid":"u1","display_name":"Alex","role":"adult","permitted":true}]}""",
            HttpStatusCode.OK, jsonCt,
          )
        }
        else -> respond("", HttpStatusCode.NotFound)
      }
    })
    e.loadAudience("h1")
    assertEquals(1, calls)
    assertEquals("restricted", store.state.currentHubAudience?.visibility)
    assertEquals(listOf("u1"), store.state.currentHubAudience?.members?.map { it.uid })
    assertEquals(true, store.state.currentHubAudience?.members?.single()?.permitted)
  }

  // loadAudience mirrors AuthEngine's 401 refresh-and-retry: a 401 on the audience
  // fetch rotates the token (persist + SessionRotated) and retries once transparently.
  @Test fun `loadAudience 401 refreshes once and retries`() = runBlocking<Unit> {
    val store = readyStore()                               // session = Session("ax","rx")
    val ts = MemTokenStore(Session("ax", "rx"))
    var calls = 0
    val e = engine(store, MockEngine { req ->
      when (req.url.encodedPath) {
        "/families/fam1/hubs/h1/audience" -> {
          calls++
          if (calls == 1) respond("expired", HttpStatusCode.Unauthorized)
          else respond("""{"visibility":"family","members":[]}""", HttpStatusCode.OK, jsonCt)
        }
        "/auth/refresh" -> respond("""{"access":"fresh","refresh":"r2"}""", HttpStatusCode.OK, jsonCt)
        else -> respond("", HttpStatusCode.NotFound)
      }
    }, ts = ts)
    e.loadAudience("h1")
    assertEquals(2, calls)                                 // retried after refresh
    assertEquals(Session("fresh", "r2"), store.state.session)  // rotated into state
    assertEquals(Session("fresh", "r2"), ts.session)       // and persisted
    assertEquals("family", store.state.currentHubAudience?.visibility)
  }

  // A failed refresh after a 401 is non-fatal: loadAudience swallows it (the sheet
  // shows a quiet empty/loading state) — no crash, no stale audience, no rotation.
  @Test fun `loadAudience swallows a failed refresh (quiet, non-fatal)`() = runBlocking<Unit> {
    val store = readyStore()
    val ts = MemTokenStore(Session("ax", "rx"))
    var calls = 0
    val e = engine(store, MockEngine { req ->
      when (req.url.encodedPath) {
        "/families/fam1/hubs/h1/audience" -> { calls++; respond("expired", HttpStatusCode.Unauthorized) }
        "/auth/refresh" -> respond("nope", HttpStatusCode.Unauthorized)   // refresh also fails
        else -> respond("", HttpStatusCode.NotFound)
      }
    }, ts = ts)
    e.loadAudience("h1")                                   // must not throw
    assertEquals(1, calls)                                 // no successful retry
    assertNull(store.state.currentHubAudience)             // nothing dispatched
    assertEquals(Session("ax", "rx"), store.state.session) // no rotation on failed refresh
  }

  @Test fun `loadAudience with no session is a no-op`() = runBlocking<Unit> {
    val store = createAppStore(debug = false)              // no session/family
    var hit = false
    val e = engine(store, MockEngine { hit = true; respond("", HttpStatusCode.NotFound) })
    e.loadAudience("h1")
    assertEquals(false, hit)                               // guarded before any network call
    assertNull(store.state.currentHubAudience)
  }

  // Slice 4 (ADR 0038) — toggleItem runs the optimistic apply + outbox enqueue through
  // ContentStore. A 500 backend means the kicked sync's inbound drain throws before the
  // egress runs, so the op stays pending and we can observe the enqueue deterministically.
  private fun seedChecklist(cs: ContentStore) = cs.applyDelta(
    changedCards = emptyList(),
    changedHubs = listOf(Hub("h1", title = "Party", visibility = "family")),
    changedSections = listOf(HubSection("s1", hubId = "h1", title = "Plan")),
    changedBlocks = listOf(HubBlock(id = "b1", sectionId = "s1", type = "checklist", ord = 0, version = 3,
      payload = BlockPayload(items = listOf(ChecklistItem(id = "i1", text = "Pack", done = false))))),
    tombstones = emptyList(), nextCursor = "c1", nowIso = "2026-06-29T00:00:00Z",
  )

  @Test fun `toggleItem optimistically flips the block to pending and queues one op`() = runBlocking<Unit> {
    val store = readyStore()
    val cs = freshContentStore(); seedChecklist(cs)
    val e = engine(store, MockEngine { respond("err", HttpStatusCode.InternalServerError) }, contentStore = cs)
    e.toggleItem("b1", "i1", done = true)
    assertEquals("pending", cs.blockLocalState("b1"))       // optimistic write flag is on
    assertEquals(1, cs.pendingOpCount())                    // exactly one coalesced egress op
  }

  // Slice 5b (ADR 0038 §W4) — deleteBlock runs the optimistic delete (mark 'pending'/Removing
  // + enqueue a "delete" op) through ContentStore, mirroring toggleItem. A 500 backend keeps the
  // op pending so the enqueue is observable.
  @Test fun `deleteBlock optimistically marks the block pending and queues one delete op`() = runBlocking<Unit> {
    val store = readyStore()
    val cs = freshContentStore(); seedChecklist(cs)
    val e = engine(store, MockEngine { respond("err", HttpStatusCode.InternalServerError) }, contentStore = cs)
    e.deleteBlock("b1")
    assertEquals("pending", cs.blockLocalState("b1"))        // "Removing…" — row stays visible
    val op = cs.nextPendingOp()!!
    assertEquals("delete", op.type)
    assertEquals("b1", op.targetId)
  }

  @Test fun `retryBlock re-arms a block parked failed back to pending`() = runBlocking<Unit> {
    val store = readyStore()
    val cs = freshContentStore(); seedChecklist(cs)
    cs.enqueueBlockToggle("b1", "i1", done = true, doneBy = "mom", nowIso = "2026-06-29T00:01:00Z", opId = "OP1")
    val op = cs.nextPendingOp()!!; cs.markOpInflight(op.opId); cs.failOp(op.opId, "b1")   // simulate cap-reached
    assertEquals("failed", cs.blockLocalState("b1"))
    val e = engine(store, MockEngine { respond("err", HttpStatusCode.InternalServerError) }, contentStore = cs)
    e.retryBlock("b1")
    assertEquals("pending", cs.blockLocalState("b1"))       // flipped back; op re-queued for the next drain
  }
}
