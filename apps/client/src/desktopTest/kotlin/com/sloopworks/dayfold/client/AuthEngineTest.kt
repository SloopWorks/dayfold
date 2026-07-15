package com.sloopworks.dayfold.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    databaseDispatcher: CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
    handler: MockEngine,
  ): Pair<AuthEngine, org.reduxkotlin.Store<AppState>> {
    val store = createTestAppStore(debug = false)
    val client = AuthClient("https://api.test", HttpClient(handler))
    return AuthEngine(
      store, client, ts, devSecret = devSecret,
      clearCache = onClearCache,
      loadCachedMemberships = { cached },
      saveMemberships = { savedMemberships.add(it) },
      databaseDispatcher = databaseDispatcher,
    ) to store
  }

  private fun whoami(families: String) =
    """{"family_id":null,"families":[$families]}"""
  private val activeOwner =
    """{"family_id":"fam1","name":"The Jacksons","role":"owner","status":"active"}"""

  private fun coordinator() = SessionCoordinator(
    refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    refreshSession = { error("refresh is not expected") },
    commitRotation = {},
  )

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

  @Test fun `membership cache load and save run off a responsive caller thread`() {
    val uiDispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "auth-ui") }
      .asCoroutineDispatcher()
    val databaseDispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "auth-db") }
      .asCoroutineDispatcher()
    val loadStarted = CompletableDeferred<Unit>()
    val releaseLoad = CountDownLatch(1)
    var loadThread = ""
    var saveThread = ""

    try {
      runBlocking(uiDispatcher) {
        val store = createTestAppStore(debug = false)
        val client = AuthClient("https://api.test", HttpClient(MockEngine { req ->
          when (req.url.encodedPath) {
            "/auth/whoami" -> respond(whoami(activeOwner), HttpStatusCode.OK, jsonCt)
            else -> respond("", HttpStatusCode.NotFound)
          }
        }))
        val auth = AuthEngine(
          store = store,
          authClient = client,
          tokenStore = MemTokenStore(Session("ax", "rx")),
          loadCachedMemberships = {
            loadThread = Thread.currentThread().name
            loadStarted.complete(Unit)
            check(releaseLoad.await(5, TimeUnit.SECONDS)) { "cache load was not released" }
            emptyList()
          },
          saveMemberships = { saveThread = Thread.currentThread().name },
          databaseDispatcher = databaseDispatcher,
        )

        val restore = launch { auth.restore() }
        loadStarted.await()
        var pulseThread = ""
        launch { pulseThread = Thread.currentThread().name }.join()

        assertTrue(pulseThread.startsWith("auth-ui"), "pulse ran on $pulseThread")
        assertFalse(restore.isCompleted, "held cache load should suspend, not block, the caller")
        releaseLoad.countDown()
        restore.join()
      }
    } finally {
      releaseLoad.countDown()
      uiDispatcher.close()
      databaseDispatcher.close()
    }

    assertTrue(loadThread.startsWith("auth-db"), "cache load ran on $loadThread")
    assertTrue(saveThread.startsWith("auth-db"), "cache save ran on $saveThread")
  }

  @Test fun `cache clear runs off a responsive caller thread`() {
    val uiDispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "auth-clear-ui") }
      .asCoroutineDispatcher()
    val databaseDispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "auth-clear-db") }
      .asCoroutineDispatcher()
    val clearStarted = CompletableDeferred<Unit>()
    val releaseClear = CountDownLatch(1)
    var clearThread = ""

    try {
      runBlocking(uiDispatcher) {
        val session = Session("ax", "rx")
        val store = createTestAppStore(AppState(session = session, route = Route.Feed), debug = false)
        val auth = AuthEngine(
          store = store,
          authClient = AuthClient("https://api.test", HttpClient(MockEngine {
            respond("", HttpStatusCode.NoContent)
          })),
          tokenStore = MemTokenStore(session),
          clearCache = {
            clearThread = Thread.currentThread().name
            clearStarted.complete(Unit)
            check(releaseClear.await(5, TimeUnit.SECONDS)) { "cache clear was not released" }
          },
          databaseDispatcher = databaseDispatcher,
        )

        val signOut = launch { auth.signOut() }
        clearStarted.await()
        var pulseThread = ""
        launch { pulseThread = Thread.currentThread().name }.join()

        assertTrue(pulseThread.startsWith("auth-clear-ui"), "pulse ran on $pulseThread")
        assertFalse(signOut.isCompleted, "held cache clear should suspend, not block, the caller")
        releaseClear.countDown()
        signOut.join()
        assertEquals(Route.SignIn, store.state.route)
      }
    } finally {
      releaseClear.countDown()
      uiDispatcher.close()
      databaseDispatcher.close()
    }

    assertTrue(clearThread.startsWith("auth-clear-db"), "cache clear ran on $clearThread")
  }

  @Test fun `remote sign-out cancellation still clears tenant data then propagates`() = runBlocking {
    val session = Session("ax", "rx")
    val tokenStore = MemTokenStore(session)
    val store = createTestAppStore(AppState(session = session, route = Route.Feed), debug = false)
    var cacheCleared = false
    val auth = AuthEngine(
      store = store,
      authClient = AuthClient("https://api.test", HttpClient(MockEngine {
        throw CancellationException("remote sign-out cancelled")
      })),
      tokenStore = tokenStore,
      clearCache = { cacheCleared = true },
    )

    assertFailsWith<CancellationException> { auth.signOut() }
    assertNull(tokenStore.session)
    assertTrue(cacheCleared)
    assertEquals(Route.SignIn, store.state.route)
  }

  @Test fun `dead-session cancellation at the DB hop still completes tenant cleanup`() {
    val databaseExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "dead-session-db") }
    val databaseDispatcher = databaseExecutor.asCoroutineDispatcher()
    val databaseOccupied = CountDownLatch(1)
    val releaseDatabase = CountDownLatch(1)
    val cleanupStarted = CompletableDeferred<Unit>()
    val session = Session("ax", "rx")
    var storedSession: Session? = session
    var cacheCleared = false
    var clearThread = ""

    try {
      val store = createTestAppStore(AppState(session = session, route = Route.Feed), debug = false)
      val tokenStore = object : TokenStore {
        override fun load(): Session? = storedSession
        override fun save(session: Session) { storedSession = session }
        override fun clear() { storedSession = null }
      }
      val auth = AuthEngine(
        store = store,
        authClient = AuthClient("https://api.test", HttpClient(MockEngine { req ->
          when (req.url.encodedPath) {
            "/auth/whoami", "/auth/refresh" -> respond("", HttpStatusCode.Unauthorized)
            else -> respond("", HttpStatusCode.NotFound)
          }
        })),
        tokenStore = tokenStore,
        loadCachedMemberships = {
          // This first DB hop is allowed to finish, but leaves a blocker queued ahead of the
          // later dead-session clear hop on the same single-thread dispatcher.
          databaseExecutor.execute {
            databaseOccupied.countDown()
            check(releaseDatabase.await(5, TimeUnit.SECONDS)) { "database dispatcher was not released" }
          }
          emptyList()
        },
        clearCache = {
          clearThread = Thread.currentThread().name
          cacheCleared = true
        },
        databaseDispatcher = databaseDispatcher,
        beforeTerminalCleanup = { cleanupStarted.complete(Unit) },
      )

      runBlocking {
        val restore = launch { auth.restore() }
        cleanupStarted.await() // terminal cleanup is now queued at the occupied DB hop
        assertTrue(databaseOccupied.await(5, TimeUnit.SECONDS), "database dispatcher was not occupied")
        restore.cancel()
        assertFalse(restore.isCompleted, "cancellation must not bypass tenant cache cleanup")
        releaseDatabase.countDown()
        restore.join()
        assertTrue(restore.isCancelled)
      }

      assertNull(storedSession)
      assertTrue(cacheCleared)
      assertTrue(clearThread.startsWith("dead-session-db"), "cache clear ran on $clearThread")
      assertEquals(Route.SignIn, store.state.route)
    } finally {
      releaseDatabase.countDown()
      databaseDispatcher.close()
    }
  }

  @Test fun `cache save cancellation propagates through sign in`() = runBlocking {
    val store = createTestAppStore(AppState(route = Route.SignIn), debug = false)
    val tokenStore = MemTokenStore()
    val cacheClears = AtomicInteger()
    val client = AuthClient("https://api.test", HttpClient(MockEngine { req ->
      when (req.url.encodedPath) {
        "/auth/dev-token" -> respond("""{"access":"a1","refresh":"r1"}""", HttpStatusCode.OK, jsonCt)
        "/auth/whoami" -> respond(whoami(""), HttpStatusCode.OK, jsonCt)
        else -> respond("", HttpStatusCode.NotFound)
      }
    }))
    val auth = AuthEngine(
      store = store,
      authClient = client,
      tokenStore = tokenStore,
      devSecret = "DEVSECRET",
      clearCache = { cacheClears.incrementAndGet() },
      saveMemberships = { throw CancellationException("cancel cache save") },
    )

    assertFailsWith<CancellationException> { auth.signIn("google") }
    assertNull(store.state.authError, "cancellation must not be translated into SignInFailed")
    assertNull(store.state.session)
    assertNull(tokenStore.session)
    assertEquals(2, cacheClears.get(), "admission clear plus terminal clear must both complete")
  }

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

  @Test fun `a second cached restore cancels and joins the previous reconciliation`() = runBlocking<Unit> {
    val calls = AtomicInteger()
    val firstStarted = CompletableDeferred<Unit>()
    val firstCancelled = CompletableDeferred<Unit>()
    val (eng, store) = engine(
      ts = MemTokenStore(Session("ax", "rx")),
      cached = listOf(activeFam),
      handler = MockEngine { req ->
        when (req.url.encodedPath) {
          "/auth/whoami" -> if (calls.incrementAndGet() == 1) {
            firstStarted.complete(Unit)
            try {
              awaitCancellation()
            } finally {
              firstCancelled.complete(Unit)
            }
          } else {
            respond(whoami(activeOwner), HttpStatusCode.OK, jsonCt)
          }
          "/families/fam1/members" -> respond("""{"members":[]}""", HttpStatusCode.OK, jsonCt)
          "/auth/me" -> respond("""{"uid":"u1"}""", HttpStatusCode.OK, jsonCt)
          else -> respond("", HttpStatusCode.NotFound)
        }
      },
    )

    eng.restore()
    firstStarted.await()
    eng.restore()
    withTimeout(3_000) { firstCancelled.await() }
    withTimeout(3_000) { while (calls.get() < 2) yield() }
    eng.reconcileJob?.join()
    assertEquals("fam1", store.state.activeFamilyId)
    assertEquals(2, calls.get())
  }

  @Test fun `reconcile overwrites the cached memberships with the whoami result and persists them`() = runBlocking {
    val ts = MemTokenStore(Session("ax", "rx"))
    val saved = mutableListOf<List<FamilyMembership>>()
    // HERMETICITY GATE: restore() launches reconcile() on a REAL background scope
    // (Dispatchers.Default), so without this the background whoami races the
    // "optimistic first" assertion below — the assertion wins on a fast machine and
    // loses on a loaded CI runner (observed: PR #323, AuthEngineTest.kt:126).
    // Holding the whoami response until the optimistic state is asserted makes the
    // ordering deterministic without a sleep.
    val whoamiGate = CompletableDeferred<Unit>()
    val (eng, store) = engine(ts,
      cached = listOf(FamilyMembership("stale", "Stale Fam", "adult", "active")),
      savedMemberships = saved,
      handler = MockEngine { req ->
        when (req.url.encodedPath) {
          "/auth/whoami" -> {
            whoamiGate.await()
            respond(whoami(activeOwner), HttpStatusCode.OK, jsonCt)   // fresh truth: fam1
          }
          "/families/fam1/members" -> respond("""{"members":[]}""", HttpStatusCode.OK, jsonCt)
          else -> respond("", HttpStatusCode.NotFound)
        }
      })
    eng.restore()
    assertEquals("stale", store.state.activeFamilyId)           // optimistic (cache) first
    whoamiGate.complete(Unit)                                   // …now let the background whoami land
    eng.reconcileJob?.join()
    assertEquals(listOf("fam1"), store.state.families.map { it.familyId })   // overwritten by whoami
    assertEquals(listOf("fam1"), saved.last().map { it.familyId })           // persisted for next cold start
    assertEquals(Route.Feed, store.state.route)
  }

  @Test fun `reconcile with a dead session clears the token and cache and routes to SignIn`() = runBlocking {
    val ts = MemTokenStore(Session("ax", "rx"))
    var cleared = false
    // Same hermeticity gate as above: the 401 reconcile would otherwise be free to route
    // to SignIn before the "optimistic first" assertion runs.
    val whoamiGate = CompletableDeferred<Unit>()
    val (eng, store) = engine(ts, cached = listOf(activeFam), onClearCache = { cleared = true },
      handler = MockEngine { req ->
        when (req.url.encodedPath) {
          "/auth/whoami" -> {
            whoamiGate.await()
            respond("", HttpStatusCode.Unauthorized)                   // access dead
          }
          "/auth/refresh" -> respond("", HttpStatusCode.Unauthorized)  // …refresh can't recover
          else -> respond("", HttpStatusCode.NotFound)
        }
      })
    eng.restore()
    assertEquals(Route.Feed, store.state.route)                 // optimistic first
    whoamiGate.complete(Unit)
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
    val store = createTestAppStore(AppState(route = Route.SignIn), debug = false)
    val client = AuthClient("https://api.test", HttpClient(MockEngine { respond("", HttpStatusCode.OK) }))
    AuthEngine(store, client, MemTokenStore(null), devSecret = null).signIn("apple")
    assertEquals(Route.SignIn, store.state.route)                 // failure stays put, no nav
    assertTrue(store.state.authError?.contains("S2") == true, "was: ${store.state.authError}")
    assertNull(store.state.session)
  }

  @Test fun `sign-in uses the firebase id token when the platform yields one`() = runBlocking {
    val ts = MemTokenStore(null)
    var firebaseHit = false; var devHit = false
    val store = createTestAppStore(debug = false)
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
    val store = createTestAppStore(debug = false)
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
    val store = createTestAppStore(AppState(session = Session("a1", "r1"), route = Route.CreateFamily), debug = false)
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
    val store = createTestAppStore(
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
    val store = createTestAppStore(
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
    val store = createTestAppStore(AppState(route = Route.SignIn), debug = false)
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
    val store = createTestAppStore(AppState(session = Session("a", "r")), debug = false)
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
    val store = createTestAppStore(AppState(session = Session("a", "r")), debug = false)
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
    val store = createTestAppStore(
      AppState(session = Session("a", "r"), pendingApprovals = listOf(PendingMember("u9", "Sam"), PendingMember("u8", "Mo"))),
      debug = false,
    )
    val client = AuthClient("https://api.test", HttpClient(MockEngine { respond("", HttpStatusCode.NoContent) }))
    AuthEngine(store, client, MemTokenStore(Session("a", "r"))).approveMember("fam1", "u9")
    assertEquals(listOf("u8"), store.state.pendingApprovals.map { it.uid })
  }

  @Test fun `loadMembers fills the roster`() = runBlocking {
    val store = createTestAppStore(AppState(session = Session("a", "r")), debug = false)
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
    val store = createTestAppStore(
      AppState(session = Session("a", "r"), members = listOf(FamilyMember("u1", "Pat", role = "owner"), FamilyMember("u2", "Maya"))),
      debug = false,
    )
    val client = AuthClient("https://api.test", HttpClient(MockEngine { respond("", HttpStatusCode.NoContent) }))
    AuthEngine(store, client, MemTokenStore(Session("a", "r"))).removeMember("fam1", "u2")
    assertEquals(listOf("u1"), store.state.members.map { it.uid })
  }

  // ── connected devices ──
  @Test fun `loadDevices fills the device list`() = runBlocking {
    val store = createTestAppStore(AppState(session = Session("a", "r")), debug = false)
    val client = AuthClient("https://api.test", HttpClient(MockEngine { req ->
      if (req.url.encodedPath == "/auth/me/credentials")
        respond("""{"credentials":[{"id":"c1","kind":"app","current":true}]}""", HttpStatusCode.OK, jsonCt)
      else respond("", HttpStatusCode.NotFound)
    }))
    AuthEngine(store, client, MemTokenStore(Session("a", "r"))).loadDevices()
    assertEquals(1, store.state.devices.size)
    assertEquals("c1", store.state.devices[0].id)
  }

  @Test fun `independent auth reads do not wait behind a slow device request`() = runBlocking<Unit> {
    val deviceStarted = CompletableDeferred<Unit>()
    val releaseDevice = CompletableDeferred<Unit>()
    val session = Session("a", "r")
    val store = createTestAppStore(AppState(session = session, activeFamilyId = "fam1"), debug = false)
    val client = AuthClient("https://api.test", HttpClient(MockEngine { req ->
      when (req.url.encodedPath) {
        "/auth/me/credentials" -> {
          deviceStarted.complete(Unit)
          releaseDevice.await()
          respond("""{"credentials":[{"id":"old","kind":"app"}]}""", HttpStatusCode.OK, jsonCt)
        }
        "/families/fam1/members" ->
          respond("""{"members":[{"uid":"u1","display_name":"Pat"}]}""", HttpStatusCode.OK, jsonCt)
        else -> respond("", HttpStatusCode.NotFound)
      }
    }))
    val auth = AuthEngine(store, client, MemTokenStore(session))

    val devices = launch { auth.loadDevices() }
    deviceStarted.await()
    withTimeout(1_000) { auth.loadMembers("fam1") }
    assertEquals(listOf("u1"), store.state.members.map { it.uid })
    releaseDevice.complete(Unit)
    devices.join()
  }

  @Test fun `sign out preempts a slow auth read and rejects its late result`() = runBlocking<Unit> {
    val deviceStarted = CompletableDeferred<Unit>()
    val releaseDevice = CompletableDeferred<Unit>()
    val session = Session("a", "r")
    val tokens = MemTokenStore(session)
    val store = createTestAppStore(AppState(session = session, route = Route.Feed), debug = false)
    val client = AuthClient("https://api.test", HttpClient(MockEngine { req ->
      when (req.url.encodedPath) {
        "/auth/me/credentials" -> {
          deviceStarted.complete(Unit)
          releaseDevice.await()
          respond("""{"credentials":[{"id":"late","kind":"app"}]}""", HttpStatusCode.OK, jsonCt)
        }
        "/auth/signout" -> respond("", HttpStatusCode.NoContent)
        else -> respond("", HttpStatusCode.NotFound)
      }
    }))
    val auth = AuthEngine(store, client, tokens)

    val devices = launch { auth.loadDevices() }
    deviceStarted.await()
    withTimeout(1_000) { auth.signOut() }
    assertEquals(Route.SignIn, store.state.route)
    assertNull(tokens.session)
    releaseDevice.complete(Unit)
    devices.join()
    assertTrue(store.state.devices.isEmpty(), "a late pre-sign-out read must not repopulate state")
  }

  @Test fun `revokeDevice drops from the list on success`() = runBlocking {
    val store = createTestAppStore(
      AppState(session = Session("a", "r"), devices = listOf(DeviceCredential("c1", current = true), DeviceCredential("c2"))),
      debug = false,
    )
    val client = AuthClient("https://api.test", HttpClient(MockEngine { respond("", HttpStatusCode.NoContent) }))
    AuthEngine(store, client, MemTokenStore(Session("a", "r"))).revokeDevice("c2")
    assertEquals(listOf("c1"), store.state.devices.map { it.id })
  }

  // ── own avatar (task 4 fix — optimistic op-start + revert-on-failure) ──
  @Test fun `updateAvatar applies the server-returned value on success and clears avatarOpId`() = runBlocking {
    val store = createTestAppStore(
      AppState(session = Session("a", "r"), myAvatarColor = "teal", myAvatarRef = "avatar:fox-01"),
      debug = false,
    )
    val client = AuthClient("https://api.test", HttpClient(MockEngine { req ->
      if (req.url.encodedPath == "/auth/me")
        respond("""{"display_name":"Pat","avatar_color":"coral","avatar_ref":"avatar:sun-01"}""", HttpStatusCode.OK, jsonCt)
      else respond("", HttpStatusCode.NotFound)
    }))
    AuthEngine(store, client, MemTokenStore(Session("a", "r"))).updateAvatar("coral", "avatar:sun-01")
    assertEquals("coral", store.state.myAvatarColor)
    assertEquals("avatar:sun-01", store.state.myAvatarRef)
    assertNull(store.state.avatarOpId)
    assertNull(store.state.avatarError)
  }

  @Test fun `updateAvatar reverts to the previous value and sets avatarError on failure`() = runBlocking {
    val store = createTestAppStore(
      AppState(session = Session("a", "r"), myAvatarColor = "teal", myAvatarRef = "avatar:fox-01"),
      debug = false,
    )
    val client = AuthClient("https://api.test", HttpClient(MockEngine { respond("", HttpStatusCode.BadRequest) }))
    AuthEngine(store, client, MemTokenStore(Session("a", "r"))).updateAvatar("coral", "avatar:sun-01")
    // reverted — the failed PATCH must not leave the picked value showing
    assertEquals("teal", store.state.myAvatarColor)
    assertEquals("avatar:fox-01", store.state.myAvatarRef)
    assertNull(store.state.avatarOpId)
    assertTrue(store.state.avatarError != null, "expected avatarError to be set on failure")
  }

  // ── CLI/device approval (S6-D) ── runBlocking<Unit> per the agent-dev-loop JUnit gotcha.
  @Test fun `lookupDevice happy path loads the grant and routes to AuthorizeDevice`() = runBlocking<Unit> {
    val store = createTestAppStore(AppState(session = Session("a", "r"), route = Route.EnterCode), debug = false)
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
    val store = createTestAppStore(AppState(session = Session("a", "r"), route = Route.EnterCode), debug = false)
    val client = AuthClient("https://api.test", HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }))
    AuthEngine(store, client, MemTokenStore(Session("a", "r"))).lookupDevice("XXXX-YYYY")
    assertEquals(Route.AuthorizeDevice, store.state.route)
    assertEquals("expired", store.state.deviceOutcome)
    assertNull(store.state.pendingDevice)
  }

  @Test fun `lookupDevice 429 stays on EnterCode with an inline error`() = runBlocking<Unit> {
    val store = createTestAppStore(AppState(session = Session("a", "r"), route = Route.EnterCode), debug = false)
    val client = AuthClient("https://api.test", HttpClient(MockEngine { respond("", HttpStatusCode.TooManyRequests) }))
    AuthEngine(store, client, MemTokenStore(Session("a", "r"))).lookupDevice("X")
    assertEquals(Route.EnterCode, store.state.route)
    assertTrue(store.state.deviceError?.contains("15 minutes") == true, "was: ${store.state.deviceError}")
  }

  @Test fun `lookupDevice 401 refreshes once and retries`() = runBlocking<Unit> {
    val ts = MemTokenStore(Session("stale", "r1"))
    var lookups = 0
    val store = createTestAppStore(AppState(session = Session("stale", "r1"), route = Route.EnterCode), debug = false)
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
    val store = createTestAppStore(
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
    val store = createTestAppStore(
      AppState(session = Session("a", "r"), route = Route.AuthorizeDevice, pendingDevice = PendingDevice("X")),
      debug = false,
    )
    val client = AuthClient("https://api.test", HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }))
    AuthEngine(store, client, MemTokenStore(Session("a", "r"))).approveDevice("fam1", "X")
    assertEquals("expired", store.state.deviceOutcome)
  }

  @Test fun `approveDevice 403 (non-owner) surfaces an inline error, no outcome`() = runBlocking<Unit> {
    val store = createTestAppStore(
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
      val store = createTestAppStore(
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
    val store = createTestAppStore(AppState(session = Session("a", "r"), route = Route.Feed), debug = false)
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
    val store = createTestAppStore(AppState(route = Route.SignIn), debug = false)
    val client = AuthClient("https://api.test", HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }))
    AuthEngine(store, client, MemTokenStore(null), devSecret = "DEVSECRET").openDeviceLink("https://x/device?user_code=WDJF-7K2P")
    assertEquals(Route.SignIn, store.state.route)
    assertEquals("WDJF-7K2P", store.state.pendingDeviceLink)
  }

  @Test fun `openDeviceLink ignores a malformed payload`() = runBlocking<Unit> {
    val store = createTestAppStore(AppState(route = Route.SignIn), debug = false)
    val client = AuthClient("https://api.test", HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }))
    AuthEngine(store, client, MemTokenStore(null)).openDeviceLink("https://x/device")  // no user_code
    assertNull(store.state.pendingDeviceLink)
    assertEquals(Route.SignIn, store.state.route)
  }

  @Test fun `cold-install resume — stashed link opens after sign-in resolves memberships`() = runBlocking<Unit> {
    val ts = MemTokenStore(null)
    val store = createTestAppStore(AppState(route = Route.SignIn), debug = false)
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
    val store = createTestAppStore(AppState(route = Route.SignIn), debug = false)
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

  @Test fun `reconcile replacement does not deadlock with terminal cleanup`() = runBlocking<Unit> {
    val terminalEntered = CompletableDeferred<Unit>()
    val releaseTerminal = CompletableDeferred<Unit>()
    val session = Session("a", "r")
    val store = createTestAppStore(debug = false)
    val auth = AuthEngine(
      store = store,
      authClient = AuthClient("https://api.test", HttpClient(MockEngine { req ->
        when (req.url.encodedPath) {
          "/auth/whoami", "/auth/refresh" -> respond("", HttpStatusCode.Unauthorized)
          else -> respond("", HttpStatusCode.NotFound)
        }
      })),
      tokenStore = MemTokenStore(session),
      loadCachedMemberships = { listOf(activeFam) },
    )
    auth.afterTerminalInvalidationHook = {
      terminalEntered.complete(Unit)
      releaseTerminal.await()
    }

    auth.restore()
    terminalEntered.await()
    val replacement = async { auth.restore() }
    withTimeout(3_000) { while (auth.reconcileJob != null) yield() }
    releaseTerminal.complete(Unit)
    withTimeout(3_000) { replacement.await() }
    assertTrue(store.state.route != Route.Loading, "replacement must complete after terminal cleanup")
  }

  @Test fun `stale blocked sign-in cannot wipe a newer dev identity cache`() = runBlocking<Unit> {
    val exchangeStarted = CompletableDeferred<Unit>()
    val releaseExchange = CompletableDeferred<Unit>()
    val clears = AtomicInteger()
    val store = createTestAppStore(AppState(route = Route.SignIn), debug = false)
    val auth = AuthEngine(
      store = store,
      authClient = AuthClient("https://api.test", HttpClient(MockEngine { req ->
        if (req.url.encodedPath == "/auth/firebase") {
          exchangeStarted.complete(Unit)
          releaseExchange.await()
          respond("""{"access":"old-a","refresh":"old-r"}""", HttpStatusCode.OK, jsonCt)
        } else respond("", HttpStatusCode.NotFound)
      })),
      tokenStore = MemTokenStore(),
      clearCache = { clears.incrementAndGet() },
    )

    val old = launch { auth.signIn("google") { "provider-token" } }
    exchangeStarted.await()
    auth.devSignIn()
    releaseExchange.complete(Unit)
    old.join()
    assertEquals(1, clears.get(), "only the winning dev identity may clear the cache")
    assertEquals(DEV_TOKEN, store.state.session?.access)
    assertEquals("dev-family", store.state.activeFamilyId)
  }

  @Test fun `cancelled blocked sign-in never reaches destructive cache admission`() = runBlocking<Unit> {
    val exchangeStarted = CompletableDeferred<Unit>()
    val releaseExchange = CompletableDeferred<Unit>()
    val clears = AtomicInteger()
    val coordinator = coordinator()
    val store = createTestAppStore(AppState(route = Route.SignIn), debug = false)
    val auth = AuthEngine(
      store = store,
      authClient = AuthClient("https://api.test", HttpClient(MockEngine { req ->
        if (req.url.encodedPath == "/auth/firebase") {
          exchangeStarted.complete(Unit)
          releaseExchange.await()
          respond("""{"access":"old-a","refresh":"old-r"}""", HttpStatusCode.OK, jsonCt)
        } else respond("", HttpStatusCode.NotFound)
      })),
      tokenStore = MemTokenStore(),
      clearCache = { clears.incrementAndGet() },
      sessionCoordinator = coordinator,
    )

    val old = launch { auth.signIn("google") { "provider-token" } }
    exchangeStarted.await()
    coordinator.invalidate()
    releaseExchange.complete(Unit)
    old.join()
    assertEquals(0, clears.get())
    assertNull(store.state.session)
  }

  @Test fun `stale family terminal rejection cannot expire the selected replacement family`() = runBlocking<Unit> {
    val session = Session("a", "r")
    val coordinator = coordinator()
    val authContext = coordinator.install(session)
    val oldFamily = checkNotNull(coordinator.selectFamily(authContext, "old"))
    checkNotNull(coordinator.selectFamily(authContext, "new"))
    val tokens = MemTokenStore(session)
    val store = createTestAppStore(AppState(session = session, activeFamilyId = "new", route = Route.Feed), debug = false)
    val auth = AuthEngine(
      store = store,
      authClient = AuthClient("https://api.test", HttpClient(MockEngine { respond("", HttpStatusCode.OK) })),
      tokenStore = tokens,
      sessionCoordinator = coordinator,
    )

    auth.terminateFamilySession(oldFamily, expired = true)
    assertEquals(session, tokens.session)
    assertEquals(session, store.state.session)
    assertTrue(coordinator.authSnapshot() != null)
  }

  @Test fun `overlapping approvals load cannot strand mint busy or publish stale rows`() = runBlocking<Unit> {
    val firstLoadStarted = CompletableDeferred<Unit>()
    val releaseFirstLoad = CompletableDeferred<Unit>()
    val loads = AtomicInteger()
    val session = Session("a", "r")
    val store = createTestAppStore(AppState(session = session, activeFamilyId = "fam1"), debug = false)
    val auth = AuthEngine(
      store,
      AuthClient("https://api.test", HttpClient(MockEngine { req ->
        when {
          req.url.encodedPath == "/families/fam1/invites" && req.method.value == "GET" -> {
            if (loads.incrementAndGet() == 1) {
              firstLoadStarted.complete(Unit)
              releaseFirstLoad.await()
              respond(
                """{"invites":[{"id":"stale","mode":"link","expires_at":"z"}],"pending":[]}""",
                HttpStatusCode.OK,
                jsonCt,
              )
            } else respond(
              """{"invites":[{"id":"fresh","mode":"link","expires_at":"z"}],"pending":[]}""",
              HttpStatusCode.OK,
              jsonCt,
            )
          }
          req.url.encodedPath == "/families/fam1/invites" && req.method.value == "POST" ->
            respond("""{"invite_id":"minted","token":"TOK","url":"https://x","role":"adult","mode":"qr","expires_at":"z"}""", HttpStatusCode.Created, jsonCt)
          else -> respond("", HttpStatusCode.NotFound)
        }
      })),
      MemTokenStore(session),
    )

    val staleLoad = launch { auth.loadApprovals("fam1") }
    firstLoadStarted.await()
    withTimeout(3_000) { auth.mintInvite("fam1", "qr") }
    releaseFirstLoad.complete(Unit)
    staleLoad.join()
    assertFalse(store.state.inviteBusy)
    assertFalse(store.state.approvalsBusy)
    assertEquals("TOK", store.state.mintedInvite?.token)
    assertEquals(listOf("fresh"), store.state.outstandingInvites.map { it.id })
  }

  @Test fun `overlapping approvals load cannot strand revoke or member busy`() = runBlocking<Unit> {
    suspend fun runMutation(mutate: suspend (AuthEngine) -> Unit): AppState {
      val firstLoadStarted = CompletableDeferred<Unit>()
      val releaseFirstLoad = CompletableDeferred<Unit>()
      val loads = AtomicInteger()
      val session = Session("a", "r")
      val store = createTestAppStore(
        AppState(
          session = session,
          activeFamilyId = "fam1",
          outstandingInvites = listOf(Invite(id = "i1", mode = "link", expiresAt = "z")),
          pendingApprovals = listOf(PendingMember(uid = "u1", displayName = "Pat", role = "adult")),
        ),
        debug = false,
      )
      val auth = AuthEngine(
        store,
        AuthClient("https://api.test", HttpClient(MockEngine { req ->
          when {
            req.url.encodedPath == "/families/fam1/invites" && req.method.value == "GET" -> {
              if (loads.incrementAndGet() == 1) {
                firstLoadStarted.complete(Unit)
                releaseFirstLoad.await()
                respond("""{"invites":[{"id":"stale"}],"pending":[{"uid":"stale"}]}""", HttpStatusCode.OK, jsonCt)
              } else respond("""{"invites":[],"pending":[]}""", HttpStatusCode.OK, jsonCt)
            }
            req.method.value == "DELETE" || req.url.encodedPath.endsWith(":approve") ->
              respond("", HttpStatusCode.NoContent)
            else -> respond("", HttpStatusCode.NotFound)
          }
        })),
        MemTokenStore(session),
      )
      val staleLoad = launch { auth.loadApprovals("fam1") }
      firstLoadStarted.await()
      withTimeout(3_000) { mutate(auth) }
      releaseFirstLoad.complete(Unit)
      staleLoad.join()
      return store.state
    }

    val revoke = runMutation { it.revokeInvite("fam1", "i1") }
    assertNull(revoke.inviteOpId)
    assertFalse(revoke.approvalsBusy)
    assertTrue(revoke.outstandingInvites.isEmpty())

    val member = runMutation { it.approveMember("fam1", "u1") }
    assertNull(member.memberOpId)
    assertFalse(member.approvalsBusy)
    assertTrue(member.pendingApprovals.isEmpty())
  }

  @Test fun `same-resource avatar mutations cannot roll back a newer success`() = runBlocking<Unit> {
    val firstStarted = CompletableDeferred<Unit>()
    val releaseFirst = CompletableDeferred<Unit>()
    val calls = AtomicInteger()
    val session = Session("a", "r")
    val store = createTestAppStore(
      AppState(session = session, myAvatarColor = "old", myAvatarRef = "avatar:old"),
      debug = false,
    )
    val auth = AuthEngine(
      store,
      AuthClient("https://api.test", HttpClient(MockEngine { req ->
        if (req.url.encodedPath == "/auth/me" && calls.incrementAndGet() == 1) {
          firstStarted.complete(Unit)
          releaseFirst.await()
          respond("", HttpStatusCode.InternalServerError)
        } else respond(
          """{"display_name":"Pat","avatar_color":"new","avatar_ref":"avatar:new"}""",
          HttpStatusCode.OK,
          jsonCt,
        )
      })),
      MemTokenStore(session),
    )

    val first = launch { auth.updateAvatar("first", "avatar:first") }
    firstStarted.await()
    val second = async { auth.updateAvatar("new", "avatar:new") }
    yield()
    assertFalse(second.isCompleted, "same-resource mutation must wait for its predecessor")
    releaseFirst.complete(Unit)
    first.join()
    second.await()
    assertEquals("new", store.state.myAvatarColor)
    assertEquals("avatar:new", store.state.myAvatarRef)
    assertNull(store.state.avatarOpId)
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
