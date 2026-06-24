package com.sloopworks.dayfold.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    cs.applyDelta(listOf(Card("cached", title = "Cached")), emptyList(), "c0", "2026-06-18T09:00:00Z")
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
    cs.applyDelta(listOf(Card("a", title = "A")), emptyList(), "c0", "2026-06-18T09:00:00Z")
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
    s1.applyDelta(listOf(Card("a", title = "A")), emptyList(), "cur42", "2026-06-18T10:00:00Z")
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

  // ADR 0030 round-1 P0-2: a removed member (403) / non-member (404) must not retain
  // family content — the cache is wiped and the session signs out.
  @Test fun `tenancy revocation (403) wipes the local cache and signs out`() = runBlocking {
    val cs = freshStore()
    cs.applyDelta(listOf(Card("a", title = "A")), emptyList(), "c0", "2026-06-18T09:00:00Z")
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
    cs.applyDelta(listOf(Card("a", title = "A")), emptyList(), "c0", "2026-06-18T09:00:00Z")
    val sc = syncClient(MockEngine { respond("nope", HttpStatusCode.NotFound) })
    val store = createAppStore(debug = false)
    SyncEngine(store, cs, sc, nowProvider = { "t" }).syncNow()
    assertTrue(cs.activeCards().isEmpty())
  }

  @Test fun `a 401 does NOT wipe the cache (token problem, left to refresh)`() = runBlocking {
    val cs = freshStore()
    cs.applyDelta(listOf(Card("a", title = "A")), emptyList(), "c0", "2026-06-18T09:00:00Z")
    val sc = syncClient(MockEngine { respond("unauthorized", HttpStatusCode.Unauthorized) })
    val store = createAppStore(debug = false)
    SyncEngine(store, cs, sc, nowProvider = { "t" }).syncNow()
    assertEquals(listOf("a"), cs.activeCards().map { it.id })   // cache intact
    assertEquals("HTTP 401", store.state.error)
  }
}
