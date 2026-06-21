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
import kotlin.test.assertNull
import kotlin.test.assertTrue

// AUTH-S5 T4 — AuthEngine drives the real AuthClient over a MockEngine + an
// in-memory TokenStore, asserting the resulting store state (route + session).
class AuthEngineTest {
  private val jsonCt = headersOf(HttpHeaders.ContentType, "application/json")

  private class MemTokenStore(var session: Session? = null) : TokenStore {
    override fun load() = session
    override fun save(session: Session) { this.session = session }
    override fun clear() { session = null }
  }

  private fun engine(
    ts: MemTokenStore,
    devSecret: String? = "DEVSECRET",
    handler: MockEngine,
  ): Pair<AuthEngine, org.reduxkotlin.Store<AppState>> {
    val store = createAppStore(debug = false)
    val client = AuthClient("https://api.test", HttpClient(handler))
    return AuthEngine(store, client, ts, devSecret = devSecret) to store
  }

  private fun whoami(families: String) =
    """{"family_id":null,"families":[$families]}"""
  private val activeOwner =
    """{"family_id":"fam1","name":"The Jacksons","role":"owner","status":"active"}"""

  @Test fun `restore with no saved session lands on SignIn`() = runBlocking {
    val (eng, store) = engine(MemTokenStore(null), handler = MockEngine { respond("", HttpStatusCode.OK) })
    eng.restore()
    assertEquals(Route.SignIn, store.state.route)
    assertNull(store.state.session)
  }

  @Test fun `restore with a saved active session lands on Feed`() = runBlocking {
    val ts = MemTokenStore(Session("ax", "rx"))
    val (eng, store) = engine(ts, handler = MockEngine { respond(whoami(activeOwner), HttpStatusCode.OK, jsonCt) })
    eng.restore()
    assertEquals(Route.Feed, store.state.route)
    assertEquals("fam1", store.state.activeFamilyId)
    assertEquals("ax", store.state.session?.access)
  }

  @Test fun `sign-in success persists the session and routes by memberships`() = runBlocking {
    val ts = MemTokenStore(null)
    val (eng, store) = engine(ts, handler = MockEngine { req ->
      when (req.url.encodedPath) {
        "/auth/dev-token" -> respond("""{"access":"a1","refresh":"r1"}""", HttpStatusCode.OK, jsonCt)
        "/auth/whoami" -> respond(whoami(""), HttpStatusCode.OK, jsonCt)   // no families yet
        else -> respond("", HttpStatusCode.NotFound)
      }
    })
    eng.signIn("google")
    assertEquals(Route.CreateFamily, store.state.route)            // signed in, no family → onboarding
    assertEquals(Session("a1", "r1"), store.state.session)
    assertEquals(Session("a1", "r1"), ts.session)                  // persisted
  }

  @Test fun `sign-in with no dev provider fails closed`() = runBlocking {
    val store = createAppStore(AppState(route = Route.SignIn), debug = false)
    val client = AuthClient("https://api.test", HttpClient(MockEngine { respond("", HttpStatusCode.OK) }))
    AuthEngine(store, client, MemTokenStore(null), devSecret = null).signIn("apple")
    assertEquals(Route.SignIn, store.state.route)                 // failure stays put, no nav
    assertTrue(store.state.authError?.contains("S2") == true, "was: ${store.state.authError}")
    assertNull(store.state.session)
  }

  @Test fun `create-family routes into the new owner family`() = runBlocking {
    val ts = MemTokenStore(Session("a1", "r1"))
    val store = createAppStore(AppState(session = Session("a1", "r1"), route = Route.CreateFamily), debug = false)
    val client = AuthClient("https://api.test", HttpClient(MockEngine { req ->
      if (req.url.encodedPath == "/families") respond("""{"familyId":"famZ"}""", HttpStatusCode.Created, jsonCt)
      else respond("", HttpStatusCode.NotFound)
    }))
    AuthEngine(store, client, ts, devSecret = "DEVSECRET").createFamily("The Jacksons")
    assertEquals(Route.Feed, store.state.route)
    assertEquals("famZ", store.state.activeFamilyId)
    assertEquals("owner", store.state.families.single().role)
  }

