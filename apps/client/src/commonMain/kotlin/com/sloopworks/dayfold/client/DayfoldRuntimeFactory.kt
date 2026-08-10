package com.sloopworks.dayfold.client

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import org.reduxkotlin.Store
import org.reduxkotlin.StoreEnhancer
import org.reduxkotlin.concurrent.NotificationContext

/**
 * The host-safe client graph created by [DayfoldRuntimeFactory].
 *
 * Platform hosts retain this graph, call its lifecycle and command methods, and provide native UI
 * seams only for the duration of the relevant command. The graph retains no Activity, View, or
 * UIKit controller.
 */
class DayfoldRuntimeGraph internal constructor(
  private val runtime: DayfoldRuntime,
  val store: Store<AppState>,
  val commands: DayfoldCommands,
  internal val authEngine: AuthEngine,
  internal val syncEngine: SyncEngine,
  internal val syncCoordinator: SyncCoordinator,
  internal val hubEngine: HubEngine,
  internal val nowEngine: NowEngine,
  internal val sessionCoordinator: SessionCoordinator,
  private val externalHubTargets: PendingExternalHubTargetCoordinator,
) {
  /** Starts schema preparation, device projection, and authentication restoration once. */
  suspend fun start() = runtime.start()

  /** Enters the foreground policy, starting sync and polling when a family is active. */
  suspend fun resume() = runtime.resume()

  /** Leaves foreground polling stopped while database projections remain active. */
  suspend fun pause() = runtime.pause()

  /** Immediately closes publication and starts non-blocking structured cancellation. */
  fun cancel() {
    // Close the identity boundary synchronously too. A host-owned provider UI coroutine may not
    // be a runtime child, but its eventual result can no longer install/save/dispatch.
    sessionCoordinator.invalidate()
    runtime.cancel()
  }

  /** Waits until every owned child and the shared HTTP client have closed. */
  suspend fun awaitClosed() = runtime.awaitClosed()

  /**
   * Requests a silent background sync pass — for a headless caller (WorkManager, BGAppRefreshTask)
   * that finds this graph already live in-process and must delegate to it rather than build its own
   * refresher (ADR 0020 R3; see RuntimeHandleHolder). Uses [SyncReason.BACKGROUND], never
   * MANUAL_REFRESH: that reason publishes a user-facing sync status, and a background wake must be
   * silent.
   *
   * Uses [SyncCoordinator.requestSyncOnceAndAwait], not [SyncCoordinator.requestSync]: the runtime
   * is very often live but PAUSED at exactly this moment (app backgrounded, no Activity in the
   * foreground — the common case a background wake exists for), and `requestSync` deliberately does
   * nothing but conflate while paused. `requestSyncOnceAndAwait` runs one pass through the same
   * worker regardless of paused state, without restarting the poll loop or flipping the coordinator
   * back to resumed — AND suspends until that pass has actually finished, which this method's own
   * `suspend` signature promises to its caller (`RefreshDeps.delegateToRuntime`): a headless wake
   * (WorkManager's `doWork`, iOS's `BGAppRefreshTask`) tells the OS "done" right after this returns,
   * so returning as soon as the pass was merely ARMED would report success before the sync it
   * triggered had actually run.
   *
   * Returns whether that pass actually SYNCED — false when it short-circuits (no worker bound yet,
   * this graph is closed) or when the pass itself found no family / failed. The headless wake logs
   * this verbatim, and a log line claiming a sync that never happened is worse than no log line.
   */
  suspend fun requestBackgroundSync(): Boolean =
    syncCoordinator.requestSyncOnceAndAwait(SyncReason.BACKGROUND)

  /** Replaces the active family with close, join, wipe, and restart ordering. */
  suspend fun replaceFamily(familyId: String?): FamilySessionContext? {
    ensureOpen()
    val auth = sessionCoordinator.authSnapshot() ?: return null
    return runtime.replaceFamily(auth, familyId)?.also { externalHubTargets.familyBound(it) }
  }

  /**
   * Performs provider sign-in with a host-scoped native UI seam, then binds the selected family.
   * The seam is used only for this call and is never retained by the runtime graph.
   */
  suspend fun signIn(provider: String, providerSignIn: FirebaseSignIn) {
    ensureOpen()
    start()
    authEngine.signIn(provider, providerSignIn)
    bindSelectedFamily()
  }

  /** Performs debug sign-in and binds its synthetic family to runtime-owned projections. */
  suspend fun devSignIn() {
    ensureOpen()
    start()
    authEngine.devSignIn()
    bindSelectedFamily()
  }

  /** Creates the first family, then starts its runtime-owned projections and sync lifetime. */
  suspend fun createFamily(name: String) {
    ensureOpen()
    start()
    authEngine.createFamily(name)
    bindSelectedFamily()
  }

  private suspend fun bindSelectedFamily() {
    val familyId = store.state.session.activeFamilyId ?: return
    replaceFamily(familyId)
  }

  private fun ensureOpen() {
    check(
      runtime.lifecycleState != DayfoldRuntimeState.CLOSING &&
        runtime.lifecycleState != DayfoldRuntimeState.CLOSED,
    ) { "DayfoldRuntimeGraph is closed" }
  }
}

