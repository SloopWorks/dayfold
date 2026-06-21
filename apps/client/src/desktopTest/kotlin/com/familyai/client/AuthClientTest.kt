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
    val ex = assertFailsWith<AuthHttpException> { client(engine).devToken("dev", "alice", "WRONG") }
    assertEquals(403, ex.status)
  }

  @Test fun `createFamily throws on a 4xx`() = runBlocking<Unit> {
    val engine = MockEngine { respond("bad", HttpStatusCode.BadRequest) }
    val ex = assertFailsWith<AuthHttpException> { client(engine).createFamily("ACCESS", "") }
    assertEquals(400, ex.status)
  }

  // ── redeemInvite: status → RedeemResult ──
  @Test fun `redeemInvite 200 fresh success is Pending with family info`() = runBlocking {
    var path = ""; var sent = ""
    val engine = MockEngine { req ->
      path = req.url.encodedPath; sent = body(req)
      respond("""{"family_id":"fam1","family_name":"The Jacksons","role":"adult","status":"pending"}""", HttpStatusCode.OK, jsonCt)
    }
    val r = client(engine).redeemInvite("ACCESS", "tok123")
    assertEquals("/invites:redeem", path)
    assertTrue(sent.contains("\"token\":\"tok123\""), "body was: $sent")
    assertEquals(RedeemResult.Pending("fam1", "The Jacksons", "adult"), r)
  }

  @Test fun `redeemInvite 200 already-requested is Pending with nulls`() = runBlocking {
    val engine = MockEngine { respond("""{"status":"pending"}""", HttpStatusCode.OK, jsonCt) }
    assertEquals(RedeemResult.Pending(null, null, null), client(engine).redeemInvite("A", "t"))
  }

  @Test fun `redeemInvite 404 is Expired`() = runBlocking {
    val engine = MockEngine { respond("", HttpStatusCode.NotFound) }
    assertEquals(RedeemResult.Expired, client(engine).redeemInvite("A", "t"))
  }

  @Test fun `redeemInvite 429 is Locked`() = runBlocking {
    val engine = MockEngine { respond("", HttpStatusCode.TooManyRequests) }
    assertEquals(RedeemResult.Locked, client(engine).redeemInvite("A", "t"))
  }

  @Test fun `redeemInvite 409 already-member vs removed`() = runBlocking {
    val already = MockEngine { respond("""{"type":"already-member"}""", HttpStatusCode.Conflict, jsonCt) }
    assertEquals(RedeemResult.AlreadyMember, client(already).redeemInvite("A", "t"))
    val removed = MockEngine { respond("""{"type":"removed"}""", HttpStatusCode.Conflict, jsonCt) }
    assertEquals(RedeemResult.Removed, client(removed).redeemInvite("A", "t"))
  }

  @Test fun `redeemInvite throws on 401 (transient → engine retries or joinerror)`() = runBlocking<Unit> {
    val engine = MockEngine { respond("", HttpStatusCode.Unauthorized) }
    val ex = assertFailsWith<AuthHttpException> { client(engine).redeemInvite("A", "t") }
    assertEquals(401, ex.status)
  }

  // ── owner-side approvals ──
  @Test fun `familyApprovals parses the pending queue with the bearer`() = runBlocking {
    var path = ""; var auth: String? = null
    val engine = MockEngine { req ->
      path = req.url.encodedPath; auth = req.headers[HttpHeaders.Authorization]
      respond(
        """{"invites":[],"pending":[
          {"uid":"u9","display_name":"Sam Rivera","role":"adult","provider":"google","requested_at":"2026-06-21T10:00:00Z"}]}""",
        HttpStatusCode.OK, jsonCt,
      )
    }
    val pending = client(engine).familyApprovals("ACCESS", "fam1")
    assertEquals("/families/fam1/invites", path)
    assertEquals("Bearer ACCESS", auth)
    assertEquals(1, pending.size)
    assertEquals("u9", pending[0].uid)
    assertEquals("Sam Rivera", pending[0].displayName)
  }

  @Test fun `approveMember posts the colon-action path and accepts 204`() = runBlocking {
    var path = ""
    val engine = MockEngine { req -> path = req.url.encodedPath; respond("", HttpStatusCode.NoContent) }
    client(engine).approveMember("ACCESS", "fam1", "u9")
    assertEquals("/families/fam1/members/u9:approve", path)
  }

  @Test fun `declineMember accepts 204`() = runBlocking<Unit> {
    val engine = MockEngine { respond("", HttpStatusCode.NoContent) }
    client(engine).declineMember("ACCESS", "fam1", "u9")   // no throw
  }

  @Test fun `approveMember throws on 404 (no longer pending)`() = runBlocking<Unit> {
    val engine = MockEngine { respond("", HttpStatusCode.NotFound) }
    val ex = assertFailsWith<AuthHttpException> { client(engine).approveMember("A", "f", "u") }
    assertEquals(404, ex.status)
  }

  // ── roster ──
  @Test fun `familyMembers parses the active roster`() = runBlocking {
    var path = ""
    val engine = MockEngine { req ->
      path = req.url.encodedPath
      respond(
        """{"members":[
          {"uid":"u1","display_name":"Pat","role":"owner","status":"active","joined_at":"2026-06-01T00:00:00Z"},
          {"uid":"u2","display_name":"Maya","role":"adult","status":"active"}]}""",
        HttpStatusCode.OK, jsonCt,
      )
    }
    val m = client(engine).familyMembers("ACCESS", "fam1")
    assertEquals("/families/fam1/members", path)
    assertEquals(2, m.size)
    assertEquals("owner", m[0].role)
    assertEquals("Maya", m[1].displayName)
  }

  @Test fun `removeMember deletes and accepts 204`() = runBlocking {
    var method = ""; var path = ""
    val engine = MockEngine { req -> method = req.method.value; path = req.url.encodedPath; respond("", HttpStatusCode.NoContent) }
    client(engine).removeMember("ACCESS", "fam1", "u2")
    assertEquals("DELETE", method)
    assertEquals("/families/fam1/members/u2", path)
  }

  @Test fun `removeMember throws on 409 (last owner)`() = runBlocking<Unit> {
    val engine = MockEngine { respond("", HttpStatusCode.Conflict) }
    val ex = assertFailsWith<AuthHttpException> { client(engine).removeMember("A", "f", "u") }
    assertEquals(409, ex.status)
  }

  // ── connected devices ──
  @Test fun `credentials parses the device list with the current flag`() = runBlocking {
    var path = ""
    val engine = MockEngine { req ->
      path = req.url.encodedPath
      respond(
        """{"credentials":[
          {"id":"c1","kind":"app","label":"iPhone","current":true,"last_used_at":"2026-06-21T10:00:00Z"},
          {"id":"c2","kind":"cli","label":"claude-code","scopes":["content:write"],"current":false}]}""",
        HttpStatusCode.OK, jsonCt,
      )
    }
    val creds = client(engine).credentials("ACCESS")
    assertEquals("/auth/me/credentials", path)
    assertEquals(2, creds.size)
    assertTrue(creds[0].current); assertEquals("cli", creds[1].kind)
  }

  @Test fun `revokeCredential deletes the path and accepts 204`() = runBlocking {
    var method = ""; var path = ""
    val engine = MockEngine { req -> method = req.method.value; path = req.url.encodedPath; respond("", HttpStatusCode.NoContent) }
    client(engine).revokeCredential("ACCESS", "c2")
    assertEquals("DELETE", method)
    assertEquals("/auth/me/credentials/c2", path)
  }

  @Test fun `revokeCredential throws on 404 (not yours)`() = runBlocking<Unit> {
    val engine = MockEngine { respond("", HttpStatusCode.NotFound) }
    val ex = assertFailsWith<AuthHttpException> { client(engine).revokeCredential("A", "x") }
    assertEquals(404, ex.status)
  }
}