  @Test fun `sign-out clears tokens and returns to SignIn`() = runBlocking {
    val ts = MemTokenStore(Session("a1", "r1"))
    val store = createAppStore(
      AppState(session = Session("a1", "r1"), families = listOf(FamilyMembership("fam1", status = "active")),
        activeFamilyId = "fam1", route = Route.Feed, cards = listOf(Card("c", title = "T"))),
      debug = false,
    )
    val client = AuthClient("https://api.test", HttpClient(MockEngine { respond("", HttpStatusCode.NoContent) }))
    AuthEngine(store, client, ts).signOut()
    assertEquals(Route.SignIn, store.state.route)
    assertNull(store.state.session)
    assertNull(ts.session)                       // cleared locally
    assertTrue(store.state.cards.isEmpty())
  }

  @Test fun `expired access on restore triggers one refresh-and-retry`() = runBlocking {
    val ts = MemTokenStore(Session("stale", "r1"))
    var whoamiCalls = 0
    val (eng, store) = engine(ts, handler = MockEngine { req ->
      when (req.url.encodedPath) {
        "/auth/whoami" -> {
          whoamiCalls++
          if (whoamiCalls == 1) respond("expired", HttpStatusCode.Unauthorized)   // 401 first
          else respond(whoami(activeOwner), HttpStatusCode.OK, jsonCt)             // ok after refresh
        }
        "/auth/refresh" -> respond("""{"access":"fresh","refresh":"r2"}""", HttpStatusCode.OK, jsonCt)
        else -> respond("", HttpStatusCode.NotFound)
      }
    })
    eng.restore()
    assertEquals(2, whoamiCalls)                        // retried after refresh
    assertEquals(Route.Feed, store.state.route)
    assertEquals(Session("fresh", "r2"), store.state.session)   // rotated into state
    assertEquals(Session("fresh", "r2"), ts.session)            // and persisted
  }

  // ── invitee-join (slice-2 foundation) ──
  private suspend fun redeemOutcome(status: HttpStatusCode, body: String): Pair<String?, String?> {
    val store = createAppStore(AppState(session = Session("a", "r")), debug = false)
    val client = AuthClient("https://api.test", HttpClient(MockEngine { req ->
      if (req.url.encodedPath == "/invites:redeem") respond(body, status, jsonCt) else respond("", HttpStatusCode.NotFound)
    }))
    AuthEngine(store, client, MemTokenStore(Session("a", "r"))).redeemInvite("tok")
    return store.state.joinOutcome to store.state.joinFamilyName
  }

  @Test fun `redeem invite success routes to waiting`() = runBlocking {
    val (outcome, fam) = redeemOutcome(HttpStatusCode.OK, """{"family_id":"fam1","family_name":"The Jacksons","role":"adult","status":"pending"}""")
    assertEquals("waiting", outcome)
    assertEquals("The Jacksons", fam)
  }

  @Test fun `redeem invite maps each rejection`() = runBlocking {
    assertEquals("expired", redeemOutcome(HttpStatusCode.NotFound, "").first)
    assertEquals("locked", redeemOutcome(HttpStatusCode.TooManyRequests, "").first)
    assertEquals("already", redeemOutcome(HttpStatusCode.Conflict, """{"type":"already-member"}""").first)
    assertEquals("removed", redeemOutcome(HttpStatusCode.Conflict, """{"type":"removed"}""").first)
    assertEquals("error", redeemOutcome(HttpStatusCode.InternalServerError, "").first)  // transient → join-retry
  }

