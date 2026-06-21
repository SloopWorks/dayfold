package com.familyai.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// AUTH-S5 T2 — AuthClient request shapes + response parsing, against a fake
// transport (no server). Pairs with the pure reducer tests (T1).
class AuthClientTest {
  private val jsonCt = headersOf(HttpHeaders.ContentType, "application/json")
  private fun client(engine: MockEngine) = AuthClient("https://api.test", HttpClient(engine))
  private suspend fun body(req: HttpRequestData): String =
    (req.body as? io.ktor.http.content.TextContent)?.text ?: ""

  @Test fun `devToken sends provider + provider_uid with the dev bearer and parses tokens`() = runBlocking {
    var path = ""; var auth: String? = null; var sent = ""
    val engine = MockEngine { req ->
      path = req.url.encodedPath; auth = req.headers[HttpHeaders.Authorization]; sent = body(req)
      respond("""{"access":"ax","refresh":"rx"}""", HttpStatusCode.OK, jsonCt)
    }
    val s = client(engine).devToken("dev", "alice", "DEVSECRET")
    assertEquals("/auth/dev-token", path)
    assertEquals("Bearer DEVSECRET", auth)
    assertTrue(sent.contains("\"provider\":\"dev\""), "body was: $sent")
    assertTrue(sent.contains("\"provider_uid\":\"alice\""), "body was: $sent")
    assertEquals(Session("ax", "rx"), s)
  }

  @Test fun `whoami parses family_id + memberships`() = runBlocking {
    var auth: String? = null
    val engine = MockEngine { req ->
      auth = req.headers[HttpHeaders.Authorization]
      respond(
        """{"family_id":"fam1","families":[
          {"family_id":"fam1","name":"The Jacksons","role":"owner","status":"active"},
          {"family_id":"fam2","name":"Riveras","role":"adult","status":"pending"}]}""",
        HttpStatusCode.OK, jsonCt,
      )
    }
    val w = client(engine).whoami("ACCESS")
    assertEquals("Bearer ACCESS", auth)
    assertEquals("fam1", w.familyId)
    assertEquals(2, w.families.size)
    assertEquals("owner", w.families[0].role)
    assertEquals("pending", w.families[1].status)
    assertEquals("fam2", w.families[1].familyId)
  }

  @Test fun `whoami tolerates a null family_id and empty families`() = runBlocking {
    val engine = MockEngine { respond("""{"family_id":null,"families":[]}""", HttpStatusCode.OK, jsonCt) }
    val w = client(engine).whoami("ACCESS")
    assertEquals(null, w.familyId)
    assertTrue(w.families.isEmpty())
  }

  @Test fun `createFamily posts the name and returns the new id (accepts 201)`() = runBlocking {
    var sent = ""
    val engine = MockEngine { req ->
      sent = body(req)
      respond("""{"familyId":"fam9"}""", HttpStatusCode.Created, jsonCt)
    }
    val id = client(engine).createFamily("ACCESS", "The Jacksons")
    assertTrue(sent.contains("\"name\":\"The Jacksons\""), "body was: $sent")
    assertEquals("fam9", id)
  }

  @Test fun `refresh rotates the session`() = runBlocking {
    var sent = ""
    val engine = MockEngine { req ->
      sent = body(req)
      respond("""{"access":"a2","refresh":"r2"}""", HttpStatusCode.OK, jsonCt)
    }
    val s = client(engine).refresh("r1")
    assertTrue(sent.contains("\"refresh\":\"r1\""), "body was: $sent")
    assertEquals(Session("a2", "r2"), s)
  }

  @Test fun `signout accepts 204`() = runBlocking<Unit> {
    val engine = MockEngine { respond("", HttpStatusCode.NoContent) }
    client(engine).signout("ACCESS")   // no throw
  }

  @Test fun `devToken throws on a rejected dev secret`() = runBlocking<Unit> {
    val engine = MockEngine { respond("forbidden", HttpStatusCode.Forbidden) }
    assertFailsWith<IllegalArgumentException> { client(engine).devToken("dev", "alice", "WRONG") }
  }

  @Test fun `createFamily throws on a 4xx`() = runBlocking<Unit> {
    val engine = MockEngine { respond("bad", HttpStatusCode.BadRequest) }
    assertFailsWith<IllegalArgumentException> { client(engine).createFamily("ACCESS", "") }
  }
}
