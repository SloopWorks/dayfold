package com.sloopworks.dayfold.client

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
import kotlin.test.assertNull
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

  @Test fun `firebaseToken posts the idToken with no bearer and parses tokens`() = runBlocking {
    var path = ""; var auth: String? = null; var sent = ""
    val engine = MockEngine { req ->
      path = req.url.encodedPath; auth = req.headers[HttpHeaders.Authorization]; sent = body(req)
      respond("""{"access":"fa","refresh":"fr"}""", HttpStatusCode.OK, jsonCt)
    }
    val s = client(engine).firebaseToken("ID_TOKEN_XYZ")
    assertEquals("/auth/firebase", path)
    assertNull(auth)                                    // proof is the ID token in the body, not a bearer
    assertTrue(sent.contains("\"idToken\":\"ID_TOKEN_XYZ\""), "body was: $sent")
    assertEquals(Session("fa", "fr"), s)
  }

  @Test fun `firebaseToken throws on a rejected (401) token`() = runBlocking {
    val engine = MockEngine { respond("""{"type":"bad-identity"}""", HttpStatusCode.Unauthorized, jsonCt) }
    val ex = assertFailsWith<AuthHttpException> { client(engine).firebaseToken("bad") }
    assertEquals(401, ex.status)
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

  // A dead/reused refresh token → server 401. refresh() must THROW (not return a
  // bogus session) — every refresh-and-retry path (SyncEngine #104, AuthEngine,
  // HubEngine) keys off this to fall back to SyncFailed / sign-out.
  @Test fun `refresh throws on a rejected refresh token`() = runBlocking<Unit> {
    val engine = MockEngine { respond("invalid_grant", HttpStatusCode.Unauthorized) }
    val ex = assertFailsWith<AuthHttpException> { client(engine).refresh("dead") }
    assertEquals(401, ex.status)
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
    val q = client(engine).familyApprovals("ACCESS", "fam1")
    assertEquals("/families/fam1/invites", path)
    assertEquals("Bearer ACCESS", auth)
    assertEquals(1, q.pending.size)
    assertEquals("u9", q.pending[0].uid)
    assertEquals("Sam Rivera", q.pending[0].displayName)
    assertTrue(q.invites.isEmpty())
  }

  @Test fun `familyApprovals parses avatar_color and avatar_ref on pending members`() = runBlocking {
    val engine = MockEngine {
      respond(
        """{"invites":[],"pending":[
          {"uid":"u9","display_name":"Sam Rivera","avatar_color":"teal","avatar_ref":"avatar:fox-01",
           "role":"adult","provider":"google","requested_at":"2026-06-21T10:00:00Z"}]}""",
        HttpStatusCode.OK, jsonCt,
      )
    }
    val q = client(engine).familyApprovals("ACCESS", "fam1")
    assertEquals("teal", q.pending[0].avatarColor)
    assertEquals("avatar:fox-01", q.pending[0].avatarRef)
  }

  @Test fun `familyApprovals also parses outstanding active invites`() = runBlocking {
    val engine = MockEngine {
      respond(
        """{"invites":[{"id":"inv1","role":"adult","mode":"link","max_uses":5,"used_count":1,
          "expires_at":"2026-07-09T00:00:00Z","created_at":"2026-07-07T00:00:00Z"}],"pending":[]}""",
        HttpStatusCode.OK, jsonCt,
      )
    }
    val q = client(engine).familyApprovals("ACCESS", "fam1")
    assertEquals(1, q.invites.size)
    assertEquals("link", q.invites[0].mode)
    assertEquals(5, q.invites[0].maxUses)
    assertEquals(1, q.invites[0].usedCount)
  }

  // ── owner-side mint / revoke ──
  @Test fun `mintInvite posts mode+role+max_uses and parses the one-time token`() = runBlocking {
    var path = ""; var sent = ""; var auth: String? = null
    val engine = MockEngine { req ->
      path = req.url.encodedPath; sent = body(req); auth = req.headers[HttpHeaders.Authorization]
      respond(
        """{"invite_id":"inv1","token":"TOK_ONETIME","url":"https://api.test/invite/TOK_ONETIME",
          "role":"adult","mode":"qr","expires_at":"2026-07-07T10:15:00Z"}""",
        HttpStatusCode.Created, jsonCt,
      )
    }
    val r = client(engine).mintInvite("ACCESS", "fam1", "qr", 1)
    assertEquals("/families/fam1/invites", path)
    assertEquals("Bearer ACCESS", auth)
    assertTrue(sent.contains("\"mode\":\"qr\""), "body was: $sent")
    assertTrue(sent.contains("\"max_uses\":1"), "body was: $sent")
    assertTrue(r is MintResult.Ok)
    assertEquals("TOK_ONETIME", (r as MintResult.Ok).invite.token)
    assertEquals("qr", r.invite.mode)
  }

  @Test fun `mintInvite 429 is RateLimited and 403 is Forbidden`() = runBlocking {
    assertEquals(MintResult.RateLimited, client(MockEngine { respond("", HttpStatusCode.TooManyRequests) }).mintInvite("A", "f", "link", 5))
    assertEquals(MintResult.Forbidden, client(MockEngine { respond("", HttpStatusCode.Forbidden) }).mintInvite("A", "f", "qr", 1))
  }

  @Test fun `mintInvite throws on 400 (bad mode)`() = runBlocking<Unit> {
    val ex = assertFailsWith<AuthHttpException> { client(MockEngine { respond("""{"type":"bad-mode"}""", HttpStatusCode.BadRequest, jsonCt) }).mintInvite("A", "f", "nope", 1) }
    assertEquals(400, ex.status)
  }

  @Test fun `revokeInvite deletes the invite path and accepts 204`() = runBlocking {
    var method = ""; var path = ""
    val engine = MockEngine { req -> method = req.method.value; path = req.url.encodedPath; respond("", HttpStatusCode.NoContent) }
    client(engine).revokeInvite("ACCESS", "fam1", "inv1")
    assertEquals("DELETE", method)
    assertEquals("/families/fam1/invites/inv1", path)
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

  @Test fun `familyMembers parses avatar_color and avatar_ref`() = runBlocking {
    val engine = MockEngine {
      respond(
        """{"members":[
          {"uid":"u1","display_name":"Leo","avatar_color":"teal","avatar_ref":"avatar:fox-01","role":"adult","status":"active"},
          {"uid":"u2","display_name":"Maya","role":"adult","status":"active"}]}""",
        HttpStatusCode.OK, jsonCt,
      )
    }
    val m = client(engine).familyMembers("ACCESS", "fam1")
    assertEquals("teal", m[0].avatarColor)
    assertEquals("avatar:fox-01", m[0].avatarRef)
    assertNull(m[1].avatarColor)   // older/undecorated payload still parses (nullable default)
    assertNull(m[1].avatarRef)
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

  // ── own profile (task 4) ──
  @Test fun `getMe parses the profile with the bearer`() = runBlocking {
    var path = ""; var auth: String? = null
    val engine = MockEngine { req ->
      path = req.url.encodedPath; auth = req.headers[HttpHeaders.Authorization]
      respond(
        """{"user_id":"u1","display_name":"Leo","avatar_color":"teal","avatar_ref":"avatar:fox-01"}""",
        HttpStatusCode.OK, jsonCt,
      )
    }
    val me = client(engine).getMe("ACCESS")
    assertEquals("/auth/me", path)
    assertEquals("Bearer ACCESS", auth)
    assertEquals(MeProfile("u1", "Leo", "teal", "avatar:fox-01"), me)
  }

  @Test fun `getMe throws on 401`() = runBlocking<Unit> {
    val engine = MockEngine { respond("", HttpStatusCode.Unauthorized) }
    val ex = assertFailsWith<AuthHttpException> { client(engine).getMe("A") }
    assertEquals(401, ex.status)
  }

  @Test fun `updateAvatar PATCHes both keys (null clears) and parses the response`() = runBlocking {
    var method = ""; var path = ""; var sent = ""
    val engine = MockEngine { req ->
      method = req.method.value; path = req.url.encodedPath; sent = body(req)
      respond("""{"display_name":"Leo","avatar_color":"coral","avatar_ref":"avatar:sun-01"}""", HttpStatusCode.OK, jsonCt)
    }
    val me = client(engine).updateAvatar("ACCESS", "coral", "avatar:sun-01")
    assertEquals("PATCH", method)
    assertEquals("/auth/me", path)
    assertTrue(sent.contains("\"avatar_color\":\"coral\""), "body was: $sent")
    assertTrue(sent.contains("\"avatar_ref\":\"avatar:sun-01\""), "body was: $sent")
    assertEquals("coral", me.avatarColor)
    assertEquals("avatar:sun-01", me.avatarRef)
    assertEquals("", me.userId)   // PATCH's response omits user_id
  }

  @Test fun `updateAvatar sends explicit null to clear a field`() = runBlocking {
    var sent = ""
    val engine = MockEngine { req -> sent = body(req); respond("""{"display_name":null,"avatar_color":null,"avatar_ref":null}""", HttpStatusCode.OK, jsonCt) }
    client(engine).updateAvatar("ACCESS", null, null)
    assertTrue(sent.contains("\"avatar_color\":null"), "body was: $sent")
    assertTrue(sent.contains("\"avatar_ref\":null"), "body was: $sent")
  }

  @Test fun `updateDisplayName PATCHes display_name and parses the response`() = runBlocking {
    var method = ""; var path = ""; var sent = ""
    val engine = MockEngine { req ->
      method = req.method.value; path = req.url.encodedPath; sent = body(req)
      respond("""{"display_name":"Zoe"}""", HttpStatusCode.OK, jsonCt)
    }
    val me = client(engine).updateDisplayName("ACCESS", "Zoe")
    assertEquals("PATCH", method)
    assertEquals("/auth/me", path)
    assertTrue(sent.contains("\"display_name\":\"Zoe\""), "body was: $sent")
    assertEquals("Zoe", me.displayName)
  }

  @Test fun `updateAvatar throws on 400 (bad avatar)`() = runBlocking<Unit> {
    val engine = MockEngine { respond("""{"type":"bad-avatar"}""", HttpStatusCode.BadRequest, jsonCt) }
    val ex = assertFailsWith<AuthHttpException> { client(engine).updateAvatar("A", "x".repeat(99), null) }
    assertEquals(400, ex.status)
  }

  // ── CLI/device approval (S6-D) ──
  @Test fun `devicePending 200 parses the grant incl origin_kind with the bearer + query`() = runBlocking {
    var path = ""; var query = ""; var auth: String? = null
    val engine = MockEngine { req ->
      path = req.url.encodedPath; query = req.url.encodedQuery; auth = req.headers[HttpHeaders.Authorization]
      respond(
        """{"user_code":"WDJF-7K2P","client":"dayfold-cli","origin_ip":"1.2.3.4",
          "origin_ua":"curl/8","origin_kind":"datacenter","created_at":"2026-06-23T10:00:00Z",
          "expires_at":"2026-06-23T10:10:00Z"}""",
        HttpStatusCode.OK, jsonCt,
      )
    }
    val r = client(engine).devicePending("ACCESS", "WDJF-7K2P")
    assertEquals("/device/pending", path)
    assertEquals("user_code=WDJF-7K2P", query)
    assertEquals("Bearer ACCESS", auth)
    assertTrue(r is DeviceLookupResult.Found)
    assertEquals("datacenter", r.device.originKind)
    assertEquals("dayfold-cli", r.device.client)
  }

  @Test fun `devicePending url-encodes the user_code`() = runBlocking {
    var query = ""
    val engine = MockEngine { req -> query = req.url.encodedQuery; respond("", HttpStatusCode.NotFound) }
    client(engine).devicePending("A", "a b")
    // whitespace must be percent-encoded (not a raw space) so it can't break the query.
    assertTrue(query.contains("a%20b") && !query.contains("a b"), "query was: $query")
  }

  @Test fun `devicePending 404 is NotFound and 429 is Locked`() = runBlocking {
    val miss = MockEngine { respond("""{"type":"not-found"}""", HttpStatusCode.NotFound, jsonCt) }
    assertEquals(DeviceLookupResult.NotFound, client(miss).devicePending("A", "X"))
    val locked = MockEngine { respond("", HttpStatusCode.TooManyRequests) }
    assertEquals(DeviceLookupResult.Locked, client(locked).devicePending("A", "X"))
  }

  @Test fun `devicePending throws on 401 (→ engine refresh-and-retry)`() = runBlocking<Unit> {
    val engine = MockEngine { respond("", HttpStatusCode.Unauthorized) }
    val ex = assertFailsWith<AuthHttpException> { client(engine).devicePending("A", "X") }
    assertEquals(401, ex.status)
  }

  @Test fun `deviceApprove posts user_code to the family path and maps statuses`() = runBlocking {
    var path = ""; var sent = ""
    val ok = MockEngine { req -> path = req.url.encodedPath; sent = body(req); respond("", HttpStatusCode.NoContent) }
    assertEquals(DeviceActionResult.Ok, client(ok).deviceApprove("ACCESS", "fam1", "WDJF-7K2P"))
    assertEquals("/families/fam1/device/approve", path)
    assertTrue(sent.contains("\"user_code\":\"WDJF-7K2P\""), "body was: $sent")

    assertEquals(DeviceActionResult.Expired, client(MockEngine { respond("", HttpStatusCode.NotFound) }).deviceApprove("A", "f", "X"))
    assertEquals(DeviceActionResult.Locked, client(MockEngine { respond("", HttpStatusCode.TooManyRequests) }).deviceApprove("A", "f", "X"))
    assertEquals(DeviceActionResult.Forbidden, client(MockEngine { respond("", HttpStatusCode.Forbidden) }).deviceApprove("A", "f", "X"))
  }

  @Test fun `deviceApprove throws on 401`() = runBlocking<Unit> {
    val ex = assertFailsWith<AuthHttpException> { client(MockEngine { respond("", HttpStatusCode.Unauthorized) }).deviceApprove("A", "f", "X") }
    assertEquals(401, ex.status)
  }

  @Test fun `deviceDeny treats 204 and 404 both as Ok (gone == denied)`() = runBlocking {
    var path = ""
    val ok = MockEngine { req -> path = req.url.encodedPath; respond("", HttpStatusCode.NoContent) }
    assertEquals(DeviceActionResult.Ok, client(ok).deviceDeny("ACCESS", "fam1", "X"))
    assertEquals("/families/fam1/device/deny", path)
    assertEquals(DeviceActionResult.Ok, client(MockEngine { respond("", HttpStatusCode.NotFound) }).deviceDeny("A", "f", "X"))
  }
}
