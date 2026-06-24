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

  @Test fun `idle with no family or session is a no-op`() = runBlocking {
    val store = createAppStore(debug = false)            // no session/family
    var hit = false
    val e = engine(store, MockEngine { hit = true; respond("[]", HttpStatusCode.OK, jsonCt) })
    e.loadHubs()
    assertEquals(false, hit)
    assertTrue(store.state.hubs.isEmpty())
  }
}
