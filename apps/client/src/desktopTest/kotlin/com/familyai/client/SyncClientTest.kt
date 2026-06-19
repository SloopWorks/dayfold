package com.familyai.client

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

class SyncClientTest {
  private fun client(engine: MockEngine) =
    SyncClient("https://api.test", "fam1", "sec", HttpClient(engine))

  @Test fun `fetchPage parses the envelope and forwards since + auth`() = runBlocking {
    var seenSince: String? = "UNSET"; var seenAuth: String? = null
    val engine = MockEngine { req ->
      seenSince = req.url.parameters["since"]; seenAuth = req.headers[HttpHeaders.Authorization]
      respond(
        """{"changes":{"cards":[{"id":"a","title":"A"}]},"tombstones":[],"next_cursor":"c1","has_more":false}""",
        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
      )
    }
    val resp = client(engine).fetchPage("cur0")
    assertEquals("a", resp.changes.cards[0].id)
    assertEquals("c1", resp.nextCursor)
    assertEquals("cur0", seenSince)
    assertEquals("Bearer sec", seenAuth)
  }

  @Test fun `fetchPage throws on non-200`() = runBlocking {
    val engine = MockEngine { respond("nope", HttpStatusCode.InternalServerError) }
    assertFailsWith<Exception> { client(engine).fetchPage(null) }
  }
}
