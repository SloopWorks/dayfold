package com.sloopworks.dayfold.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
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
  private fun engine(store: org.reduxkotlin.Store<AppState>, handler: MockEngine, ts: MemTokenStore = MemTokenStore(Session("ax", "rx"))) =
    HubEngine(store, HubClient("https://api.test", HttpClient(handler)), AuthClient("https://api.test", HttpClient(handler)), ts)

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

  @Test fun `openHub loads the tree`() = runBlocking {
    val store = readyStore()
    val e = engine(store, MockEngine {
      respond("""{"hub":{"id":"h1","title":"Party","visibility":"family"},"sections":[],"blocks":[]}""", HttpStatusCode.OK, jsonCt)
    })
    e.openHub("h1")
    assertEquals("h1", store.state.currentHubId)
    assertEquals("Party", store.state.currentHubTree?.hub?.title)
  }

  @Test fun `openHub on a 404 restricted-or-absent clears back to list with a note`() = runBlocking {
    val store = readyStore()
    val e = engine(store, MockEngine { respond("", HttpStatusCode.NotFound) })
    e.openHub("hX")
    assertNull(store.state.currentHubId)
    assertNull(store.state.currentHubTree)
    assertTrue(store.state.hubError != null)
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
