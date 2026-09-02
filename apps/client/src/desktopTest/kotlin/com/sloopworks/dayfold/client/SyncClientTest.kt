package com.sloopworks.dayfold.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.jsonPrimitive

class SyncClientTest {
  private fun client(engine: MockEngine) = SyncClient("https://api.test", HttpClient(engine))

  @Test fun `fetchPage parses the envelope and forwards since + auth`() = runBlocking {
    var seenSince: String? = "UNSET"; var seenAuth: String? = null
    val engine = MockEngine { req ->
      seenSince = req.url.parameters["since"]; seenAuth = req.headers[HttpHeaders.Authorization]
      respond(
        """{"changes":{"cards":[{"id":"a","title":"A"}]},"tombstones":[],"next_cursor":"c1","has_more":false}""",
        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
      )
    }
    val resp = client(engine).fetchPage("fam1", "sec", "cur0")
    assertEquals("a", resp.changes.cards[0].id)
    assertEquals("c1", resp.nextCursor)
    assertEquals("cur0", seenSince)
    assertEquals("Bearer sec", seenAuth)
  }

  // runBlocking<Unit>: assertFailsWith returns the caught Throwable, so without the
  // explicit Unit this method's return type is Throwable and JUnit silently skips it.
  @Test fun `fetchPage throws on non-200`() = runBlocking<Unit> {
    val engine = MockEngine { respond("nope", HttpStatusCode.InternalServerError) }
    assertFailsWith<Exception> { client(engine).fetchPage("fam1", "sec", null) }
  }

  @Test fun `fetchPage parses hubs and hub tombstones`() = runBlocking {
    val engine = MockEngine { respond(
      """{"changes":{"cards":[],"hubs":[{"id":"h1","type":"event","title":"Party","status":"active","updated_at":"t1"}]},
          "tombstones":[{"type":"hub","id":"h2"}],"next_cursor":"abc","has_more":false}""",
      HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
    val r = client(engine).fetchPage("fam1", "sec", null)
    assertEquals("h1", r.changes.hubs.single().id)
    assertEquals(Tombstone("hub", "h2"), r.tombstones.single())
  }

  @Test fun `fetchPage retains raw temporal facets`() = runBlocking {
    val engine = MockEngine { respond(
      """{"changes":{"cards":[{"id":"c1","title":"Night","temporal":{"occurrences":[{"id":"01K45ABCDEF0123456789GHJKM","role":"event","label":"Show","start":"2026-08-29T04:00:00Z","zone":"UTC","status":"confirmed"}],"future":"kept"}}]},"tombstones":[],"has_more":false}""",
      HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
    ) }
    val card = client(engine).fetchPage("fam1", "sec", null).changes.cards.single()
    assertEquals("kept", card.temporal?.get("future")?.jsonPrimitive?.content)
  }

  @Test fun `content mutation sends creation-time capability and Card If-Match`() = runBlocking {
    var capability: String? = null
    var ifMatch: String? = null
    val engine = MockEngine { req ->
      capability = req.headers["x-dayfold-content-capability"]
      ifMatch = req.headers["if-match"]
      respond("""{"version":8}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }
    val result = client(engine).putCard("fam1", "sec", "c1", "{}", 7, "op1", "temporal-v1")
    assertEquals("temporal-v1", capability)
    assertEquals("7", ifMatch)
    assertEquals(8, result.version)
  }

}