/**
 * Builds one runtime-owned client graph from platform-neutral seams.
 *
 * The caller supplies the process-safe database and token store because their construction is
 * platform-specific. This factory owns the Redux store, one shared HTTP client and transport set,
 * the shared session coordinator, all feature engines, and their supervised runtime children.
 * [httpClientFactory] is invoked exactly once per [create] call and the resulting client is closed
 * by [DayfoldRuntime.awaitClosed].
 */
class DayfoldRuntimeFactory(
  private val api: String,
  private val contentStore: ContentStore,
  private val tokenStore: TokenStore,
  private val notificationContext: NotificationContext,
  private val httpClientFactory: () -> HttpClient = { HttpClient() },
  private val backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
  private val databaseDispatcher: CoroutineDispatcher = Dispatchers.Default,
  private val nowProvider: () -> String = { kotlin.time.Clock.System.now().toString() },
  private val idProvider: () -> String = { Ulid.next() },
  private val initialState: AppState = AppState(),
  private val debug: Boolean = true,
  private val extraEnhancer: StoreEnhancer<AppState>? = null,
  private val pollIntervalMs: Long = 45_000L,
  private val devSecret: String? = null,
  private val devProvider: String = "dev",
  private val devProviderUid: String = "dev-user",
  private val onResourcesClosed: () -> Unit = {},
  // CAL-8 (ADR 0063) — the device calendar seam. NoOpCalendarPort everywhere except the real
  // Android adapter, mirroring how notificationContext/tokenStore are host-supplied.
  private val calendarPort: CalendarPort = NoOpCalendarPort,
) {
  /** Creates an independent graph whose complete coroutine tree is owned by its runtime. */
  fun create(): DayfoldRuntimeGraph {
    val store = createAppStore(
      notificationContext = notificationContext,
      initial = initialState,
      debug = debug,
      extraEnhancer = extraEnhancer,
    )
    val http = httpClientFactory()
    val authClient = AuthClient(api, http)
    val syncClient = SyncClient(api, http)
    val hubClient = HubClient(api, http)

    lateinit var runtime: DayfoldRuntime
    lateinit var authEngine: AuthEngine
    lateinit var syncEngine: SyncEngine
    lateinit var syncCoordinator: SyncCoordinator
    lateinit var hubEngine: HubEngine
    lateinit var nowEngine: NowEngine
    lateinit var responseEngine: ResponseEngine
    lateinit var calendarCheckEngine: CalendarCheckEngine
    lateinit var commands: DayfoldCommands
    lateinit var coordinator: SessionCoordinator
    lateinit var externalHubTargets: PendingExternalHubTargetCoordinator

    try {
      runtime = DayfoldRuntime(
        backgroundDispatcher = backgroundDispatcher,
        componentsFactory = { rootScope ->
          val refreshScope = rootScope.supervisedChild()
          coordinator = SessionCoordinator(
            refreshScope = refreshScope,
            refreshSession = { context -> context.refreshWith(authClient::refresh) },
            commitRotation = { session ->
              tokenStore.save(session)
              store.dispatch(SessionRotated(session))
            },
          )
          store.state.session.session?.let { session ->
            val auth = coordinator.install(session)
            coordinator.selectFamily(auth, store.state.session.activeFamilyId)
          }

          authEngine = AuthEngine(
            store = store,
            authClient = authClient,
            tokenStore = tokenStore,
            devSecret = devSecret,
            devProvider = devProvider,
            devProviderUid = devProviderUid,
            clearCache = contentStore::wipe,
            loadCachedMemberships = contentStore::cachedMemberships,
            saveMemberships = contentStore::replaceMemberships,
            scope = rootScope.supervisedChild(),
            databaseDispatcher = databaseDispatcher,
            beforeTerminalCleanup = {
              runtime.closeFamilyForTerminal()
              externalHubTargets.clear()
            },
            sessionCoordinator = coordinator,
          )
          syncEngine = SyncEngine(
            store = store,
            contentStore = contentStore,
            syncClient = syncClient,
            pollIntervalMs = pollIntervalMs,
            // Compatibility-only scope for the legacy bridge adapter. The runtime path below
            // gives polling and drain ownership to SyncCoordinator's replaceable family scope.
            scope = rootScope.supervisedChild(),
            nowProvider = nowProvider,
            authClient = authClient,
            tokenStore = tokenStore,
            suppliedSessionCoordinator = coordinator,
            databaseDispatcher = databaseDispatcher,
            onSessionInvalidated = { context, expired ->
              // A sync pass is a child of the family that terminal cleanup must cancel and join.
              // Move cleanup to a root-owned child so the pass can return before that join.
              runtime.launchTerminalCleanup {
                authEngine.terminateFamilySession(context, expired)
              }
            },
          )
          syncCoordinator = SyncCoordinator(
            syncEngine = syncEngine,
            pollIntervalMs = pollIntervalMs,
          )
          syncEngine.attachCoordinator(syncCoordinator)
          hubEngine = HubEngine(
            store = store,
            hubClient = hubClient,
            authClient = authClient,
            tokenStore = tokenStore,
            contentStore = contentStore,
            syncEngine = syncEngine,
            scope = rootScope.supervisedChild(),
            nowProvider = nowProvider,
            idProvider = idProvider,
            databaseDispatcher = databaseDispatcher,
            suppliedSessionCoordinator = coordinator,
            onSessionExpired = { context ->
              // Audience requests are family children too; awaiting cleanup here would self-join.
              runtime.launchTerminalCleanup {
                authEngine.terminateFamilySession(context, expired = true)
              }
            },
          )
          nowEngine = NowEngine(
            store = store,
            contentStore = contentStore,
            scope = rootScope.supervisedChild(),
            nowProvider = nowProvider,
            databaseDispatcher = databaseDispatcher,
            sessionCoordinator = coordinator,
          )
          responseEngine = ResponseEngine(
            store = store,
            contentStore = contentStore,
            syncEngine = syncEngine,
            // There is no connectivity API in this client, so "online" is approximated by the
            // last sync outcome: a failed sync means we could not reach the server, which is
            // what the receipt copy is actually about. It only chooses which honest sentence
            // to show — the write lands locally and queues either way, so a wrong guess costs
            // a word, never a write.
            isOnline = { store.state.content.error == null },
            nowProvider = nowProvider,
            idProvider = idProvider,
            databaseDispatcher = databaseDispatcher,
          )
          calendarCheckEngine = CalendarCheckEngine(
            store = store,
            contentStore = contentStore,
            calendarPort = calendarPort,
            scope = rootScope.supervisedChild(),
            nowProvider = nowProvider,
            databaseDispatcher = databaseDispatcher,
          )
          externalHubTargets = PendingExternalHubTargetCoordinator(
            scope = rootScope,
            isCurrent = coordinator::isCurrent,
            deliver = { family, target, onAdmitted ->
              hubEngine.openHub(
                context = family,
                hubId = target.hubId,
                focusBlockId = target.blockId,
                onAdmitted = onAdmitted,
              )
            },
          )
          commands = DayfoldCommands(
            store = store,
            scope = rootScope,
            authEngine = authEngine,
            syncCoordinator = syncCoordinator,
            hubEngine = hubEngine,
            nowEngine = nowEngine,
            responseEngine = responseEngine,
            contentStore = contentStore,
            sessionCoordinator = coordinator,
            externalHubTargets = externalHubTargets,
            calendarCheckEngine = calendarCheckEngine,
            bindSelectedFamily = {
              val auth = coordinator.authSnapshot()
              val familyId = store.state.session.activeFamilyId
              if (auth != null && familyId != null) {
                runtime.replaceFamily(auth, familyId)?.let { externalHubTargets.familyBound(it) }
              }
            },
          )

          DayfoldRuntimeComponents(
            sessionCoordinator = coordinator,
            contentBridge = ContentBridge(
              store = store,
              contentStore = contentStore,
              sessionCoordinator = coordinator,
              scope = rootScope.supervisedChild(),
              databaseDispatcher = databaseDispatcher,
            ),
            bindFamilyWork = { context, familyScope, publication ->
              hubEngine.bindFamilyWork(context, familyScope, publication)
            },
            closeFamilyWorkAdmission = hubEngine::closeFamilyAdmission,
          )
        },
        prepareSchema = {
          withContext(databaseDispatcher) { contentStore.reconcileSchemaVersion() }
        },
        restoreAuth = { _, publication ->
          authEngine.restore()
          val auth = coordinator.authSnapshot()
          val familyId = store.state.session.activeFamilyId
          if (auth != null && familyId != null && publication.isOpen) {
            runtime.replaceFamily(auth, familyId)?.let { externalHubTargets.familyBound(it) }
          } else if (auth == null) {
            // Restore has now proved there is no identity. Discard a cold notification from a
            // previous signed-out user rather than carrying its tenant-less Hub id into sign-in.
            externalHubTargets.clear()
          }
        },
        resumeSync = { familyScope, family, publication ->
          if (family != null && coordinator.isCurrent(family)) {
            publication.publish { syncCoordinator.resume(familyScope) }
          }
        },
        pauseSync = { syncCoordinator.pause() },
        wipeFamily = {
          withContext(databaseDispatcher) { contentStore.wipe() }
        },
        closeResources = {
          syncEngine.stop()
          hubEngine.stop()
          nowEngine.stop()
          calendarCheckEngine.stop()
          http.close()
          onResourcesClosed()
        },
      )
    } catch (error: Throwable) {
      http.close()
      throw error
    }

    return DayfoldRuntimeGraph(
      runtime = runtime,
      store = store,
      commands = commands,
      authEngine = authEngine,
      syncEngine = syncEngine,
      syncCoordinator = syncCoordinator,
      hubEngine = hubEngine,
      nowEngine = nowEngine,
      sessionCoordinator = coordinator,
      externalHubTargets = externalHubTargets,
    )
  }

  private fun CoroutineScope.supervisedChild(): CoroutineScope =
    CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
}
