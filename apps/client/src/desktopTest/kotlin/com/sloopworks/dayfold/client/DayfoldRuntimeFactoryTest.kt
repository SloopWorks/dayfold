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
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.reduxkotlin.concurrent.NotificationContext

class DayfoldRuntimeFactoryTest {
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
        assertEquals(rotated, graph.store.state.session)

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
        initialState = AppState(session = session, route = Route.Loading),
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
          if (graph.store.state.activeFamilyId == "fam-1") membershipsLoaded.complete(Unit)
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
          if (graph.store.state.session == null) signedOut.complete(Unit)
        }
        graph.resume()
        try {
          withTimeout(2_000) { signedOut.await() }
        } finally {
          unsubscribe()
        }

        assertNull(graph.store.state.session)
        assertEquals(Route.SignIn, graph.store.state.route)
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
        initialState = AppState(session = session, route = Route.Loading),
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
          if (graph.store.state.activeFamilyId == "fam-1") membershipsLoaded.complete(Unit)
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
          if (graph.store.state.session == null) signedOut.complete(Unit)
        }
        withTimeout(2_000) { graph.hubEngine.loadAudience("hub-1") }
        assertTrue("/families/fam-1/hubs/hub-1/audience" in requests, requests.toString())
        assertTrue("/auth/refresh" in requests, requests.toString())
        try {
          withTimeout(2_000) { signedOut.await() }
        } finally {
          unsubscribe()
        }

        assertNull(graph.store.state.session)
        assertEquals(Route.SignIn, graph.store.state.route)
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
        initialState = AppState(route = Route.SignIn),
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
      assertNull(graph.store.state.session)
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
    session = session,
    families = listOf(FamilyMembership("fam-1", "Family", "owner", "active")),
    activeFamilyId = "fam-1",
    route = Route.Feed,
  )
}
