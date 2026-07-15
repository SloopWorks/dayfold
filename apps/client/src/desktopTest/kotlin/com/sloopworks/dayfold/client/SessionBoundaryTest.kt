package com.sloopworks.dayfold.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Deterministic identity-boundary races spanning AuthEngine and SessionCoordinator. */
class SessionBoundaryTest {
  private class MemoryTokenStore(var session: Session? = null) : TokenStore {
    override fun load(): Session? = session
    override fun save(session: Session) { this.session = session }
    override fun clear() { session = null }
  }

  private val jsonContentType = headersOf(HttpHeaders.ContentType, "application/json")

  @Test fun `sign out invalidates and joins a blocked cached reconcile before tenant cleanup`() = runBlocking {
    val session = Session("ax", "rx")
    val tokenStore = MemoryTokenStore(session)
    val store = createTestAppStore(debug = false)
    val whoamiStarted = CompletableDeferred<Unit>()
    val whoamiFinished = CompletableDeferred<Unit>()
    var cacheClearedAfterReconcile = false
    val auth = AuthEngine(
      store = store,
      authClient = AuthClient("https://api.test", HttpClient(MockEngine { request ->
        when (request.url.encodedPath) {
          "/auth/whoami" -> {
            whoamiStarted.complete(Unit)
            try {
              awaitCancellation()
            } finally {
              whoamiFinished.complete(Unit)
            }
          }
          "/auth/signout" -> respond("", HttpStatusCode.NoContent)
          else -> respond("", HttpStatusCode.NotFound)
        }
      })),
      tokenStore = tokenStore,
      loadCachedMemberships = {
        listOf(FamilyMembership("fam1", "Family", "owner", "active"))
      },
      clearCache = { cacheClearedAfterReconcile = whoamiFinished.isCompleted },
    )

    auth.restore()
    whoamiStarted.await()
    auth.signOut()

    assertTrue(whoamiFinished.isCompleted, "sign-out must join reconcile before cache cleanup")
    assertTrue(cacheClearedAfterReconcile)
    assertNull(tokenStore.session)
    assertEquals(Route.SignIn, store.state.route)
  }

  @Test fun `blocked provider completion cannot install after sign out invalidates its ticket`() = runBlocking {
    val providerStarted = CompletableDeferred<Unit>()
    val releaseProvider = CompletableDeferred<Unit>()
    val tokenStore = MemoryTokenStore()
    val store = createTestAppStore(AppState(route = Route.SignIn), debug = false)
    val coordinator = SessionCoordinator(
      refreshScope = this,
      refreshSession = { error("refresh must not run") },
      commitRotation = { error("rotation must not commit") },
    )
    val auth = AuthEngine(
      store = store,
      authClient = AuthClient("https://api.test", HttpClient(MockEngine { request ->
        if (request.url.encodedPath == "/auth/firebase") {
          respond(
            """{"access":"late-a","refresh":"late-r"}""",
            HttpStatusCode.OK,
            jsonContentType,
          )
        } else {
          respond("", HttpStatusCode.NotFound)
        }
      })),
      tokenStore = tokenStore,
      devSecret = null,
      firebaseSignIn = {
        providerStarted.complete(Unit)
        releaseProvider.await()
        "late-provider-token"
      },
      sessionCoordinator = coordinator,
    )

    val signIn = async { auth.signIn("google") }
    providerStarted.await()
    auth.signOut()
    releaseProvider.complete(Unit)
    signIn.await()

    assertNull(coordinator.authSnapshot())
    assertNull(tokenStore.session)
    assertNull(store.state.session)
    assertEquals(Route.SignIn, store.state.route)
  }

  @Test fun `a new sign in waits for terminal cleanup and survives it`() = runBlocking {
    val old = Session("old-a", "old-r", "old-user")
    val tokenStore = MemoryTokenStore(old)
    val store = createTestAppStore(AppState(session = old, route = Route.Feed), debug = false)
    val cleanupEntered = CompletableDeferred<Unit>()
    val releaseCleanup = CompletableDeferred<Unit>()
    val providerExchanged = CompletableDeferred<Unit>()
    val auth = AuthEngine(
      store = store,
      authClient = AuthClient("https://api.test", HttpClient(MockEngine { request ->
        when (request.url.encodedPath) {
          "/auth/firebase" -> {
            providerExchanged.complete(Unit)
            respond(
              """{"access":"new-a","refresh":"new-r","user_id":"new-user"}""",
              HttpStatusCode.OK,
              jsonContentType,
            )
          }
          "/auth/whoami" -> respond(
            """{"families":[{"family_id":"fam-new","name":"New","role":"owner","status":"active"}]}""",
            HttpStatusCode.OK,
            jsonContentType,
          )
          "/auth/signout" -> respond("", HttpStatusCode.NoContent)
          else -> respond("", HttpStatusCode.NotFound)
        }
      })),
      tokenStore = tokenStore,
      beforeTerminalCleanup = {
        cleanupEntered.complete(Unit)
        releaseCleanup.await()
      },
    )

    val signOut = async { auth.signOut() }
    cleanupEntered.await()
    val signIn = async { auth.signIn("google", FirebaseSignIn { "provider-token" }) }
    providerExchanged.await()
    releaseCleanup.complete(Unit)
    signOut.await()
    signIn.await()

    assertEquals(Session("new-a", "new-r"), tokenStore.session)
    assertEquals("new-a", store.state.session?.access)
    assertEquals("fam-new", store.state.activeFamilyId)
    assertEquals(Route.Feed, store.state.route)
  }
}
