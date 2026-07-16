package com.sloopworks.dayfold.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HubEngineTest {
  private val jsonCt = headersOf(HttpHeaders.ContentType, "application/json")
  private class MemTokenStore(var session: Session? = null) : TokenStore {
    override fun load() = session
    override fun save(session: Session) { this.session = session }
    override fun clear() { session = null }
  }
  // store with an active family + session, so the engine isn't idle.
  private fun readyStore() = createTestAppStore(
    AppState(session = SessionState(session = Session("ax", "rx"), activeFamilyId = "fam1"), navigation = NavigationState(route = Route.Hubs)), debug = false,
  )

  private fun freshContentStore() = ContentStore.create(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))

  private fun engine(
    store: org.reduxkotlin.Store<AppState>,
    handler: MockEngine,
    ts: MemTokenStore = MemTokenStore(Session("ax", "rx")),
    contentStore: ContentStore = freshContentStore(),
    syncEngine: SyncEngine? = null,
    databaseDispatcher: CoroutineDispatcher = Dispatchers.Default,
    nowProvider: () -> String = { "2026-06-29T00:01:00Z" },
    idProvider: () -> String = { Ulid.next() },
    hubTreeFlowProvider: ((String) -> Flow<HubTree?>)? = null,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    sessionCoordinator: SessionCoordinator? = null,
    onSessionExpired: suspend (FamilySessionContext) -> Unit = {},
  ): HubEngine {
    val cs = contentStore
    val sc = SyncClient("https://api.test", HttpClient(handler))
    val se = syncEngine ?: SyncEngine(store, cs, sc, nowProvider = { "2026-06-24T00:00:00Z" })
    return HubEngine(
      store = store,
      hubClient = HubClient("https://api.test", HttpClient(handler)),
      authClient = AuthClient("https://api.test", HttpClient(handler)),
      tokenStore = ts,
      contentStore = cs,
      syncEngine = se,
      hubTreeFlowProvider = hubTreeFlowProvider ?: { hubId -> cs.hubTreeFlow(hubId) },
      scope = scope,
      nowProvider = nowProvider,
      idProvider = idProvider,
      databaseDispatcher = databaseDispatcher,
      suppliedSessionCoordinator = sessionCoordinator,
      onSessionExpired = onSessionExpired,
    )
  }

  private fun coordinator(
    store: org.reduxkotlin.Store<AppState>,
    refreshScope: CoroutineScope,
  ): Pair<SessionCoordinator, FamilySessionContext> {
    val coordinator = SessionCoordinator(
      refreshScope = refreshScope,
      refreshSession = { error("refresh not expected") },
      commitRotation = { session -> store.dispatch(SessionRotated(session)) },
    )
    val auth = coordinator.install(requireNotNull(store.state.session.session))
    return coordinator to requireNotNull(coordinator.selectFamily(auth, requireNotNull(store.state.session.activeFamilyId)))
  }

  // poll the store until predicate or timeout
  private fun await(store: org.reduxkotlin.Store<AppState>, pred: (AppState) -> Boolean) {
    val deadline = System.currentTimeMillis() + 3000
    while (System.currentTimeMillis() < deadline) { if (pred(store.state)) return; Thread.sleep(20) }
    throw AssertionError("timed out; state=${store.state}")
  }

  private suspend fun openAudience(
    engine: HubEngine,
    store: org.reduxkotlin.Store<AppState>,
    hubId: String = "h1",
  ) {
    engine.openHub(hubId)
    store.dispatch(OpenAudienceSheet)
  }

  // PR1: loadHubs is a no-op — the hub list is now DB-fed via the SyncEngine hub bridge.
  // The bridge (HubsLoaded from activeHubsFlow) is the sole writer of state.hubs.
  @Test fun `loadHubs is a no-op (hub list is DB-fed via the bridge)`() = runBlocking {
    val store = readyStore()
    var hit = false
    val e = engine(store, MockEngine { hit = true; respond("[]", HttpStatusCode.OK, jsonCt) })
    e.loadHubs()
    assertEquals(false, hit)               // no network call — bridge owns the list
    assertTrue(store.state.hubs.isEmpty()) // unchanged; bridge not started in this test
  }

  // PR2: openHub is now DB-fed. It dispatches OpenHub, triggers a sync, and subscribes
  // to contentStore.hubTreeFlow(hubId) → dispatches HubTreeLoaded when rows arrive.
  @Test fun `openHub dispatches OpenHub then HubTreeLoaded from DB`() = runBlocking {
    val store = readyStore()
    val cs = freshContentStore()
    // Pre-seed the DB with h1 tree
    cs.applyDelta(
      changedCards = emptyList(),
      changedHubs = listOf(Hub("h1", title = "Party", visibility = "family")),
      changedSections = listOf(HubSection("s1", hubId = "h1", title = "Details")),
      changedBlocks = listOf(HubBlock("b1", sectionId = "s1", type = "text", bodyMd = "hi")),
      tombstones = emptyList(), nextCursor = "c1", nowIso = "2026-06-24T00:00:00Z",
    )
    val e = engine(store, MockEngine { respond("{}", HttpStatusCode.OK, jsonCt) }, contentStore = cs)
    e.openHub("h1")
    // openHub dispatches OpenHub immediately
    assertEquals("h1", store.state.currentHubId)
    // The treeJob coroutine should dispatch HubTreeLoaded shortly
    await(store) { it.currentHubTree?.hub?.title == "Party" }
    assertEquals(listOf("s1"), store.state.currentHubTree?.sections?.map { it.id })
    assertEquals(listOf("b1"), store.state.currentHubTree?.blocks?.map { it.id })
  }

  // PR2: when the hub is absent from DB (was never synced or was tombstoned), the
  // flow emits null. HubNotFound is no longer dispatched (the network call is gone);
  // hubsBusy stays true until a sync delivers the tree.
  @Test fun `openHub with hub absent from DB stays busy (no HubNotFound)`() = runBlocking {
    val store = readyStore()
    val cs = freshContentStore()
    val e = engine(store, MockEngine { respond("{}", HttpStatusCode.OK, jsonCt) }, contentStore = cs)
    e.openHub("hX")
    assertEquals("hX", store.state.currentHubId)
    assertTrue(store.state.hubsBusy)  // still busy — no hub in DB yet
    assertNull(store.state.currentHubTree)
    assertNull(store.state.hubError)  // no error dispatched
  }

  @Test fun `closeHub clears the substate AND cancels the tree subscription`() = runBlocking {
    val store = readyStore()
    val cs = freshContentStore()
    cs.applyDelta(
      changedCards = emptyList(), changedHubs = listOf(Hub("h1", title = "Trip", visibility = "family")),
      changedSections = listOf(HubSection("s1", hubId = "h1", title = "X")),
      changedBlocks = listOf(HubBlock("b1", sectionId = "s1", type = "text", bodyMd = "v1")),
      tombstones = emptyList(), nextCursor = "c1", nowIso = "2026-06-24T00:00:00Z",
    )
    val e = engine(store, MockEngine { respond("{}", HttpStatusCode.OK, jsonCt) }, contentStore = cs)
    e.openHub("h1")
    await(store) { it.currentHubTree?.hub?.title == "Trip" }

    e.closeHub()
    // Low-level cleanup no longer owns navigation; the command/UI layer dispatches once.
    assertEquals("h1", store.state.currentHubId)
    store.dispatch(CloseHub)
    assertNull(store.state.currentHubId)
    assertNull(store.state.currentHubTree)
    assertNull(store.state.hubFocusBlockId)

    // the tree subscription must be cancelled — a later DB write to h1 must NOT
    // re-dispatch HubTreeLoaded (else the coroutine leaks per hub open).
    cs.applyDelta(
      changedCards = emptyList(), changedHubs = listOf(Hub("h1", title = "Trip", visibility = "family")),
      changedSections = listOf(HubSection("s1", hubId = "h1", title = "X")),
      changedBlocks = listOf(HubBlock("b1", sectionId = "s1", type = "text", bodyMd = "v2")),
      tombstones = emptyList(), nextCursor = "c2", nowIso = "2026-06-24T00:01:00Z",
    )
    Thread.sleep(250)
    assertNull(store.state.currentHubTree)   // cancelled → no stray re-render
  }

  // Navigating hub1 -> hub2 must cancel hub1's tree subscription (openHub line:
  // `treeJob?.cancel()`), else a later DB write to hub1 would stray-dispatch
  // HubTreeLoaded(hub1) over the hub2 detail the user is now viewing (+ leak a
  // coroutine per navigation). closeHub's cancellation is tested; re-open is not.
  @Test fun `opening a different hub cancels the prior hub's tree subscription`() = runBlocking {
    val store = readyStore()
    val cs = freshContentStore()
    cs.applyDelta(
      changedCards = emptyList(),
      changedHubs = listOf(Hub("h1", title = "H1", visibility = "family"), Hub("h2", title = "H2", visibility = "family")),
      changedSections = listOf(HubSection("s1", hubId = "h1", title = "X"), HubSection("s2", hubId = "h2", title = "Y")),
      changedBlocks = listOf(HubBlock("b1", sectionId = "s1", type = "text", bodyMd = "h1v1"), HubBlock("b2", sectionId = "s2", type = "text", bodyMd = "h2v1")),
      tombstones = emptyList(), nextCursor = "c1", nowIso = "2026-06-24T00:00:00Z",
    )
    val e = engine(store, MockEngine { respond("{}", HttpStatusCode.OK, jsonCt) }, contentStore = cs)
    e.openHub("h1")
    await(store) { it.currentHubTree?.hub?.title == "H1" }
    e.openHub("h2")
    await(store) { it.currentHubTree?.hub?.title == "H2" }
    assertEquals("h2", store.state.currentHubId)

    // a later write to h1 must NOT pull the detail back to h1 — its subscription
    // was cancelled when we opened h2.
    cs.applyDelta(
      changedCards = emptyList(), changedHubs = listOf(Hub("h1", title = "H1", visibility = "family")),
      changedSections = listOf(HubSection("s1", hubId = "h1", title = "X")),
      changedBlocks = listOf(HubBlock("b1", sectionId = "s1", type = "text", bodyMd = "h1v2")),
      tombstones = emptyList(), nextCursor = "c2", nowIso = "2026-06-24T00:01:00Z",
    )
    Thread.sleep(250)
    assertEquals("H2", store.state.currentHubTree?.hub?.title)   // stays on h2; no stray h1 re-dispatch
  }

  @Test fun `cancelled hub A collector emitting after hub B opens cannot replace hub B`() = runBlocking {
    val store = readyStore()
    val cancelled = CompletableDeferred<Unit>()
    val releaseLateEmission = CompletableDeferred<Unit>()
    val h1Initial = HubTree(Hub("h1", title = "H1"))
    val h1Late = HubTree(Hub("h1", title = "H1 late"))
    val h2 = HubTree(Hub("h2", title = "H2"))
    val provider: (String) -> Flow<HubTree?> = { hubId ->
      if (hubId == "h1") {
        flow {
          emit(h1Initial)
          try {
            awaitCancellation()
          } catch (error: CancellationException) {
            cancelled.complete(Unit)
            withContext(NonCancellable) { releaseLateEmission.await() }
            // Deliberately model a broken/non-cooperative source. The reducer key, not
            // well-behaved cancellation, is the final protection against this publication.
            emit(h1Late)
          }
        }
      } else {
        flowOf(h2)
      }
    }
    val e = engine(
      store,
      MockEngine { respond("{}", HttpStatusCode.OK, jsonCt) },
      hubTreeFlowProvider = provider,
    )

    e.openHub("h1")
    await(store) { it.currentHubTree?.hub?.title == "H1" }
    e.openHub("h2")
    cancelled.await()
    await(store) { it.currentHubTree?.hub?.title == "H2" }
    releaseLateEmission.complete(Unit)
    delay(100)

    assertEquals("h2", store.state.currentHubId)
    assertEquals("H2", store.state.currentHubTree?.hub?.title)
  }

  @Test fun `family replacement cancels and joins blocked tree before admitting new family`() =
    runBlocking<Unit> {
      val store = readyStore()
      val fallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      val (coordinator, familyA) = coordinator(store, fallbackScope)
      val oldStarted = CompletableDeferred<Unit>()
      val releaseOld = CompletableDeferred<Unit>()
      val oldFinished = CompletableDeferred<Unit>()
      val provider: (String) -> Flow<HubTree?> = { hubId ->
        if (hubId == "h1") {
          flow {
            oldStarted.complete(Unit)
            try {
              awaitCancellation()
            } finally {
              withContext(NonCancellable) { releaseOld.await() }
              oldFinished.complete(Unit)
            }
          }
        } else {
          flowOf(HubTree(Hub(hubId, title = "New family")))
        }
      }
      val engine = engine(
        store = store,
        handler = MockEngine { respond("", HttpStatusCode.NotFound) },
        sessionCoordinator = coordinator,
        scope = fallbackScope,
        hubTreeFlowProvider = provider,
      )
      val familyAJob = SupervisorJob()
      val familyAScope = CoroutineScope(familyAJob + Dispatchers.Default)
      val familyAPublication = PublicationBoundary()
      engine.bindFamilyWork(familyA, familyAScope, familyAPublication)
      engine.openHub("h1")
      oldStarted.await()

      familyAPublication.close()
      engine.closeFamilyAdmission()
      coordinator.selectFamily(familyA.authContext, null)
      familyAJob.cancel()
      val oldJoined = CompletableDeferred<Unit>()
      val join = launch { familyAJob.join(); oldJoined.complete(Unit) }
      assertFalse(oldJoined.isCompleted, "replacement must join blocked tree work before wipe")

      releaseOld.complete(Unit)
      join.join()
      assertTrue(oldFinished.isCompleted)

      val familyB = requireNotNull(coordinator.selectFamily(familyA.authContext, "fam2"))
      store.dispatch(MembershipsLoaded(listOf(FamilyMembership("fam2", "B", "owner", "active"))))
      val familyBJob = SupervisorJob()
      engine.bindFamilyWork(
        familyB,
        CoroutineScope(familyBJob + Dispatchers.Default),
        PublicationBoundary(),
      )
      engine.openHub("h2")
      await(store) { it.currentHubTree?.hub?.title == "New family" }
      assertEquals("h2", store.state.currentHubId)
      familyBJob.cancel()
      familyBJob.join()
    }

  @Test fun `openHub with a focus block sets the deep-link arrival highlight`() = runBlocking {
    val store = readyStore()
    val cs = freshContentStore()
    cs.applyDelta(
      changedCards = emptyList(), changedHubs = listOf(Hub("h1", title = "Party", visibility = "family")),
      changedSections = listOf(HubSection("s1", hubId = "h1", title = "X")),
      changedBlocks = listOf(HubBlock("b1", sectionId = "s1", type = "text", bodyMd = "hi")),
      tombstones = emptyList(), nextCursor = "c1", nowIso = "2026-06-24T00:00:00Z",
    )
    val e = engine(store, MockEngine { respond("{}", HttpStatusCode.OK, jsonCt) }, contentStore = cs)
    e.openHub("h1", focusBlockId = "b1")
    await(store) { it.currentHubTree != null }
    assertEquals("b1", store.state.hubFocusBlockId)  // SetHubFocus dispatched + survived HubTreeLoaded
  }

  @Test fun `delayed close for hub A request cannot cancel reopened hub B request`() = runBlocking {
    val store = readyStore()
    // ContentStore.hubTreeFlow is a SQLDelight query flow: a collector receives current DB state
    // even when scheduling starts after the write. A replay=0 SharedFlow would instead drop B when
    // emit wins the collector-start race, obscuring the request-correlation behavior under test.
    val a = MutableStateFlow<HubTree?>(null)
    val b = MutableStateFlow<HubTree?>(null)
    val audienceStarted = CompletableDeferred<Unit>()
    val releaseAudience = CompletableDeferred<Unit>()
    val e = engine(
      store = store,
      handler = MockEngine { request ->
        if (request.url.encodedPath.endsWith("/audience")) {
          audienceStarted.complete(Unit)
          withContext(NonCancellable) { releaseAudience.await() }
          respond("""{"visibility":"family","members":[]}""", HttpStatusCode.OK, jsonCt)
        } else {
          respond("{}", HttpStatusCode.OK, jsonCt)
        }
      },
      hubTreeFlowProvider = { if (it == "hub-a") a else b },
    )

    e.openHub("hub-a")
    val requestA = requireNotNull(store.state.currentHubRequest)
    e.openHub("hub-b")
    val requestB = requireNotNull(store.state.currentHubRequest)
    store.dispatch(OpenAudienceSheet)
    val audienceLoadB = launch { e.loadAudience("hub-b") }
    audienceStarted.await()

    e.closeHub(expectedHubId = "hub-a", expectedRequest = requestA)
    assertTrue(audienceLoadB.isActive, "stale A cleanup must not cancel B audience work")
    b.value = HubTree(Hub("hub-b", title = "B"))
    await(store) { it.currentHubTree?.hub?.id == "hub-b" }
    releaseAudience.complete(Unit)
    audienceLoadB.join()
    await(store) { it.currentHubAudience?.visibility == "family" }

    assertEquals("hub-b", store.state.currentHubId)
    assertEquals(requestB, store.state.currentHubRequest)
  }

  @Test fun `idle with no family or session is a no-op`() = runBlocking {
    val store = createTestAppStore(debug = false)            // no session/family
    var hit = false
    val e = engine(store, MockEngine { hit = true; respond("[]", HttpStatusCode.OK, jsonCt) })
    e.loadHubs()
    assertEquals(false, hit)
    assertTrue(store.state.hubs.isEmpty())
  }

  @Test fun `open aborts navigation focus and collector after runtime admission closes`() =
    runBlocking<Unit> {
      val store = readyStore()
      val fallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      val (coordinator, family) = coordinator(store, fallbackScope)
      var collectors = 0
      val engine = engine(
        store = store,
        handler = MockEngine { respond("", HttpStatusCode.NotFound) },
        sessionCoordinator = coordinator,
        scope = fallbackScope,
        hubTreeFlowProvider = { collectors++; flowOf(HubTree(Hub("h1", title = "H1"))) },
      )
      val familyJob = SupervisorJob()
      val publication = PublicationBoundary()
      engine.bindFamilyWork(family, CoroutineScope(familyJob + Dispatchers.Default), publication)
      publication.close()
      engine.closeFamilyAdmission()

      engine.openHub("h1", focusBlockId = "b1")

      assertNull(store.state.currentHubId)
      assertNull(store.state.hubFocusBlockId)
      assertEquals(0, collectors)
      familyJob.cancel()
      familyJob.join()
    }

  // loadAudience: happy path — GET /audience → HubAudienceLoaded into currentHubAudience.
  @Test fun `loadAudience dispatches HubAudienceLoaded on success`() = runBlocking<Unit> {
    val store = readyStore()
    var calls = 0
    val e = engine(store, MockEngine { req ->
      when (req.url.encodedPath) {
        "/families/fam1/hubs/h1/audience" -> {
          calls++
          respond(
            """{"visibility":"restricted","members":[{"uid":"u1","display_name":"Alex","role":"adult","permitted":true}]}""",
            HttpStatusCode.OK, jsonCt,
          )
        }
        else -> respond("", HttpStatusCode.NotFound)
      }
    })
    openAudience(e, store)
    e.loadAudience("h1")
    assertEquals(1, calls)
    assertEquals("restricted", store.state.currentHubAudience?.visibility)
    assertEquals(listOf("u1"), store.state.currentHubAudience?.members?.map { it.uid })
    assertEquals(true, store.state.currentHubAudience?.members?.single()?.permitted)
  }

  // loadAudience mirrors AuthEngine's 401 refresh-and-retry: a 401 on the audience
  // fetch rotates the token (persist + SessionRotated) and retries once transparently.
  @Test fun `loadAudience 401 refreshes once and retries`() = runBlocking<Unit> {
    val store = readyStore()                               // session = Session("ax","rx")
    val ts = MemTokenStore(Session("ax", "rx"))
    var calls = 0
    val e = engine(store, MockEngine { req ->
      when (req.url.encodedPath) {
        "/families/fam1/hubs/h1/audience" -> {
          calls++
          if (calls == 1) respond("expired", HttpStatusCode.Unauthorized)
          else respond("""{"visibility":"family","members":[]}""", HttpStatusCode.OK, jsonCt)
        }
        "/auth/refresh" -> respond("""{"access":"fresh","refresh":"r2"}""", HttpStatusCode.OK, jsonCt)
        else -> respond("", HttpStatusCode.NotFound)
      }
    }, ts = ts)
    openAudience(e, store)
    e.loadAudience("h1")
    assertEquals(2, calls)                                 // retried after refresh
    assertEquals(Session("fresh", "r2"), store.state.session.session)  // rotated into state
    assertEquals(Session("fresh", "r2"), ts.session)       // and persisted
    assertEquals("family", store.state.currentHubAudience?.visibility)
  }

  @Test fun `failed refresh 401 from stale family does not expire replacement family`() =
    runBlocking<Unit> {
      val store = readyStore()
      val requestRefresh = CompletableDeferred<Unit>()
      val releaseRefresh = CompletableDeferred<Unit>()
      val handler = MockEngine { request ->
        when {
          request.url.encodedPath.endsWith("/audience") ->
            respond("expired", HttpStatusCode.Unauthorized)
          request.url.encodedPath == "/auth/refresh" -> {
            requestRefresh.complete(Unit)
            releaseRefresh.await()
            respond("rejected", HttpStatusCode.Unauthorized)
          }
          else -> respond("", HttpStatusCode.NotFound)
        }
      }
      val http = HttpClient(handler)
      val fallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      val coordinator = SessionCoordinator(
        refreshScope = fallbackScope,
        refreshSession = { context -> context.refreshWith(AuthClient("https://api.test", http)::refresh) },
        commitRotation = {},
      )
      val auth = coordinator.install(requireNotNull(store.state.session.session))
      coordinator.selectFamily(auth, "fam1")
      var expirations = 0
      val content = freshContentStore()
      val sync = SyncEngine(
        store,
        content,
        SyncClient("https://api.test", http),
        suppliedSessionCoordinator = coordinator,
      )
      val engine = HubEngine(
        store = store,
        hubClient = HubClient("https://api.test", http),
        authClient = AuthClient("https://api.test", http),
        tokenStore = MemTokenStore(store.state.session.session),
        contentStore = content,
        syncEngine = sync,
        scope = fallbackScope,
        suppliedSessionCoordinator = coordinator,
        onSessionExpired = { expirations++ },
      )
      engine.openHub("h1")
      store.dispatch(OpenAudienceSheet)
      val load = launch { engine.loadAudience("h1") }
      requestRefresh.await()
      coordinator.selectFamily(auth, "fam2")
      releaseRefresh.complete(Unit)
      load.join()

      assertEquals(0, expirations)
      assertEquals("fam1", store.state.session.activeFamilyId)
      assertEquals(Session("ax", "rx"), store.state.session.session)
      fallbackScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

  // A failed refresh after a 401 is non-fatal: loadAudience swallows it (the sheet
  // shows a quiet empty/loading state) — no crash, no stale audience, no rotation.
  @Test fun `loadAudience swallows a failed refresh (quiet, non-fatal)`() = runBlocking<Unit> {
    val store = readyStore()
    val ts = MemTokenStore(Session("ax", "rx"))
    var calls = 0
    val e = engine(store, MockEngine { req ->
      when (req.url.encodedPath) {
        "/families/fam1/hubs/h1/audience" -> { calls++; respond("expired", HttpStatusCode.Unauthorized) }
        "/auth/refresh" -> respond("nope", HttpStatusCode.Unauthorized)   // refresh also fails
        else -> respond("", HttpStatusCode.NotFound)
      }
    }, ts = ts)
    openAudience(e, store)
    e.loadAudience("h1")                                   // must not throw
    assertEquals(1, calls)                                 // no successful retry
    assertNull(store.state.currentHubAudience)             // nothing dispatched
    assertEquals(Session("ax", "rx"), store.state.session.session) // no rotation on failed refresh
  }

  @Test fun `loadAudience with no session is a no-op`() = runBlocking<Unit> {
    val store = createTestAppStore(debug = false)              // no session/family
    var hit = false
    val e = engine(store, MockEngine { hit = true; respond("", HttpStatusCode.NotFound) })
    e.loadAudience("h1")
    assertEquals(false, hit)                               // guarded before any network call
    assertNull(store.state.currentHubAudience)
  }

  @Test fun `close does not wait behind blocked audience load and late success is rejected`() = runBlocking<Unit> {
    val store = readyStore()
    val requestStarted = CompletableDeferred<Unit>()
    val releaseRequest = CompletableDeferred<Unit>()
    val e = engine(store, MockEngine { request ->
      if (request.url.encodedPath.endsWith("/audience")) {
        requestStarted.complete(Unit)
        withContext(NonCancellable) { releaseRequest.await() }
        respond("""{"visibility":"restricted","members":[]}""", HttpStatusCode.OK, jsonCt)
      } else {
        respond("", HttpStatusCode.NotFound)
      }
    })
    openAudience(e, store)
    val loading = launch { e.loadAudience("h1") }
    requestStarted.await()

    withTimeout(500) { e.closeHub() }
    store.dispatch(CloseHub)
    assertNull(store.state.currentHubId)

    releaseRequest.complete(Unit)
    loading.join()
    delay(100)
    assertNull(store.state.currentHubAudience)
    assertNull(store.state.audienceError)
  }

  @Test fun `legacy family cancellation joins work and reopens admission`() = runBlocking<Unit> {
    val store = readyStore()
    val oldStarted = CompletableDeferred<Unit>()
    val cancellationObserved = CompletableDeferred<Unit>()
    val releaseOld = CompletableDeferred<Unit>()
    val oldFinished = CompletableDeferred<Unit>()
    val engine = engine(
      store = store,
      handler = MockEngine { respond("", HttpStatusCode.NotFound) },
      hubTreeFlowProvider = { hubId ->
        if (hubId == "h1") {
          flow {
            oldStarted.complete(Unit)
            try {
              awaitCancellation()
            } finally {
              cancellationObserved.complete(Unit)
              withContext(NonCancellable) { releaseOld.await() }
              oldFinished.complete(Unit)
            }
          }
        } else {
          flowOf(HubTree(Hub(hubId, title = "Replacement")))
        }
      },
    )
    engine.openHub("h1")
    oldStarted.await()

    val cancellation = launch { engine.cancelFamilyWork() }
    cancellationObserved.await()
    assertFalse(cancellation.isCompleted, "cleanup must join cancellation-resistant Hub work")

    releaseOld.complete(Unit)
    cancellation.join()
    assertTrue(oldFinished.isCompleted)

    engine.openHub("h2")
    await(store) { it.currentHubTree?.hub?.title == "Replacement" }
    assertEquals("h2", store.state.currentHubId)
  }

  @Test fun `terminal family close joins blocked audience load before cleanup`() = runBlocking<Unit> {
    val store = readyStore()
    val fallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val (coordinator, family) = coordinator(store, fallbackScope)
    val requestStarted = CompletableDeferred<Unit>()
    val releaseRequest = CompletableDeferred<Unit>()
    val requestFinished = CompletableDeferred<Unit>()
    val engine = engine(
      store = store,
      handler = MockEngine { request ->
        if (request.url.encodedPath.endsWith("/audience")) {
          requestStarted.complete(Unit)
          try {
            withContext(NonCancellable) { releaseRequest.await() }
            respond("""{"visibility":"family","members":[]}""", HttpStatusCode.OK, jsonCt)
          } finally {
            requestFinished.complete(Unit)
          }
        } else {
          respond("", HttpStatusCode.NotFound)
        }
      },
      sessionCoordinator = coordinator,
      scope = fallbackScope,
      hubTreeFlowProvider = { flowOf(null) },
    )
    val familyJob = SupervisorJob()
    val publication = PublicationBoundary()
    engine.bindFamilyWork(family, CoroutineScope(familyJob + Dispatchers.Default), publication)
    openAudience(engine, store)
    val loading = launch { engine.loadAudience("h1") }
    requestStarted.await()

    publication.close()
    engine.closeFamilyAdmission()
    familyJob.cancel()
    val joined = CompletableDeferred<Unit>()
    val join = launch { familyJob.join(); joined.complete(Unit) }
    assertFalse(joined.isCompleted, "terminal cleanup must wait for audience cancellation")

    releaseRequest.complete(Unit)
    join.join()
    loading.join()
    // MockEngine may finish its transport-owned handler one scheduler turn after the cancelled
    // caller job. The pre-release assertion above is the family-child join guarantee; await the
    // transport fixture only so it cannot leak into the next test.
    requestFinished.await()
    assertNull(store.state.currentHubAudience)
  }

  @Test fun `throwing external acknowledgement cannot undo admitted Hub navigation`() = runBlocking<Unit> {
    val store = readyStore()
    val fallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val (coordinator, family) = coordinator(store, fallbackScope)
    val engine = engine(
      store = store,
      handler = MockEngine { respond("", HttpStatusCode.NotFound) },
      sessionCoordinator = coordinator,
      scope = fallbackScope,
      hubTreeFlowProvider = { flowOf(null) },
    )
    val familyJob = SupervisorJob()
    val publication = PublicationBoundary()
    engine.bindFamilyWork(family, CoroutineScope(familyJob + Dispatchers.Default), publication)

    try {
      val admitted = engine.openHub(
        context = family,
        hubId = "h1",
        onAdmitted = { error("broken platform acknowledgement") },
      )

      assertTrue(admitted)
      assertEquals("h1", store.state.currentHubId)
    } finally {
      publication.close()
      engine.closeFamilyAdmission()
      familyJob.cancel()
      fallbackScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
  }

  @Test fun `family replacement joins blocked audience mutation before wipe`() = runBlocking<Unit> {
    val store = readyStore()
    val fallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val (coordinator, family) = coordinator(store, fallbackScope)
    val mutationStarted = CompletableDeferred<Unit>()
    val releaseMutation = CompletableDeferred<Unit>()
    val mutationFinished = CompletableDeferred<Unit>()
    val engine = engine(
      store = store,
      handler = MockEngine { request ->
        if (request.url.encodedPath.endsWith("/participants/u2")) {
          mutationStarted.complete(Unit)
          try {
            withContext(NonCancellable) { releaseMutation.await() }
            respond("{}", HttpStatusCode.OK, jsonCt)
          } finally {
            mutationFinished.complete(Unit)
          }
        } else {
          respond("""{"visibility":"family","members":[]}""", HttpStatusCode.OK, jsonCt)
        }
      },
      sessionCoordinator = coordinator,
      scope = fallbackScope,
      hubTreeFlowProvider = { flowOf(null) },
    )
    val familyJob = SupervisorJob()
    val publication = PublicationBoundary()
    engine.bindFamilyWork(family, CoroutineScope(familyJob + Dispatchers.Default), publication)
    openAudience(engine, store)
    val mutation = launch { engine.setParticipant("h1", "u2", "viewer") }
    mutationStarted.await()

    publication.close()
    engine.closeFamilyAdmission()
    familyJob.cancel()
    val joined = CompletableDeferred<Unit>()
    val join = launch { familyJob.join(); joined.complete(Unit) }
    assertFalse(joined.isCompleted, "family wipe must wait for mutation cancellation")

    releaseMutation.complete(Unit)
    join.join()
    mutation.join()
    // As with the blocked load above, MockEngine owns the handler coroutine and may execute its
    // finally one scheduler turn after the cancelled Hub caller completes. The pre-release
    // assertion is the family-child ownership proof; drain the transport fixture before exit.
    mutationFinished.await()
    assertNull(store.state.currentHubAudience)
  }

  // Slice 4 (ADR 0038) — toggleItem runs the optimistic apply + outbox enqueue through
  // ContentStore. A 500 backend means the kicked sync's inbound drain throws before the
  // egress runs, so the op stays pending and we can observe the enqueue deterministically.
  private fun seedChecklist(cs: ContentStore) = cs.applyDelta(
    changedCards = emptyList(),
    changedHubs = listOf(Hub("h1", title = "Party", visibility = "family")),
    changedSections = listOf(HubSection("s1", hubId = "h1", title = "Plan")),
    changedBlocks = listOf(HubBlock(id = "b1", sectionId = "s1", type = "checklist", ord = 0, version = 3,
      payload = BlockPayload(items = listOf(ChecklistItem(id = "i1", text = "Pack", done = false))))),
    tombstones = emptyList(), nextCursor = "c1", nowIso = "2026-06-29T00:00:00Z",
  )

  @Test fun `toggleItem optimistically flips the block to pending and queues one op`() = runBlocking<Unit> {
    val store = readyStore()
    val cs = freshContentStore(); seedChecklist(cs)
    val e = engine(store, MockEngine { respond("err", HttpStatusCode.InternalServerError) }, contentStore = cs)
    e.toggleItem("b1", "i1", done = true)
    assertEquals("pending", cs.blockLocalState("b1"))       // optimistic write flag is on
    assertEquals(1, cs.pendingOpCount())                    // exactly one coalesced egress op
  }

  @Test fun `held content write suspends without blocking caller and captures edge values first`() {
    val uiDispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "hub-ui") }
      .asCoroutineDispatcher()
    val databaseDispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "hub-db") }
      .asCoroutineDispatcher()
    val databaseOccupied = CountDownLatch(1)
    val releaseDatabase = CountDownLatch(1)
    val edgeCaptured = CompletableDeferred<Unit>()
    val databaseScope = CoroutineScope(SupervisorJob() + databaseDispatcher)
    val blocker = databaseScope.launch {
      databaseOccupied.countDown()
      check(releaseDatabase.await(5, TimeUnit.SECONDS)) { "database dispatcher was not released" }
    }
    var clockThread = ""
    var idThread = ""

    try {
      assertTrue(databaseOccupied.await(5, TimeUnit.SECONDS), "database dispatcher was not occupied")
      val store = readyStore()
      val cs = freshContentStore().also(::seedChecklist)
      val hub = engine(
        store = store,
        handler = MockEngine { respond("err", HttpStatusCode.InternalServerError) },
        contentStore = cs,
        databaseDispatcher = databaseDispatcher,
        nowProvider = {
          clockThread = Thread.currentThread().name
          edgeCaptured.complete(Unit)
          "2026-06-29T00:01:00Z"
        },
        idProvider = {
          idThread = Thread.currentThread().name
          "OP-EDGE"
        },
      )

      runBlocking(uiDispatcher) {
        val toggle = launch { hub.toggleItem("b1", "i1", done = true) }
        edgeCaptured.await()
        var pulseThread = ""
        launch { pulseThread = Thread.currentThread().name }.join()

        assertTrue(pulseThread.startsWith("hub-ui"), "pulse ran on $pulseThread")
        assertFalse(toggle.isCompleted, "held content write should suspend, not block, the caller")
        assertEquals(0, cs.pendingOpCount(), "write must remain queued on the occupied DB dispatcher")
        releaseDatabase.countDown()
        toggle.join()
        assertEquals(1, cs.pendingOpCount())
      }
      hub.stop()
      runBlocking { blocker.join() }
    } finally {
      releaseDatabase.countDown()
      uiDispatcher.close()
      databaseDispatcher.close()
    }

    assertTrue(clockThread.startsWith("hub-ui"), "clock was captured on $clockThread")
    assertTrue(idThread.startsWith("hub-ui"), "id was captured on $idThread")
  }

  // Slice 5b (ADR 0038 §W4) — deleteBlock runs the optimistic delete (mark 'pending'/Removing
  // + enqueue a "delete" op) through ContentStore, mirroring toggleItem. A 500 backend keeps the
  // op pending so the enqueue is observable.
  @Test fun `deleteBlock optimistically marks the block pending and queues one delete op`() = runBlocking<Unit> {
    val store = readyStore()
    val cs = freshContentStore(); seedChecklist(cs)
    val e = engine(store, MockEngine { respond("err", HttpStatusCode.InternalServerError) }, contentStore = cs)
    e.deleteBlock("b1")
    assertEquals("pending", cs.blockLocalState("b1"))        // "Removing…" — row stays visible
    val op = cs.nextPendingOp()!!
    assertEquals("delete", op.type)
    assertEquals("b1", op.targetId)
  }

  // ── participant/visibility management (ADR 0053 DC4) ─────────────────────────
  // Each op mutates then RELOADS the audience — a successful setParticipant dispatches
  // a fresh HubAudienceLoaded (the "reload/updated" action), not a bespoke one.

  @Test fun `setParticipant PUTs the role then reloads the audience`() = runBlocking<Unit> {
    val store = readyStore()
    var putBody: String? = null; var putCalls = 0; var audienceCalls = 0
    val e = engine(store, MockEngine { req ->
      when {
        req.url.encodedPath == "/families/fam1/hubs/h1/participants/u2" && req.method.value == "PUT" -> {
          putCalls++
          putBody = (req.body as io.ktor.http.content.TextContent).text
          respond("{}", HttpStatusCode.OK, jsonCt)
        }
        req.url.encodedPath == "/families/fam1/hubs/h1/audience" -> {
          audienceCalls++
          respond(
            """{"visibility":"family","can_manage":true,"members":[{"uid":"u2","display_name":"Jordan","role":"adult","permitted":true,"participation_role":"contributor"}]}""",
            HttpStatusCode.OK, jsonCt,
          )
        }
        else -> respond("", HttpStatusCode.NotFound)
      }
    })
    openAudience(e, store)
    e.setParticipant("h1", "u2", "contributor")
    assertEquals(1, putCalls)
    assertEquals("""{"role":"contributor"}""", putBody)
    assertEquals(1, audienceCalls)                          // reload fired on success
    assertEquals("contributor", store.state.currentHubAudience?.members?.single()?.participationRole)
    assertEquals(true, store.state.currentHubAudience?.canManage)
    assertNull(store.state.audienceError)
  }

  @Test fun `setParticipant on failure dispatches HubManageFailed and skips the reload`() = runBlocking<Unit> {
    val store = readyStore()
    var audienceCalls = 0
    val e = engine(store, MockEngine { req ->
      when (req.url.encodedPath) {
        "/families/fam1/hubs/h1/participants/u2" -> respond("nope", HttpStatusCode.Forbidden)
        "/families/fam1/hubs/h1/audience" -> { audienceCalls++; respond("", HttpStatusCode.NotFound) }
        else -> respond("", HttpStatusCode.NotFound)
      }
    })
    openAudience(e, store)
    e.setParticipant("h1", "u2", "co_owner")
    assertEquals(0, audienceCalls)                          // mutation failed before any reload
    assertNull(store.state.currentHubAudience)
    assertEquals("Couldn't update that person's access. Try again.", store.state.audienceError)
  }

  @Test fun `removeParticipant DELETEs then reloads the audience`() = runBlocking<Unit> {
    val store = readyStore()
    var delCalls = 0; var audienceCalls = 0
    val e = engine(store, MockEngine { req ->
      when {
        req.url.encodedPath == "/families/fam1/hubs/h1/participants/u2" && req.method.value == "DELETE" -> {
          delCalls++; respond("", HttpStatusCode.NoContent)
        }
        req.url.encodedPath == "/families/fam1/hubs/h1/audience" -> {
          audienceCalls++
          respond("""{"visibility":"family","members":[]}""", HttpStatusCode.OK, jsonCt)
        }
        else -> respond("", HttpStatusCode.NotFound)
      }
    })
    openAudience(e, store)
    e.removeParticipant("h1", "u2")
    assertEquals(1, delCalls)
    assertEquals(1, audienceCalls)
    assertTrue(store.state.currentHubAudience?.members?.isEmpty() == true)
  }

  @Test fun `setVisibility PUTs the visibility then reloads the audience`() = runBlocking<Unit> {
    val store = readyStore()
    var putBody: String? = null; var audienceCalls = 0
    val e = engine(store, MockEngine { req ->
      when {
        req.url.encodedPath == "/families/fam1/hubs/h1/visibility" && req.method.value == "PUT" -> {
          putBody = (req.body as io.ktor.http.content.TextContent).text
          respond("{}", HttpStatusCode.OK, jsonCt)
        }
        req.url.encodedPath == "/families/fam1/hubs/h1/audience" -> {
          audienceCalls++
          respond("""{"visibility":"restricted","members":[]}""", HttpStatusCode.OK, jsonCt)
        }
        else -> respond("", HttpStatusCode.NotFound)
      }
    })
    openAudience(e, store)
    e.setVisibility("h1", "restricted")
    assertEquals("""{"visibility":"restricted"}""", putBody)
    assertEquals(1, audienceCalls)
    assertEquals("restricted", store.state.currentHubAudience?.visibility)
  }

  @Test fun `setVisibility with no session is a no-op`() = runBlocking<Unit> {
    val store = createTestAppStore(debug = false)              // no session/family
    var hit = false
    val e = engine(store, MockEngine { hit = true; respond("", HttpStatusCode.NotFound) })
    e.setVisibility("h1", "family")
    assertEquals(false, hit)
    assertNull(store.state.currentHubAudience)
  }

  @Test fun `retryBlock re-arms a block parked failed back to pending`() = runBlocking<Unit> {
    val store = readyStore()
    val cs = freshContentStore(); seedChecklist(cs)
    cs.enqueueBlockToggle("b1", "i1", done = true, doneBy = "mom", nowIso = "2026-06-29T00:01:00Z", opId = "OP1")
    val op = cs.nextPendingOp()!!; cs.markOpInflight(op.opId); cs.failOp(op.opId, "b1")   // simulate cap-reached
    assertEquals("failed", cs.blockLocalState("b1"))
    val e = engine(store, MockEngine { respond("err", HttpStatusCode.InternalServerError) }, contentStore = cs)
    e.retryBlock("b1")
    assertEquals("pending", cs.blockLocalState("b1"))       // flipped back; op re-queued for the next drain
  }

  @Test fun `hide and unhide round trip through the engine write boundary`() = runBlocking {
    val store = readyStore()
    val cs = freshContentStore()
    val e = engine(store, MockEngine { respond("err", HttpStatusCode.InternalServerError) }, contentStore = cs)

    e.hideBlock("b1")
    assertEquals(setOf("b1"), cs.hiddenIdsFlow().first())
    e.unhideBlock("b1")
    assertEquals(emptySet(), cs.hiddenIdsFlow().first())
  }
}
