package com.sloopworks.dayfold.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DayfoldCommandsTest {
  private class MemoryTokenStore(private var session: Session?) : TokenStore {
    override fun load(): Session? = session
    override fun save(session: Session) { this.session = session }
    override fun clear() { session = null }
  }

  @Test fun `open hubs carries its return destination in one atomic action`() {
    val store = createTestAppStore(
      AppState(route = Route.Feed, detailStack = listOf("card-1")),
      debug = false,
    )

    DayfoldCommands.navigationOnly(store).openHubs(HubReturnDestination.FEED_DETAIL)

    assertEquals(Route.Hubs, store.state.route)
    assertTrue(store.state.hubFromDetail)
  }

  @Test fun `close is expected-hub correlated and cannot clear a replacement hub`() {
    val request = HubRequestKey(HubTenantGeneration(1L, 1L), 1L)
    val store = createTestAppStore(
      AppState(route = Route.Hubs, currentHubId = "hub-a", currentHubRequest = request),
      debug = false,
    )
    val commands = DayfoldCommands.navigationOnly(store)

    commands.closeHub("hub-a", HubReturnDestination.HUB_LIST)
    store.dispatch(OpenHub("hub-b", request.copy(requestId = 2L)))
    commands.closeHub("hub-a", HubReturnDestination.HUB_LIST)

    assertEquals("hub-b", store.state.currentHubId)
    assertEquals(Route.Hubs, store.state.route)
  }

  @Test fun `device approval carries rendered family code and hubs without rereading state`() =
    runBlocking<Unit> {
      val session = Session("access", "refresh", "user")
      val store = createTestAppStore(
        AppState(
          session = session,
          activeFamilyId = "family-a",
          pendingDevice = PendingDevice(userCode = "STATE-CODE"),
        ),
        debug = false,
      )
      val coordinator = SessionCoordinator(
        refreshScope = CoroutineScope(coroutineContext),
        refreshSession = { error("refresh not expected") },
        commitRotation = {},
      )
      coordinator.selectFamily(coordinator.install(session), "family-a")
      val request = CompletableDeferred<HttpRequestData>()
      val http = HttpClient(MockEngine {
        request.complete(it)
        respond("", HttpStatusCode.NoContent, headersOf("content-type", "application/json"))
      })
      val auth = AuthEngine(
        store = store,
        authClient = AuthClient("https://api.test", http),
        tokenStore = MemoryTokenStore(session),
        scope = CoroutineScope(coroutineContext),
        sessionCoordinator = coordinator,
      )
      val commands = DayfoldCommands(
        store = store,
        scope = CoroutineScope(coroutineContext),
        authEngine = auth,
        sessionCoordinator = coordinator,
      )

      commands.approveDevice("family-b", "EDGE-CODE", listOf("hub-2", "hub-1"))
      val sent = request.await()
      val body = (sent.body as TextContent).text

      assertEquals("/families/family-b/device/approve", sent.url.encodedPath)
      assertTrue(body.contains("EDGE-CODE"), body)
      assertFalse(body.contains("STATE-CODE"), body)
      assertTrue(body.contains("hub-2"), body)
      http.close()
    }

  @Test fun `member command captured in old A generation cannot publish after A B A`() =
    runBlocking<Unit> {
      val session = Session("access", "refresh", "user")
      val pending = PendingMember("member-1", "Pat")
      val store = createTestAppStore(
        AppState(
          session = session,
          activeFamilyId = "family-a",
          pendingApprovals = listOf(pending),
        ),
        debug = false,
      )
      val coordinator = SessionCoordinator(
        refreshScope = CoroutineScope(coroutineContext),
        refreshSession = { error("refresh not expected") },
        commitRotation = {},
      )
      val authContext = coordinator.install(session)
      coordinator.selectFamily(authContext, "family-a")
      val requestStarted = CompletableDeferred<Unit>()
      val releaseRequest = CompletableDeferred<Unit>()
      val http = HttpClient(MockEngine {
        requestStarted.complete(Unit)
        releaseRequest.await()
        respond("", HttpStatusCode.NoContent)
      })
      val auth = AuthEngine(
        store = store,
        authClient = AuthClient("https://api.test", http),
        tokenStore = MemoryTokenStore(session),
        scope = CoroutineScope(coroutineContext),
        sessionCoordinator = coordinator,
      )
      val commands = DayfoldCommands(
        store = store,
        scope = CoroutineScope(coroutineContext),
        authEngine = auth,
        sessionCoordinator = coordinator,
      )

      commands.approveMember("family-a", "member-1")
      requestStarted.await()
      coordinator.selectFamily(authContext, "family-b")
      coordinator.selectFamily(authContext, "family-a")
      releaseRequest.complete(Unit)
      coroutineContext[Job]?.children?.toList().orEmpty().joinAll()

      assertEquals(listOf(pending), store.state.pendingApprovals)
      http.close()
    }
}
