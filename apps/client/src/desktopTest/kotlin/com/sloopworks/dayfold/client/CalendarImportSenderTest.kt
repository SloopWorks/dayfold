package com.sloopworks.dayfold.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * spec §3.1 build-step-4/5 — the outbox sender's "hub"/"section" dispatch arms (a two-branch
 * addition to the existing block PUT arm), and §3.1's depends_on cascade-drop: a 403 on the hub op
 * must not leave the section/block ops to trickle out one 409 at a time — nothing has left the
 * device for a not-yet-sent dependent, so dropping them is a pure local delete.
 */
class CalendarImportSenderTest {
  private fun freshStore() = ContentStore.create(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
  private fun syncClient(engine: MockEngine) = SyncClient("https://api.test", HttpClient(engine))
  private fun readyStore() = createTestAppStore(
    AppState(session = SessionState(session = Session("sec", "refresh"), activeFamilyId = "fam1")),
    debug = false,
  )
  private fun engine(cs: ContentStore, sc: SyncClient) =
    SyncEngine(readyStore(), cs, sc, nowProvider = { "2026-08-09T10:00:00Z" })

  private fun ops() = listOf(
    MaterializedOp("op-hub", "hub", "hub-1", "upsertHub", """{"id":"hub-1","type":"event","title":"T","visibility":"restricted","audience":["u1"],"start_at":"2026-06-28T12:00:00-07:00"}""", null),
    MaterializedOp("op-sec", "section", "sec-1", "upsertSection", """{"hubId":"hub-1","id":"sec-1","title":"From your calendar"}""", "op-hub"),
    MaterializedOp("op-blk", "block", "blk-1", "upsertBlock", """{"sectionId":"sec-1","type":"milestone","payload":{"date":"2026-06-28T12:00:00-07:00","label":"T"},"provenance":{"source":"calendar","at":"2026-08-09T10:00:00Z"}}""", "op-sec"),
  )

  @Test fun `hub and section ops PUT to the correct routes and ack removes nothing pending`() = runBlocking<Unit> {
    val cs = freshStore()
    cs.enqueueImportOps(ops(), "2026-08-09T10:00:00Z")
    val seenPaths = mutableListOf<String>()
    val sc = syncClient(MockEngine { req ->
      seenPaths += req.url.encodedPath
      respond("""{"version":1}""", HttpStatusCode.OK, headersOf("content-type", "application/json"))
    })
    engine(cs, sc).syncNow()

    assertTrue(seenPaths.contains("/families/fam1/hubs/hub-1"), "hub op did not PUT the hub route: $seenPaths")
    assertTrue(seenPaths.contains("/families/fam1/sections/sec-1"), "section op did not PUT the section route: $seenPaths")
    assertTrue(seenPaths.contains("/families/fam1/blocks/blk-1"), "block op did not PUT the block route: $seenPaths")
    assertEquals(0, cs.pendingOpCount())
    assertEquals(mapOf("op-hub" to "acked", "op-sec" to "acked", "op-blk" to "acked"), cs.outboxOpStates(listOf("op-hub", "op-sec", "op-blk")))
  }

  @Test fun `a 403 on the hub op cascade-drops the section and block ops — no half-written import`() = runBlocking<Unit> {
    val cs = freshStore()
    cs.enqueueImportOps(ops(), "2026-08-09T10:00:00Z")
    var hubAttempted = false
    val sc = syncClient(MockEngine { req ->
      if (req.url.encodedPath == "/families/fam1/hubs/hub-1") {
        hubAttempted = true
        respond("""{"type":"forbidden"}""", HttpStatusCode.Forbidden)
      } else {
        // The cascade-drop must remove the section/block ops BEFORE the sender ever reaches them —
        // if this branch is hit the test fails via the path assertion below.
        respond("""{"version":1}""", HttpStatusCode.OK, headersOf("content-type", "application/json"))
      }
    })
    engine(cs, sc).syncNow()

    assertTrue(hubAttempted)
    assertEquals(0, cs.pendingOpCount())
    assertEquals(0L, cs.outboxSize())   // hub Dropped + section/block cascade-dropped — nothing left at all
    val states = cs.outboxOpStates(listOf("op-hub", "op-sec", "op-blk"))
    assertNull(states["op-hub"]); assertNull(states["op-sec"]); assertNull(states["op-blk"])
  }

  @Test fun `cascadeDropDependents walks a multi-level chain (hub to section to two blocks)`() {
    val cs = freshStore()
    cs.enqueueImportOps(
      listOf(
        MaterializedOp("op-hub", "hub", "hub-1", "upsertHub", "{}", null),
        MaterializedOp("op-sec", "section", "sec-1", "upsertSection", "{}", "op-hub"),
        MaterializedOp("op-blk1", "block", "blk-1", "upsertBlock", "{}", "op-sec"),
        MaterializedOp("op-blk2", "block", "blk-2", "upsertBlock", "{}", "op-sec"),
      ),
      "2026-08-09T10:00:00Z",
    )
    cs.dropOp("op-hub", "hub-1")
    cs.cascadeDropDependents("op-hub")
    assertEquals(0L, cs.outboxSize())
  }

  @Test fun `cascadeDropDependents on an op with no dependents is a harmless no-op`() {
    val cs = freshStore()
    cs.enqueueImportOps(listOf(MaterializedOp("op-blk", "block", "blk-1", "toggle", "{}", null)), "2026-08-09T10:00:00Z")
    cs.cascadeDropDependents("op-blk")
    assertEquals(1L, cs.outboxSize())   // the op itself is untouched — cascade only removes DEPENDENTS
  }

  @Test fun `enqueueImportOps is idempotent — a re-confirm with the same op ids does not duplicate rows`() {
    val cs = freshStore()
    cs.enqueueImportOps(ops(), "2026-08-09T10:00:00Z")
    cs.enqueueImportOps(ops(), "2026-08-09T10:00:05Z")   // re-confirm after source-changed/version-conflict
    assertEquals(3L, cs.outboxSize())
  }

  @Test fun `calendarImportIds round-trips the minted ids for retry-safe reuse`() {
    val cs = freshStore()
    cs.upsertCalendarImport(
      proposalId = "prop-1", proposalJson = "", destinationJson = "",
      hubId = "hub-1", sectionId = "sec-1", blockIds = listOf("blk-1", "blk-2"),
      status = "applying", nowIso = "2026-08-09T10:00:00Z",
    )
    assertEquals(ImportOpIds("hub-1", "sec-1", listOf("blk-1", "blk-2")), cs.calendarImportIds("prop-1"))
    assertNull(cs.calendarImportIds("prop-missing"))
  }

  @Test fun `sign-out wipes an in-flight import's local proposal record`() {
    val cs = freshStore()
    cs.upsertCalendarImport("prop-1", "", "", "hub-1", "sec-1", listOf("blk-1"), "applying", "2026-08-09T10:00:00Z")
    cs.wipe()
    assertNull(cs.calendarImportIds("prop-1"))
  }
}
