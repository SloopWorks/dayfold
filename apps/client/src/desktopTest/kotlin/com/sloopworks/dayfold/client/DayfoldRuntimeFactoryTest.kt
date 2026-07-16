package com.sloopworks.dayfold.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import org.reduxkotlin.concurrent.NotificationContext

class DayfoldRuntimeFactoryTest {
  private class QueuedDispatcher : CoroutineDispatcher() {
    private val tasks = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
      tasks.addLast(block)
    }

    fun runUntilIdle() {
      while (tasks.isNotEmpty()) tasks.removeFirst().run()
    }
  }

  private class MemoryTokenStore(var session: Session? = null) : TokenStore {
    val saves = AtomicInteger()
    val clears = AtomicInteger()

    override fun load(): Session? = session

    override fun save(session: Session) {
      this.session = session
      saves.incrementAndGet()
    }

    override fun clear() {
      session = null
      clears.incrementAndGet()
    }
  }

  private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

  @Test fun `factory creates one transport and all engines use the shared rotated session`() =
    runBlocking<Unit> {
      val initial = Session("old-access", "old-refresh", "user-1")
      val rotated = Session("new-access", "new-refresh", "user-1")
      val tokens = MemoryTokenStore(initial)
      val requests = Collections.synchronizedList(mutableListOf<String>())
      val httpCreations = AtomicInteger()
      val closes = AtomicInteger()
      val graph = factory(
        contentStore = freshContentStore(),
        tokenStore = tokens,
        initialState = readyState(initial),
        httpClientFactory = {
          httpCreations.incrementAndGet()
          HttpClient(MockEngine { request ->
            requests += "${request.url.encodedPath}:${request.headers[HttpHeaders.Authorization]}"
            when {
              request.url.encodedPath.endsWith("/sync") -> respond(
                """{"changes":{},"tombstones":[],"next_cursor":"c1","has_more":false}""",
                HttpStatusCode.OK,
                jsonHeaders,
              )
              request.url.encodedPath.endsWith("/audience") -> respond(
                """{"visibility":"family","members":[]}""",
                HttpStatusCode.OK,
                jsonHeaders,
              )
              else -> respond("", HttpStatusCode.NotFound)
            }
          })
        },
        onResourcesClosed = { closes.incrementAndGet() },
      ).create()

      try {
        val expected = requireNotNull(graph.sessionCoordinator.authSnapshot())
        assertNotNull(graph.sessionCoordinator.rotate(expected, rotated))
        assertEquals(rotated, graph.store.state.session.session)

        graph.syncEngine.syncNow()
        graph.hubEngine.openHub("hub-1")
        graph.store.dispatch(OpenAudienceSheet)
        graph.hubEngine.loadAudience("hub-1")

        assertEquals(1, httpCreations.get())
        assertEquals(1, tokens.saves.get())
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.endsWith(":Bearer new-access") }, requests.toString())
      } finally {
        graph.cancel()
        graph.awaitClosed()
      }
      assertEquals(1, closes.get())
    }

  @Test fun `cached membership reconciliation keeps command hub taps admitted`() =
    runBlocking<Unit> {
      val session = Session("access", "refresh", "user-1")
      val tokens = MemoryTokenStore(session)
      val content = freshContentStore().also {
        it.replaceMemberships(
          listOf(FamilyMembership("fam-1", "Family", role = "owner", status = "active")),
        )
      }
      val reconcileStarted = CompletableDeferred<Unit>()
      val releaseReconcile = CompletableDeferred<Unit>()
      val membershipReconfirmed = CompletableDeferred<Unit>()
      val graph = factory(
        contentStore = content,
        tokenStore = tokens,
        initialState = AppState(navigation = NavigationState(route = Route.Loading)),
        httpClientFactory = {
          HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
              "/auth/whoami" -> {
                reconcileStarted.complete(Unit)
                releaseReconcile.await()
                respond(
                  """{"families":[{"family_id":"fam-1","name":"Family","role":"owner","status":"active"}]}""",
                  HttpStatusCode.OK,
                  jsonHeaders,
                )
              }
              "/families/fam-1/members" -> {
                membershipReconfirmed.complete(Unit)
                respond("""{"members":[]}""", HttpStatusCode.OK, jsonHeaders)
              }
              "/auth/me" -> respond(
                """{"user_id":"user-1","display_name":"Pat"}""",
                HttpStatusCode.OK,
                jsonHeaders,
              )
              else -> respond("", HttpStatusCode.NotFound)
            }
          })
        },
      ).create()

      try {
        val cachedMembershipPublished = CompletableDeferred<Unit>()
        val unsubscribeMembership = graph.store.subscribe {
          if (graph.store.state.session.activeFamilyId == "fam-1") cachedMembershipPublished.complete(Unit)
        }
        graph.start()
        try {
          withTimeout(2_000) { cachedMembershipPublished.await() }
        } finally {
          unsubscribeMembership()
        }
        withTimeout(2_000) { reconcileStarted.await() }

        // Model the runtime owner already bound to the cached family before whoami confirms the
        // same membership. Re-confirmation must not invalidate that owner's family generation.
        val boundFamily = assertNotNull(graph.replaceFamily("fam-1"))
        releaseReconcile.complete(Unit)
        withTimeout(2_000) { membershipReconfirmed.await() }
        assertTrue(
          graph.sessionCoordinator.isCurrent(boundFamily),
          "confirming cached membership must not stale the runtime's bound family generation",
        )

        val hubOpened = CompletableDeferred<Unit>()
        val unsubscribeHub = graph.store.subscribe {
          if (graph.store.state.hubs.currentHubId == "hub-1") hubOpened.complete(Unit)
        }
        try {
          graph.commands.openHub("fam-1", "hub-1")
          withTimeout(2_000) { hubOpened.await() }
        } finally {
          unsubscribeHub()
        }
        assertEquals("hub-1", graph.store.state.hubs.currentHubId)
      } finally {
        graph.cancel()
        graph.awaitClosed()
      }
    }

  @Test fun `queued hub tap from an old A generation is rejected after A B A`() =
    runBlocking<Unit> {
      val session = Session("access", "refresh", "user-1")
      val dispatcher = QueuedDispatcher()
      val graph = DayfoldRuntimeFactory(
        api = "https://api.test",
        contentStore = freshContentStore(),
        tokenStore = MemoryTokenStore(session),
        notificationContext = NotificationContext.Inline,
        httpClientFactory = { HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }) },
        backgroundDispatcher = dispatcher,
        databaseDispatcher = dispatcher,
        initialState = readyState(session),
        debug = false,
      ).create()

      try {
        val auth = assertNotNull(graph.sessionCoordinator.authSnapshot())
        val originalA = assertNotNull(graph.sessionCoordinator.familySnapshot("fam-1"))
        graph.commands.openHub("fam-1", "old-a-hub")

        assertNotNull(graph.sessionCoordinator.selectFamily(auth, "fam-2"))
        val replacementA = assertNotNull(graph.sessionCoordinator.selectFamily(auth, "fam-1"))
        assertTrue(graph.sessionCoordinator.isCurrent(replacementA))
        assertTrue(!graph.sessionCoordinator.isCurrent(originalA))

        dispatcher.runUntilIdle()

        assertNull(
          graph.store.state.hubs.currentHubId,
          "a queued tap captured in the original A must not open in replacement A",
        )
      } finally {
        graph.cancel()
        dispatcher.runUntilIdle()
        graph.awaitClosed()
      }
    }

  @Test fun `coordinator owned sync rejection completes root owned terminal cleanup`() =
    runBlocking<Unit> {
      val session = Session("expired-access", "expired-refresh", "user-1")
      val tokens = MemoryTokenStore(session)
      val closes = AtomicInteger()
      val content = freshContentStore().also {
        it.applyDelta(
          changedCards = listOf(Card("private", title = "Private")),
          changedHubs = emptyList(),
          tombstones = emptyList(),
          nextCursor = "old",
          nowIso = "2026-07-14T12:00:00Z",
        )
      }
      val graph = factory(
        contentStore = content,
        tokenStore = tokens,
        initialState = AppState(session = SessionState(session = session), navigation = NavigationState(route = Route.Loading)),
        httpClientFactory = {
          HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
              "/auth/whoami" -> respond(
                """{"families":[{"family_id":"fam-1","name":"Family","role":"owner","status":"active"}]}""",
                HttpStatusCode.OK,
                jsonHeaders,
              )
              "/families/fam-1/sync" -> respond("", HttpStatusCode.Unauthorized)
              "/auth/refresh" -> respond("", HttpStatusCode.Unauthorized)
              else -> respond("", HttpStatusCode.NotFound)
            }
          })
        },
        onResourcesClosed = { closes.incrementAndGet() },
      ).create()

      try {
        val membershipsLoaded = CompletableDeferred<Unit>()
        val unsubscribeMemberships = graph.store.subscribe {
          if (graph.store.state.session.activeFamilyId == "fam-1") membershipsLoaded.complete(Unit)
        }
        graph.start()
        try {
          withTimeout(2_000) { membershipsLoaded.await() }
        } finally {
          unsubscribeMemberships()
        }
        assertNotNull(graph.replaceFamily("fam-1"))
        val signedOut = CompletableDeferred<Unit>()
        val unsubscribe = graph.store.subscribe {
          if (graph.store.state.session.session == null) signedOut.complete(Unit)
        }
        graph.resume()
        try {
          withTimeout(2_000) { signedOut.await() }
        } finally {
          unsubscribe()
        }

        assertNull(graph.store.state.session.session)
        assertEquals(Route.SignIn, graph.store.state.navigation.route)
        assertNull(tokens.session)
        assertEquals(1, tokens.clears.get())
        assertTrue(content.activeCards().isEmpty())
        assertNull(graph.sessionCoordinator.authSnapshot())
      } finally {
        graph.cancel()
        graph.awaitClosed()
      }
      assertEquals(1, closes.get())
    }

  @Test fun `family owned audience rejection completes root owned terminal cleanup`() =
    runBlocking<Unit> {
      val session = Session("expired-access", "expired-refresh", "user-1")
      val tokens = MemoryTokenStore(session)
      val closes = AtomicInteger()
      val requests = Collections.synchronizedList(mutableListOf<String>())
      val content = freshContentStore().also {
        it.applyDelta(
          changedCards = listOf(Card("private", title = "Private")),
          changedHubs = listOf(Hub("hub-1", title = "Private hub")),
          tombstones = emptyList(),
          nextCursor = "old",
          nowIso = "2026-07-14T12:00:00Z",
        )
      }
      val graph = factory(
        contentStore = content,
        tokenStore = tokens,
        initialState = AppState(session = SessionState(session = session), navigation = NavigationState(route = Route.Loading)),
        httpClientFactory = {
          HttpClient(MockEngine { request ->
            requests += request.url.encodedPath
            when (request.url.encodedPath) {
              "/auth/whoami" -> respond(
                """{"families":[{"family_id":"fam-1","name":"Family","role":"owner","status":"active"}]}""",
                HttpStatusCode.OK,
                jsonHeaders,
              )
              "/families/fam-1/hubs/hub-1/audience" -> respond("", HttpStatusCode.Unauthorized)
              "/auth/refresh" -> respond("", HttpStatusCode.Unauthorized)
              else -> respond("", HttpStatusCode.NotFound)
            }
          })
        },
        onResourcesClosed = { closes.incrementAndGet() },
      ).create()

      try {
        val membershipsLoaded = CompletableDeferred<Unit>()
        val unsubscribeMemberships = graph.store.subscribe {
          if (graph.store.state.session.activeFamilyId == "fam-1") membershipsLoaded.complete(Unit)
        }
        graph.start()
        try {
          withTimeout(2_000) { membershipsLoaded.await() }
        } finally {
          unsubscribeMemberships()
        }
        assertNotNull(graph.replaceFamily("fam-1"))
        graph.hubEngine.openHub("hub-1")
        graph.store.dispatch(OpenAudienceSheet)
        val signedOut = CompletableDeferred<Unit>()
        val unsubscribe = graph.store.subscribe {
          if (graph.store.state.session.session == null) signedOut.complete(Unit)
        }
        withTimeout(2_000) { graph.hubEngine.loadAudience("hub-1") }
        assertTrue("/families/fam-1/hubs/hub-1/audience" in requests, requests.toString())
        assertTrue("/auth/refresh" in requests, requests.toString())
        try {
          withTimeout(2_000) { signedOut.await() }
        } finally {
          unsubscribe()
        }

        assertNull(graph.store.state.session.session)
        assertEquals(Route.SignIn, graph.store.state.navigation.route)
        assertNull(tokens.session)
        assertEquals(1, tokens.clears.get())
        assertTrue(content.activeCards().isEmpty())
        assertNull(graph.sessionCoordinator.authSnapshot())
      } finally {
        graph.cancel()
        graph.awaitClosed()
      }
      assertEquals(1, closes.get())
    }

  @Test fun `cancel invalidates a blocked host provider result before it can install`() =
    runBlocking<Unit> {
      val tokens = MemoryTokenStore()
      val providerEntered = CompletableDeferred<Unit>()
      val releaseProvider = CompletableDeferred<Unit>()
      val graph = factory(
        contentStore = freshContentStore(),
        tokenStore = tokens,
        initialState = AppState(navigation = NavigationState(route = Route.SignIn)),
        httpClientFactory = {
          HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
              "/auth/firebase" -> respond(
                """{"access":"late-a","refresh":"late-r","user_id":"late-user"}""",
                HttpStatusCode.OK,
                jsonHeaders,
              )
              else -> respond("", HttpStatusCode.NotFound)
            }
          })
        },
      ).create()

      graph.start()
      val signIn = async {
        graph.signIn("google", FirebaseSignIn {
          providerEntered.complete(Unit)
          releaseProvider.await()
          "late-provider-token"
        })
      }
      providerEntered.await()
      graph.cancel()
      releaseProvider.complete(Unit)
      signIn.await()
      graph.awaitClosed()

      assertNull(tokens.session)
      assertNull(graph.store.state.session.session)
      assertNull(graph.sessionCoordinator.authSnapshot())
    }

  private fun factory(
    contentStore: ContentStore,
    tokenStore: TokenStore,
    initialState: AppState,
    httpClientFactory: () -> HttpClient,
    onResourcesClosed: () -> Unit = {},
  ): DayfoldRuntimeFactory = DayfoldRuntimeFactory(
    api = "https://api.test",
    contentStore = contentStore,
    tokenStore = tokenStore,
    notificationContext = NotificationContext.Inline,
    httpClientFactory = httpClientFactory,
    backgroundDispatcher = Dispatchers.Default,
    databaseDispatcher = Dispatchers.Default,
    nowProvider = { "2026-07-14T12:00:00Z" },
    idProvider = { "fixed-op" },
    initialState = initialState,
    debug = false,
    pollIntervalMs = Long.MAX_VALUE,
    onResourcesClosed = onResourcesClosed,
  )

  private fun freshContentStore(): ContentStore =
    ContentStore.create(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))

  private fun readyState(session: Session): AppState = AppState(
    session = SessionState(
      session = session,
      families = listOf(FamilyMembership("fam-1", "Family", "owner", "active")),
      activeFamilyId = "fam-1",
    ),
    navigation = NavigationState(route = Route.Feed),
  )
}
