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
import kotlin.test.assertFalse
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
    cached: List<FamilyMembership> = emptyList(),                       // ADR 0052 — seeded membership cache
    savedMemberships: MutableList<List<FamilyMembership>> = mutableListOf(),  // captures saveMemberships calls
    onClearCache: () -> Unit = {},                                      // captures clearCache invocation
    handler: MockEngine,
  ): Pair<AuthEngine, org.reduxkotlin.Store<AppState>> {
    val store = createAppStore(debug = false)
    val client = AuthClient("https://api.test", HttpClient(handler))
    return AuthEngine(
      store, client, ts, devSecret = devSecret,
      clearCache = onClearCache,
      loadCachedMemberships = { cached },
      saveMemberships = { savedMemberships.add(it) },
    ) to store
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

  @Test fun `restore eager-loads the roster so members are available app-wide`() = runBlocking {
    val ts = MemTokenStore(Session("ax", "rx"))
    val (eng, store) = engine(ts, handler = MockEngine { req ->
      when (req.url.encodedPath) {
        "/auth/whoami" -> respond(whoami(activeOwner), HttpStatusCode.OK, jsonCt)   // active owner of fam1
        "/families/fam1/members" -> respond("""{"members":[{"uid":"u1","display_name":"Pat"},{"uid":"u2","display_name":"Maya"}]}""", HttpStatusCode.OK, jsonCt)
        else -> respond("", HttpStatusCode.NotFound)
      }
    })
    eng.restore()
    assertEquals(listOf("u1", "u2"), store.state.members.map { it.uid })   // loaded WITHOUT opening the Members screen
  }

  @Test fun `restore with a dead session (401 + refresh fails) clears tokens and routes to SignIn`() = runBlocking {
    val ts = MemTokenStore(Session("ax", "rx"))
    val (eng, store) = engine(ts, handler = MockEngine { req ->
      when (req.url.encodedPath) {
        "/auth/whoami" -> respond("", HttpStatusCode.Unauthorized)        // access expired
        "/auth/refresh" -> respond("", HttpStatusCode.Unauthorized)       // …and refresh can't recover → dead
        else -> respond("", HttpStatusCode.NotFound)
      }
    })
    eng.restore()
    assertEquals(Route.SignIn, store.state.route)                         // never wedges on Loading
    assertNull(store.state.session)
    assertNull(ts.session)                                                // dead token cleared
    assertTrue(store.state.authError?.contains("expired") == true, "was: ${store.state.authError}")
  }

  @Test fun `restore with a transient failure routes to AuthError and keeps the session`() = runBlocking {
    val ts = MemTokenStore(Session("ax", "rx"))
    val (eng, store) = engine(ts, handler = MockEngine { req ->
      when (req.url.encodedPath) {
        "/auth/whoami" -> respond("", HttpStatusCode.InternalServerError) // reachable but erroring → retryable
        else -> respond("", HttpStatusCode.NotFound)
      }
    })
    eng.restore()
    assertEquals(Route.AuthError, store.state.route)                      // not Loading, not SignIn
    assertEquals(Session("ax", "rx"), store.state.session)               // session kept for Retry
    assertEquals(Session("ax", "rx"), ts.session)                        // not cleared
  }

  // ── ADR 0052: DB-first cold-start route gate ─────────────────────────────────
  private val activeFam = FamilyMembership("fam1", "The Jacksons", "owner", "active")

  @Test fun `restore with cached memberships routes to Feed off the local cache, not the network`() = runBlocking {
    val ts = MemTokenStore(Session("ax", "rx"))
    // whoami 500s — so a Feed route can ONLY have come from the cache, and a 500 WITH a cache
    // present must NOT strand the user on AuthError (the pre-0052 behavior).
    val (eng, store) = engine(ts, cached = listOf(activeFam),
      handler = MockEngine { respond("", HttpStatusCode.InternalServerError) })
    eng.restore()
    assertEquals(Route.Feed, store.state.route)                 // routed synchronously off the DB cache
    assertEquals("fam1", store.state.activeFamilyId)
    eng.reconcileJob?.join()                                    // let the background whoami (500) finish
    assertEquals(Route.Feed, store.state.route)                 // 500 + cache → STAYS on Feed, no AuthError
    assertEquals(Session("ax", "rx"), store.state.session)      // session kept
  }

  @Test fun `reconcile overwrites the cached memberships with the whoami result and persists them`() = runBlocking {
    val ts = MemTokenStore(Session("ax", "rx"))
    val saved = mutableListOf<List<FamilyMembership>>()
    val (eng, store) = engine(ts,
      cached = listOf(FamilyMembership("stale", "Stale Fam", "adult", "active")),
      savedMemberships = saved,
      handler = MockEngine { req ->
        when (req.url.encodedPath) {
          "/auth/whoami" -> respond(whoami(activeOwner), HttpStatusCode.OK, jsonCt)   // fresh truth: fam1
          "/families/fam1/members" -> respond("""{"members":[]}""", HttpStatusCode.OK, jsonCt)
          else -> respond("", HttpStatusCode.NotFound)
        }
      })
    eng.restore()
    assertEquals("stale", store.state.activeFamilyId)           // optimistic (cache) first
    eng.reconcileJob?.join()
    assertEquals(listOf("fam1"), store.state.families.map { it.familyId })   // overwritten by whoami
    assertEquals(listOf("fam1"), saved.last().map { it.familyId })           // persisted for next cold start
    assertEquals(Route.Feed, store.state.route)
  }

  @Test fun `reconcile with a dead session clears the token and cache and routes to SignIn`() = runBlocking {
    val ts = MemTokenStore(Session("ax", "rx"))
    var cleared = false
    val (eng, store) = engine(ts, cached = listOf(activeFam), onClearCache = { cleared = true },
      handler = MockEngine { req ->
        when (req.url.encodedPath) {
          "/auth/whoami" -> respond("", HttpStatusCode.Unauthorized)   // access dead
          "/auth/refresh" -> respond("", HttpStatusCode.Unauthorized)  // …refresh can't recover
          else -> respond("", HttpStatusCode.NotFound)
        }
      })
    eng.restore()
    assertEquals(Route.Feed, store.state.route)                 // optimistic first
    eng.reconcileJob?.join()
    assertEquals(Route.SignIn, store.state.route)               // revocation wins on reconcile
    assertNull(store.state.session)
    assertNull(ts.session)                                      // dead token cleared
    assertTrue(cleared, "clearCache (incl. the membership cache) must fire on a dead session")
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

  @Test fun `sign-in uses the firebase id token when the platform yields one`() = runBlocking {
    val ts = MemTokenStore(null)
    var firebaseHit = false; var devHit = false
    val store = createAppStore(debug = false)
    val client = AuthClient("https://api.test", HttpClient(MockEngine { req ->
      when (req.url.encodedPath) {
        "/auth/firebase" -> { firebaseHit = true; respond("""{"access":"fa","refresh":"fr"}""", HttpStatusCode.OK, jsonCt) }
        "/auth/dev-token" -> { devHit = true; respond("""{"access":"d","refresh":"d"}""", HttpStatusCode.OK, jsonCt) }
        "/auth/whoami" -> respond(whoami(activeOwner), HttpStatusCode.OK, jsonCt)
        else -> respond("", HttpStatusCode.NotFound)
      }
    }))
    val eng = AuthEngine(store, client, ts, devSecret = "DEVSECRET", firebaseSignIn = { _ -> "GOOGLE_ID_TOKEN" })
    eng.signIn("google")
    assertTrue(firebaseHit, "should call /auth/firebase")
    assertTrue(!devHit, "should NOT fall back to dev-token when a firebase token is present")
    assertEquals(Route.Feed, store.state.route)
    assertEquals(Session("fa", "fr"), store.state.session)
    assertEquals(Session("fa", "fr"), ts.session)                // persisted
  }

  @Test fun `sign-in falls back to dev-token when the platform yields no token`() = runBlocking {
    val ts = MemTokenStore(null)
    var devHit = false
    val store = createAppStore(debug = false)
    val client = AuthClient("https://api.test", HttpClient(MockEngine { req ->
      when (req.url.encodedPath) {
        "/auth/dev-token" -> { devHit = true; respond("""{"access":"d1","refresh":"r1"}""", HttpStatusCode.OK, jsonCt) }
        "/auth/whoami" -> respond(whoami(""), HttpStatusCode.OK, jsonCt)
        else -> respond("", HttpStatusCode.NotFound)
      }
    }))
    // firebaseSignIn present but returns null (no Firebase config yet / user cancelled) → dev fallback
    val eng = AuthEngine(store, client, ts, devSecret = "DEVSECRET", firebaseSignIn = { _ -> null })
    eng.signIn("google")
    assertTrue(devHit, "should fall back to dev-token")
    assertEquals(Session("d1", "r1"), store.state.session)
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

  @Test fun `sign-out wipes the local content cache — data boundary`() = runBlocking {
    // Regression: logging out left the family's cards/hubs in the local DB (the
    // DB→store bridge is the sole writer of state.hubs), so a switch/next session
    // re-projected stale tenant data (e.g. a "butler" hub). Sign-out must drop the
    // cache, mirroring the ADR 0030 403/404 revocation path in SyncEngine.
    val ts = MemTokenStore(Session("a1", "r1"))
    val store = createAppStore(
      AppState(session = Session("a1", "r1"), route = Route.Feed), debug = false,
    )
    val client = AuthClient("https://api.test", HttpClient(MockEngine { respond("", HttpStatusCode.NoContent) }))
    var wiped = false
    AuthEngine(store, client, ts, clearCache = { wiped = true }).signOut()
    assertTrue(wiped)                            // local content cache cleared on logout
    assertEquals(Route.SignIn, store.state.route)
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

  // ── debug-only fake sign-in (no network) ──
  @Test fun `devSignIn lands on Feed purely local — no network, not persisted`() = runBlocking {
    val ts = MemTokenStore(null)
    val store = createAppStore(AppState(route = Route.SignIn), debug = false)
    // Any HTTP call fails the test: devSignIn must mint the session locally, so it
    // works against an unreachable/real backend without touching it.
    val client = AuthClient("https://api.test", HttpClient(MockEngine { error("devSignIn must not hit the network") }))
    AuthEngine(store, client, ts, devSecret = null).devSignIn()
    assertEquals(Route.Feed, store.state.route)
    assertEquals("dev-family", store.state.activeFamilyId)
    assertEquals("dev-user", store.state.session?.userId)
    assertFalse(store.state.authBusy)
    // Not persisted: a saved dev session would make the next cold-start restore()
    // call whoami on the (unreachable) backend → "Couldn't reach" again.
    assertNull(ts.session)
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

  // ── CLI/device approval (S6-D) ── runBlocking<Unit> per the agent-dev-loop JUnit gotcha.
  @Test fun `lookupDevice happy path loads the grant and routes to AuthorizeDevice`() = runBlocking<Unit> {
    val store = createAppStore(AppState(session = Session("a", "r"), route = Route.EnterCode), debug = false)
    val client = AuthClient("https://api.test", HttpClient(MockEngine { req ->
      if (req.url.encodedPath == "/device/pending")
        respond("""{"user_code":"WDJF-7K2P","client":"dayfold-cli","origin_kind":"datacenter"}""", HttpStatusCode.OK, jsonCt)
      else respond("", HttpStatusCode.NotFound)
    }))
    AuthEngine(store, client, MemTokenStore(Session("a", "r"))).lookupDevice("WDJF-7K2P")
    assertEquals(Route.AuthorizeDevice, store.state.route)
    assertEquals("WDJF-7K2P", store.state.pendingDevice?.userCode)
    assertEquals("datacenter", store.state.pendingDevice?.originKind)
  }

  @Test fun `lookupDevice 404 routes to AuthorizeDevice with the expired outcome`() = runBlocking<Unit> {
    val store = createAppStore(AppState(session = Session("a", "r"), route = Route.EnterCode), debug = false)
    val client = AuthClient("https://api.test", HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }))
    AuthEngine(store, client, MemTokenStore(Session("a", "r"))).lookupDevice("XXXX-YYYY")
    assertEquals(Route.AuthorizeDevice, store.state.route)
    assertEquals("expired", store.state.deviceOutcome)
    assertNull(store.state.pendingDevice)
  }

  @Test fun `lookupDevice 429 stays on EnterCode with an inline error`() = runBlocking<Unit> {
    val store = createAppStore(AppState(session = Session("a", "r"), route = Route.EnterCode), debug = false)
    val client = AuthClient("https://api.test", HttpClient(MockEngine { respond("", HttpStatusCode.TooManyRequests) }))
    AuthEngine(store, client, MemTokenStore(Session("a", "r"))).lookupDevice("X")
    assertEquals(Route.EnterCode, store.state.route)
    assertTrue(store.state.deviceError?.contains("15 minutes") == true, "was: ${store.state.deviceError}")
  }

  @Test fun `lookupDevice 401 refreshes once and retries`() = runBlocking<Unit> {
    val ts = MemTokenStore(Session("stale", "r1"))
    var lookups = 0
    val store = createAppStore(AppState(session = Session("stale", "r1"), route = Route.EnterCode), debug = false)
    val client = AuthClient("https://api.test", HttpClient(MockEngine { req ->
      when (req.url.encodedPath) {
        "/device/pending" -> {
          lookups++
          if (lookups == 1) respond("expired", HttpStatusCode.Unauthorized)
          else respond("""{"user_code":"WDJF-7K2P","origin_kind":"residential"}""", HttpStatusCode.OK, jsonCt)
        }
        "/auth/refresh" -> respond("""{"access":"fresh","refresh":"r2"}""", HttpStatusCode.OK, jsonCt)
        else -> respond("", HttpStatusCode.NotFound)
      }
    }))
    AuthEngine(store, client, ts).lookupDevice("WDJF-7K2P")
    assertEquals(2, lookups)                                      // retried after refresh
    assertEquals(Session("fresh", "r2"), store.state.session)     // rotated into state
    assertEquals(Session("fresh", "r2"), ts.session)             // and persisted
    assertEquals(Route.AuthorizeDevice, store.state.route)
    assertEquals("WDJF-7K2P", store.state.pendingDevice?.userCode)
  }

  @Test fun `approveDevice happy path sets the approved outcome`() = runBlocking<Unit> {
    val store = createAppStore(
      AppState(session = Session("a", "r"), route = Route.AuthorizeDevice, pendingDevice = PendingDevice("WDJF-7K2P")),
      debug = false,
    )
    var path = ""
    val client = AuthClient("https://api.test", HttpClient(MockEngine { req -> path = req.url.encodedPath; respond("", HttpStatusCode.NoContent) }))
    AuthEngine(store, client, MemTokenStore(Session("a", "r"))).approveDevice("fam1", "WDJF-7K2P")
    assertEquals("/families/fam1/device/approve", path)
    assertEquals("approved", store.state.deviceOutcome)
    assertFalse(store.state.deviceBusy)
  }

  @Test fun `approveDevice 404 race sets the expired outcome`() = runBlocking<Unit> {
    val store = createAppStore(
      AppState(session = Session("a", "r"), route = Route.AuthorizeDevice, pendingDevice = PendingDevice("X")),
      debug = false,
    )
    val client = AuthClient("https://api.test", HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }))
    AuthEngine(store, client, MemTokenStore(Session("a", "r"))).approveDevice("fam1", "X")
    assertEquals("expired", store.state.deviceOutcome)
  }

  @Test fun `approveDevice 403 (non-owner) surfaces an inline error, no outcome`() = runBlocking<Unit> {
    val store = createAppStore(
      AppState(session = Session("a", "r"), route = Route.AuthorizeDevice, pendingDevice = PendingDevice("X")),
      debug = false,
    )
    val client = AuthClient("https://api.test", HttpClient(MockEngine { respond("", HttpStatusCode.Forbidden) }))
    AuthEngine(store, client, MemTokenStore(Session("a", "r"))).approveDevice("fam2", "X")
    assertNull(store.state.deviceOutcome)
    assertTrue(store.state.deviceError?.contains("owner") == true, "was: ${store.state.deviceError}")
  }

  @Test fun `denyDevice sets the denied outcome (204 or already-gone)`() = runBlocking<Unit> {
    val mk = { status: HttpStatusCode ->
      val store = createAppStore(
        AppState(session = Session("a", "r"), route = Route.AuthorizeDevice, pendingDevice = PendingDevice("X")),
        debug = false,
      )
      val client = AuthClient("https://api.test", HttpClient(MockEngine { respond("", status) }))
      runBlocking { AuthEngine(store, client, MemTokenStore(Session("a", "r"))).denyDevice("fam1", "X") }
      store
    }
    assertEquals("denied", mk(HttpStatusCode.NoContent).state.deviceOutcome)
    assertEquals("denied", mk(HttpStatusCode.NotFound).state.deviceOutcome)   // gone == denied
  }

  // ── deep-link (Phase 2 client plumbing) ──
  @Test fun `openDeviceLink while signed-in looks up immediately`() = runBlocking<Unit> {
    val store = createAppStore(AppState(session = Session("a", "r"), route = Route.Feed), debug = false)
    val client = AuthClient("https://api.test", HttpClient(MockEngine { req ->
      if (req.url.encodedPath == "/device/pending")
        respond("""{"user_code":"WDJF-7K2P","origin_kind":"residential"}""", HttpStatusCode.OK, jsonCt)
      else respond("", HttpStatusCode.NotFound)
    }))
    AuthEngine(store, client, MemTokenStore(Session("a", "r"))).openDeviceLink("https://x/device?user_code=WDJF-7K2P")
    assertEquals(Route.AuthorizeDevice, store.state.route)
    assertEquals("WDJF-7K2P", store.state.pendingDevice?.userCode)
    assertNull(store.state.pendingDeviceLink)            // not stashed — handled live
  }

  @Test fun `openDeviceLink while signed-out stashes the code without navigating`() = runBlocking<Unit> {
    val store = createAppStore(AppState(route = Route.SignIn), debug = false)
    val client = AuthClient("https://api.test", HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }))
    AuthEngine(store, client, MemTokenStore(null), devSecret = "DEVSECRET").openDeviceLink("https://x/device?user_code=WDJF-7K2P")
    assertEquals(Route.SignIn, store.state.route)
    assertEquals("WDJF-7K2P", store.state.pendingDeviceLink)
  }

  @Test fun `openDeviceLink ignores a malformed payload`() = runBlocking<Unit> {
    val store = createAppStore(AppState(route = Route.SignIn), debug = false)
    val client = AuthClient("https://api.test", HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }))
    AuthEngine(store, client, MemTokenStore(null)).openDeviceLink("https://x/device")  // no user_code
    assertNull(store.state.pendingDeviceLink)
    assertEquals(Route.SignIn, store.state.route)
  }

  @Test fun `cold-install resume — stashed link opens after sign-in resolves memberships`() = runBlocking<Unit> {
    val ts = MemTokenStore(null)
    val store = createAppStore(AppState(route = Route.SignIn), debug = false)
    val client = AuthClient("https://api.test", HttpClient(MockEngine { req ->
      when (req.url.encodedPath) {
        "/auth/dev-token" -> respond("""{"access":"a1","refresh":"r1"}""", HttpStatusCode.OK, jsonCt)
        "/auth/whoami" -> respond(whoami(activeOwner), HttpStatusCode.OK, jsonCt)
        "/device/pending" -> respond("""{"user_code":"WDJF-7K2P","origin_kind":"datacenter"}""", HttpStatusCode.OK, jsonCt)
        else -> respond("", HttpStatusCode.NotFound)
      }
    }))
    val eng = AuthEngine(store, client, ts, devSecret = "DEVSECRET")
    eng.openDeviceLink("https://x/device?user_code=WDJF-7K2P")   // pre-sign-in tap → stashed
    assertEquals("WDJF-7K2P", store.state.pendingDeviceLink)
    eng.signIn("google")                                         // sign-in → memberships → resume
    assertNull(store.state.pendingDeviceLink)                    // consumed
    assertEquals(Route.AuthorizeDevice, store.state.route)       // resumed straight onto approve
    assertEquals("WDJF-7K2P", store.state.pendingDevice?.userCode)
  }

  // ── owner invite-mint (S7) ──
  @Test fun `mintInvite qr dispatches Minted and refreshes outstanding`() = runBlocking {
    val ts = MemTokenStore(Session("ax", "rx"))
    val (eng, store) = engine(ts, handler = MockEngine { req ->
      when {
        req.url.encodedPath == "/auth/whoami" -> respond(whoami(activeOwner), HttpStatusCode.OK, jsonCt)
        req.url.encodedPath == "/families/fam1/invites" && req.method.value == "POST" ->
          respond("""{"invite_id":"i","token":"TOK","url":"https://x/invite/TOK","role":"adult","mode":"qr","expires_at":"z"}""", HttpStatusCode.Created, jsonCt)
        req.url.encodedPath == "/families/fam1/invites" ->
          respond("""{"invites":[{"id":"i","role":"adult","mode":"qr","max_uses":1,"used_count":0,"expires_at":"z"}],"pending":[]}""", HttpStatusCode.OK, jsonCt)
        else -> respond("", HttpStatusCode.OK)
      }
    })
    eng.restore()
    eng.mintInvite("fam1", "qr")
    assertEquals("TOK", store.state.mintedInvite?.token)
    assertFalse(store.state.inviteBusy)
    assertEquals(1, store.state.outstandingInvites.size)         // refreshed after mint
  }

  @Test fun `mintInvite 429 dispatches ratelimited`() = runBlocking {
    val ts = MemTokenStore(Session("ax", "rx"))
    val (eng, store) = engine(ts, handler = MockEngine { req ->
      if (req.url.encodedPath == "/auth/whoami") respond(whoami(activeOwner), HttpStatusCode.OK, jsonCt)
      else respond("", HttpStatusCode.TooManyRequests)
    })
    eng.restore()
    eng.mintInvite("fam1", "link")
    assertEquals("ratelimited", store.state.mintError)
    assertFalse(store.state.inviteBusy)
  }

  @Test fun `mintInvite 403 dispatches forbidden`() = runBlocking {
    val ts = MemTokenStore(Session("ax", "rx"))
    val (eng, store) = engine(ts, handler = MockEngine { req ->
      if (req.url.encodedPath == "/auth/whoami") respond(whoami(activeOwner), HttpStatusCode.OK, jsonCt)
      else respond("", HttpStatusCode.Forbidden)
    })
    eng.restore()
    eng.mintInvite("fam1", "qr")
    assertEquals("forbidden", store.state.mintError)
  }

  @Test fun `revokeInvite drops the row from outstanding`() = runBlocking {
    val ts = MemTokenStore(Session("ax", "rx"))
    val (eng, store) = engine(ts, handler = MockEngine { req ->
      when {
        req.url.encodedPath == "/auth/whoami" -> respond(whoami(activeOwner), HttpStatusCode.OK, jsonCt)
        req.method.value == "DELETE" -> respond("", HttpStatusCode.NoContent)
        else -> respond("", HttpStatusCode.OK)
      }
    })
    eng.restore()
    store.dispatch(ApprovalsLoaded(emptyList(), listOf(Invite(id = "inv1", mode = "link", expiresAt = "z"))))
    eng.revokeInvite("fam1", "inv1")
    assertTrue(store.state.outstandingInvites.isEmpty())
  }

  // ── invite deep-link (ADR 0048) ──
  @Test fun `openInviteLink when signed in redeems immediately`() = runBlocking {
    val ts = MemTokenStore(Session("ax", "rx"))
    val (eng, store) = engine(ts, handler = MockEngine { req ->
      when (req.url.encodedPath) {
        "/auth/whoami" -> respond(whoami(activeOwner), HttpStatusCode.OK, jsonCt)
        "/invites:redeem" -> respond("""{"family_id":"fam1","family_name":"The Jacksons","role":"adult","status":"pending"}""", HttpStatusCode.OK, jsonCt)
        else -> respond("", HttpStatusCode.NotFound)
      }
    })
    eng.restore()
    eng.openInviteLink("https://x/invite/TOK_abc123")
    assertEquals(Route.JoinInvite, store.state.route)      // routed so the outcome is visible
    assertEquals("waiting", store.state.joinOutcome)       // redeemed → waiting-for-approval
    assertEquals("The Jacksons", store.state.joinFamilyName)
  }

  @Test fun `openInviteLink pre-sign-in stashes then redeems on sign-in`() = runBlocking {
    val ts = MemTokenStore(null)
    val store = createAppStore(AppState(route = Route.SignIn), debug = false)
    val client = AuthClient("https://api.test", HttpClient(MockEngine { req ->
      when (req.url.encodedPath) {
        "/auth/dev-token" -> respond("""{"access":"a1","refresh":"r1"}""", HttpStatusCode.OK, jsonCt)
        "/auth/whoami" -> respond(whoami(activeOwner), HttpStatusCode.OK, jsonCt)
        "/invites:redeem" -> respond("""{"family_id":"fam1","family_name":"The Jacksons","role":"adult","status":"pending"}""", HttpStatusCode.OK, jsonCt)
        else -> respond("", HttpStatusCode.NotFound)
      }
    }))
    val eng = AuthEngine(store, client, ts, devSecret = "DEVSECRET")
    eng.openInviteLink("https://x/invite/TOK_abc123")     // pre-sign-in → stashed
    assertEquals("TOK_abc123", store.state.pendingInviteLink)
    eng.signIn("google")                                  // sign-in → memberships → redeem
    assertNull(store.state.pendingInviteLink)             // consumed
    assertEquals(Route.JoinInvite, store.state.route)     // resumes onto the Join outcome screen
    assertEquals("waiting", store.state.joinOutcome)
  }

  @Test fun `openInviteLink ignores a non-invite URL`() = runBlocking {
    val ts = MemTokenStore(Session("ax", "rx"))
    val (eng, store) = engine(ts, handler = MockEngine { respond(whoami(activeOwner), HttpStatusCode.OK, jsonCt) })
    eng.restore()
    eng.openInviteLink("https://x/device?user_code=WDJF-7K2P")
    assertNull(store.state.pendingInviteLink)
    assertNull(store.state.joinOutcome)
  }

  @Test fun `loadApprovals feeds both pending queue and outstanding invites`() = runBlocking {
    val ts = MemTokenStore(Session("ax", "rx"))
    val (eng, store) = engine(ts, handler = MockEngine { req ->
      when (req.url.encodedPath) {
        "/auth/whoami" -> respond(whoami(activeOwner), HttpStatusCode.OK, jsonCt)
        "/families/fam1/invites" -> respond(
          """{"invites":[{"id":"inv1","role":"adult","mode":"link","max_uses":5,"used_count":0,"expires_at":"z"}],"pending":[{"uid":"u9","display_name":"Sam","role":"adult"}]}""",
          HttpStatusCode.OK, jsonCt,
        )
        else -> respond("", HttpStatusCode.NotFound)
      }
    })
    eng.restore()
    eng.loadApprovals("fam1")
    assertEquals(1, store.state.pendingApprovals.size)
    assertEquals(1, store.state.outstandingInvites.size)
  }
}