  // ── owner-side approvals ──
  @Test fun `loadApprovals fills the pending queue`() = runBlocking {
    val store = createAppStore(AppState(session = Session("a", "r")), debug = false)
    val client = AuthClient("https://api.test", HttpClient(MockEngine { req ->
      if (req.url.encodedPath == "/families/fam1/invites")
        respond("""{"invites":[],"pending":[{"uid":"u9","display_name":"Sam Rivera","role":"adult"}]}""", HttpStatusCode.OK, jsonCt)
      else respond("", HttpStatusCode.NotFound)
    }))
    AuthEngine(store, client, MemTokenStore(Session("a", "r"))).loadApprovals("fam1")
    assertEquals(1, store.state.pendingApprovals.size)
    assertEquals("u9", store.state.pendingApprovals[0].uid)
  }

  @Test fun `approveMember drops the member from the queue`() = runBlocking {
    val store = createAppStore(
      AppState(session = Session("a", "r"), pendingApprovals = listOf(PendingMember("u9", "Sam"), PendingMember("u8", "Mo"))),
      debug = false,
    )
    val client = AuthClient("https://api.test", HttpClient(MockEngine { respond("", HttpStatusCode.NoContent) }))
    AuthEngine(store, client, MemTokenStore(Session("a", "r"))).approveMember("fam1", "u9")
    assertEquals(listOf("u8"), store.state.pendingApprovals.map { it.uid })
  }

  @Test fun `loadMembers fills the roster`() = runBlocking {
    val store = createAppStore(AppState(session = Session("a", "r")), debug = false)
    val client = AuthClient("https://api.test", HttpClient(MockEngine { req ->
      if (req.url.encodedPath == "/families/fam1/members")
        respond("""{"members":[{"uid":"u1","display_name":"Pat","role":"owner"}]}""", HttpStatusCode.OK, jsonCt)
      else respond("", HttpStatusCode.NotFound)
    }))
    AuthEngine(store, client, MemTokenStore(Session("a", "r"))).loadMembers("fam1")
    assertEquals(1, store.state.members.size)
    assertEquals("u1", store.state.members[0].uid)
  }

  @Test fun `removeMember drops from the roster on success`() = runBlocking {
    val store = createAppStore(
      AppState(session = Session("a", "r"), members = listOf(FamilyMember("u1", "Pat", role = "owner"), FamilyMember("u2", "Maya"))),
      debug = false,
    )
    val client = AuthClient("https://api.test", HttpClient(MockEngine { respond("", HttpStatusCode.NoContent) }))
    AuthEngine(store, client, MemTokenStore(Session("a", "r"))).removeMember("fam1", "u2")
    assertEquals(listOf("u1"), store.state.members.map { it.uid })
  }

  // ── connected devices ──
  @Test fun `loadDevices fills the device list`() = runBlocking {
    val store = createAppStore(AppState(session = Session("a", "r")), debug = false)
    val client = AuthClient("https://api.test", HttpClient(MockEngine { req ->
      if (req.url.encodedPath == "/auth/me/credentials")
        respond("""{"credentials":[{"id":"c1","kind":"app","current":true}]}""", HttpStatusCode.OK, jsonCt)
      else respond("", HttpStatusCode.NotFound)
    }))
    AuthEngine(store, client, MemTokenStore(Session("a", "r"))).loadDevices()
    assertEquals(1, store.state.devices.size)
    assertEquals("c1", store.state.devices[0].id)
  }

  @Test fun `revokeDevice drops from the list on success`() = runBlocking {
    val store = createAppStore(
      AppState(session = Session("a", "r"), devices = listOf(DeviceCredential("c1", current = true), DeviceCredential("c2"))),
      debug = false,
    )
    val client = AuthClient("https://api.test", HttpClient(MockEngine { respond("", HttpStatusCode.NoContent) }))
    AuthEngine(store, client, MemTokenStore(Session("a", "r"))).revokeDevice("c2")
    assertEquals(listOf("c1"), store.state.devices.map { it.id })
  }
}
