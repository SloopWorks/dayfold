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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HubClientTest {
  private val jsonCt = headersOf(HttpHeaders.ContentType, "application/json")
  private fun client(engine: MockEngine) = HubClient("https://api.test", HttpClient(engine))

  @Test fun `familyHubs parses the bare hub array + forwards auth`() = runBlocking {
    var auth: String? = null
    val engine = MockEngine { req ->
      auth = req.headers[HttpHeaders.Authorization]
      respond("""[{"id":"h1","type":"party-event","title":"Party","status":"active","visibility":"family"},
                  {"id":"h2","type":"medical","title":"Surgery","status":"active","visibility":"restricted"}]""",
        HttpStatusCode.OK, jsonCt)
    }
    val hubs = client(engine).familyHubs("ax", "fam1")
    assertEquals(listOf("h1", "h2"), hubs.map { it.id })
    assertEquals("restricted", hubs[1].visibility)
    assertEquals("Bearer ax", auth)
  }

  @Test fun `hubTree 200 → Loaded with sections + blocks`() = runBlocking {
    val engine = MockEngine {
      respond("""{"hub":{"id":"h1","title":"Party","visibility":"family"},
                  "sections":[{"id":"s1","hub_id":"h1","title":"Shopping","ord":0}],
                  "blocks":[{"id":"b1","section_id":"s1","type":"text","body_md":"Buy cake","ord":0}]}""",
        HttpStatusCode.OK, jsonCt)
    }
    val res = client(engine).hubTree("ax", "fam1", "h1")
    assertTrue(res is HubTreeResult.Loaded)
    res as HubTreeResult.Loaded
    assertEquals("Party", res.tree.hub.title)
    assertEquals(listOf("s1"), res.tree.sections.map { it.id })
    assertEquals("Buy cake", res.tree.blocks[0].bodyMd)
  }

  @Test fun `hubTree 404 → NotFound (restricted or deleted, omit-don't-403)`() = runBlocking {
    val engine = MockEngine { respond("", HttpStatusCode.NotFound) }
    assertEquals(HubTreeResult.NotFound, client(engine).hubTree("ax", "fam1", "hX"))
  }

  @Test fun `non-200 (not 404) throws AuthHttpException so the engine can refresh`() = runBlocking<Unit> {
    val engine = MockEngine { respond("nope", HttpStatusCode.Unauthorized) }
    val ex = assertFailsWith<AuthHttpException> { client(engine).familyHubs("ax", "fam1") }
    assertEquals(401, ex.status)
  }
}
