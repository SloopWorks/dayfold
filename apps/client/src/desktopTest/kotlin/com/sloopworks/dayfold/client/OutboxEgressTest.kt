package com.sloopworks.dayfold.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.charsets.Charsets
import io.ktor.utils.io.core.toByteArray
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ADR 0038 §5–§6 — the egress path end-to-end, headless: optimistic toggle → outbox →
// whole-block PUT (If-Match + Idempotency-Key) → ack → inbound echo clears the pending
// flag and drops the op. Plus the 412 re-merge → retry → converge path.
class OutboxEgressTest {
  private val jsonHdr = headersOf("content-type", listOf("application/json"))
  private fun store() = ContentStore.create(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
  private fun client(engine: MockEngine) = SyncClient("https://api.test", { "fam1" }, { "tok" }, HttpClient(engine))
  private fun engine(cs: ContentStore, sc: SyncClient) =
    SyncEngine(createAppStore(debug = false), cs, sc, nowProvider = { "2026-06-29T10:00:00Z" })

  private fun block(done: Boolean, version: Long, doneBy: String? = null, doneAt: String? = null) =
    HubBlock(id = "b1", sectionId = "s1", type = "checklist", version = version,
      payload = BlockPayload(items = listOf(ChecklistItem(id = "i1", text = "Pack", done = done, doneBy = doneBy, doneAt = doneAt))))
  private fun seed(cs: ContentStore, b: HubBlock, cursor: String) =
    cs.applyDelta(emptyList(), emptyList(), emptyList(), listOf(b), emptyList(), cursor, "2026-06-29T09:00:00Z")
  private fun bodyText(req: HttpRequestData): String =
    (req.body as io.ktor.http.content.TextContent).text

  @Test fun `toggle → PUT with If-Match + Idempotency-Key → ack → echo clears pending`() = runBlocking {
    val cs = store()
    seed(cs, block(done = false, version = 1), "c0")
    cs.enqueueBlockToggle("b1", "i1", done = true, doneBy = "mom", nowIso = "2026-06-29T10:00:00Z", opId = "op1")
    assertEquals("pending", cs.blockLocalState("b1"))
    assertEquals(1, cs.pendingOpCount())

    val puts = mutableListOf<Pair<String?, String>>()
    val sc = client(MockEngine { req ->
      when {
        req.url.encodedPath.endsWith("/sync") ->
          respond("""{"changes":{},"tombstones":[],"has_more":false}""", HttpStatusCode.OK, jsonHdr)
        req.method == HttpMethod.Put -> {
          assertEquals("op1", req.headers["idempotency-key"])
          puts += req.headers["if-match"] to bodyText(req)
          respond("""{"id":"b1","version":2}""", HttpStatusCode.OK, jsonHdr)
        }
        else -> respond("", HttpStatusCode.OK)
      }
    })
    engine(cs, sc).syncNow()

    assertEquals(1, puts.size)
    assertEquals("1", puts[0].first)                       // If-Match = the base version
    assertTrue(puts[0].second.contains("\"done\":true"), "PUT body carries the toggle: ${puts[0].second}")
    assertEquals(0, cs.pendingOpCount())                   // drained (acked, awaiting echo)

    // the server echoes b1@v2 (done=true) on the next /sync → drop the acked op + clear pending
    seed(cs, block(done = true, version = 2, doneBy = "mom", doneAt = "2026-06-29T10:00:00Z"), "c1")
    assertEquals(0L, cs.outboxSize())                      // acked op removed on echo
    assertNull(cs.blockLocalState("b1"))                   // back to synced
  }

  // ── Slice 5b (ADR 0038 §W4) — client delete egress ──────────────────────────
  @Test fun `enqueueBlockDelete marks the block pending (Removing…) and queues a delete op`() {
    val cs = store()
    seed(cs, block(done = false, version = 3), "c0")
    cs.enqueueBlockDelete("b1", nowIso = "2026-06-29T10:00:00Z", opId = "del1")
    assertEquals("pending", cs.blockLocalState("b1"))        // optimistic "Removing…" — row stays visible
    val op = cs.nextPendingOp()!!
    assertEquals("delete", op.type)
    assertEquals("b1", op.targetId)
    assertEquals("del1", op.opId)
    assertEquals("block", op.targetKind)
  }

  @Test fun `delete → DELETE with Idempotency-Key (no If-Match) → 204 ack → tombstone removes the row`() = runBlocking {
    val cs = store()
    seed(cs, block(done = false, version = 3), "c0")
    cs.enqueueBlockDelete("b1", nowIso = "2026-06-29T10:00:00Z", opId = "del1")

    val deletes = mutableListOf<Triple<String?, String?, String>>()   // idempotency-key, if-match, path
    var syncServed = false
    val sc = client(MockEngine { req ->
      when {
        req.url.encodedPath.endsWith("/sync") -> {
          // after the DELETE acks, the server tombstones b1 on the next page
          val page = if (syncServed) """{"changes":{},"tombstones":[{"type":"block","id":"b1"}],"has_more":false}"""
            else """{"changes":{},"tombstones":[],"has_more":false}"""
          syncServed = true
          respond(page, HttpStatusCode.OK, jsonHdr)
        }
        req.method == HttpMethod.Delete -> {
          deletes += Triple(req.headers["idempotency-key"], req.headers["if-match"], req.url.encodedPath)
          respond("", HttpStatusCode.NoContent)
        }
        else -> respond("", HttpStatusCode.OK)
      }
    })
    engine(cs, sc).syncNow()

    assertEquals(1, deletes.size)
    assertEquals("del1", deletes[0].first)                   // Idempotency-Key = op id
    assertNull(deletes[0].second)                            // no If-Match on delete
    assertTrue(deletes[0].third.endsWith("/blocks/b1"), "path: ${deletes[0].third}")
    assertEquals(0, cs.pendingOpCount())                     // drained (204 acked)

    // the server tombstones b1 → the next /sync removes the row from the cache
    engine(cs, sc).syncNow()
    assertNull(cs.blockLocalState("b1"))                     // gone (marked deleted)
  }

  @Test fun `412 re-merge — a concurrent loop edit bumps the base, the toggle re-bases and converges`() = runBlocking {
    val cs = store()
    seed(cs, block(done = false, version = 1), "c0")
    cs.enqueueBlockToggle("b1", "i1", done = true, doneBy = "mom", nowIso = "2026-06-29T10:00:00Z", opId = "op2")

    val ifMatches = mutableListOf<String?>()
    var syncServed = false
    val sc = client(MockEngine { req ->
      when {
        req.url.encodedPath.endsWith("/sync") -> {
          // the inbound drain delivers the loop's fresh edit: b1 advanced to v2 (text bumped)
          val page = if (!syncServed) {
            syncServed = true
            """{"changes":{"blocks":[{"id":"b1","section_id":"s1","type":"checklist","version":2,"payload":{"items":[{"id":"i1","text":"Pack jackets","done":false}]}}]},"tombstones":[],"has_more":false}"""
          } else """{"changes":{},"tombstones":[],"has_more":false}"""
          respond(page, HttpStatusCode.OK, jsonHdr)
        }
        req.method == HttpMethod.Put -> {
          ifMatches += req.headers["if-match"]
          // base v1 is stale (server is at v2) → 412; the re-based PUT at v2 succeeds → v3
          if (req.headers["if-match"] == "1") respond("", HttpStatusCode.PreconditionFailed)
          else respond("""{"id":"b1","version":3}""", HttpStatusCode.OK, jsonHdr)
        }
        else -> respond("", HttpStatusCode.OK)
      }
    })
    engine(cs, sc).syncNow()

    assertEquals(listOf<String?>("1", "2"), ifMatches)     // stale v1 → 412, re-based v2 → 200
    assertEquals(0, cs.pendingOpCount())                   // converged (acked)
    // the merge kept the member's toggle on top of the loop's fresh text
    assertEquals("pending", cs.blockLocalState("b1"))      // still pending until the echo
  }
}
