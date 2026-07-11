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
import kotlin.test.assertNull
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

  @Test fun `audience parses roster + permitted flags`() = runBlocking {
    val engine = MockEngine {
      respond("""{"visibility":"restricted","members":[
        {"uid":"u1","display_name":"Pat","role":"owner","permitted":true},
        {"uid":"u2","display_name":"Jordan","role":"adult","permitted":false}]}""", HttpStatusCode.OK, jsonCt)
    }
    val aud = client(engine).audience("ax", "fam1", "h1")
    assertEquals("restricted", aud.visibility)
    assertEquals(true, aud.members.first { it.uid == "u1" }.permitted)
    assertEquals(false, aud.members.first { it.uid == "u2" }.permitted)
  }

  @Test fun `audience parses avatar_color and avatar_ref`() = runBlocking {
    val engine = MockEngine {
      respond(
        """{"visibility":"family","members":[
          {"uid":"u1","display_name":"Pat","avatar_color":"teal","avatar_ref":"avatar:fox-01","role":"owner","permitted":true},
          {"uid":"u2","display_name":"Jordan","role":"adult","permitted":false}]}""", HttpStatusCode.OK, jsonCt)
    }
    val aud = client(engine).audience("ax", "fam1", "h1")
    assertEquals("teal", aud.members.first { it.uid == "u1" }.avatarColor)
    assertEquals("avatar:fox-01", aud.members.first { it.uid == "u1" }.avatarRef)
    assertNull(aud.members.first { it.uid == "u2" }.avatarColor)
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

  // audience feeds the visibility sheet; a 401 must THROW so HubEngine.loadAudience's
  // callWithRefresh rotates the token + retries (covered in HubEngineTest).
  @Test fun `audience throws AuthHttpException on a non-200 (engine refresh trigger)`() = runBlocking<Unit> {
    val engine = MockEngine { respond("unauth", HttpStatusCode.Unauthorized) }
    val ex = assertFailsWith<AuthHttpException> { client(engine).audience("ax", "fam1", "h1") }
    assertEquals(401, ex.status)
  }

  @Test fun `audience parses participation_role and can_manage`() = runBlocking {
    val engine = MockEngine {
      respond(
        """{"visibility":"restricted","can_manage":true,"members":[
          {"uid":"u1","display_name":"Pat","role":"owner","permitted":true,"participation_role":"co_owner"},
          {"uid":"u2","display_name":"Jordan","role":"adult","permitted":false}]}""", HttpStatusCode.OK, jsonCt,
      )
    }
    val aud = client(engine).audience("ax", "fam1", "h1")
    assertEquals(true, aud.canManage)
    assertEquals("co_owner", aud.members.first { it.uid == "u1" }.participationRole)
    assertNull(aud.members.first { it.uid == "u2" }.participationRole)
  }

  // ── participant/visibility mutation (ADR 0053 DC2/DC4) ────────────────────────

  @Test fun `setParticipant issues PUT participants{uid} with a role body`() = runBlocking {
    var method: String? = null; var path: String? = null; var body: String? = null; var auth: String? = null
    val engine = MockEngine { req ->
      method = req.method.value; path = req.url.encodedPath; auth = req.headers[HttpHeaders.Authorization]
      body = (req.body as io.ktor.http.content.TextContent).text
      respond("{}", HttpStatusCode.OK, jsonCt)
    }
    client(engine).setParticipant("ax", "fam1", "h1", "u2", "contributor")
    assertEquals("PUT", method)
    assertEquals("/families/fam1/hubs/h1/participants/u2", path)
    assertEquals("Bearer ax", auth)
    assertEquals("""{"role":"contributor"}""", body)
  }

  @Test fun `setParticipant throws AuthHttpException on a non-200`() = runBlocking<Unit> {
    val engine = MockEngine { respond("nope", HttpStatusCode.Forbidden) }
    val ex = assertFailsWith<AuthHttpException> { client(engine).setParticipant("ax", "fam1", "h1", "u2", "viewer") }
    assertEquals(403, ex.status)
  }

  @Test fun `removeParticipant issues DELETE participants{uid}`() = runBlocking {
    var method: String? = null; var path: String? = null
    val engine = MockEngine { req -> method = req.method.value; path = req.url.encodedPath; respond("", HttpStatusCode.NoContent) }
    client(engine).removeParticipant("ax", "fam1", "h1", "u2")
    assertEquals("DELETE", method)
    assertEquals("/families/fam1/hubs/h1/participants/u2", path)
  }

  @Test fun `removeParticipant throws AuthHttpException on a non-2xx`() = runBlocking<Unit> {
    val engine = MockEngine { respond("nope", HttpStatusCode.Unauthorized) }
    val ex = assertFailsWith<AuthHttpException> { client(engine).removeParticipant("ax", "fam1", "h1", "u2") }
    assertEquals(401, ex.status)
  }

  @Test fun `setVisibility issues PUT visibility with a visibility body`() = runBlocking {
    var method: String? = null; var path: String? = null; var body: String? = null
    val engine = MockEngine { req ->
      method = req.method.value; path = req.url.encodedPath
      body = (req.body as io.ktor.http.content.TextContent).text
      respond("{}", HttpStatusCode.OK, jsonCt)
    }
    client(engine).setVisibility("ax", "fam1", "h1", "restricted")
    assertEquals("PUT", method)
    assertEquals("/families/fam1/hubs/h1/visibility", path)
    assertEquals("""{"visibility":"restricted"}""", body)
  }

  @Test fun `setVisibility throws AuthHttpException on a non-200`() = runBlocking<Unit> {
    val engine = MockEngine { respond("nope", HttpStatusCode.Unauthorized) }
    val ex = assertFailsWith<AuthHttpException> { client(engine).setVisibility("ax", "fam1", "h1", "family") }
    assertEquals(401, ex.status)
  }
}
