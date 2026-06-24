package com.sloopworks.dayfold.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.reduxkotlin.Store

// Orchestrates the Hubs surface (ADR 0006). PR2: openHub is now DB-fed — it dispatches
// OpenHub, triggers a background sync, and subscribes to contentStore.hubTreeFlow(hubId)
// dispatching HubTreeLoaded whenever the DB delivers tree rows. Removes the direct
// hubTree network call; keeps HubClient for audience(). Mutex-guarded like AuthEngine.
class HubEngine(
  private val store: Store<AppState>,
  private val hubClient: HubClient,
  private val authClient: AuthClient,
  private val tokenStore: TokenStore,
  private val contentStore: ContentStore,
  private val syncEngine: SyncEngine,
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
  private val mutex = Mutex()
  private var treeJob: Job? = null

  private fun fid(): String? = store.state.activeFamilyId
  private fun session(): Session? = store.state.session

  // PR1: the hub LIST is now DB-fed via the SyncEngine hub bridge — this method is a
  // no-op. The bridge (SyncEngine.hubBridgeJob) is the sole writer of state.hubs via
  // HubsLoaded. Callers (shells' onLoadHubs) should trigger syncEngine.syncNow() instead.
  suspend fun loadHubs() = Unit

  // PR2: DB-fed openHub. Dispatches OpenHub immediately, triggers a sync in the background,
  // then subscribes to hubTreeFlow(hubId) — dispatching HubTreeLoaded whenever the DB emits
  // a non-null tree. Prior tree subscription is cancelled on each new openHub call.
  suspend fun openHub(hubId: String) = mutex.withLock {
    store.dispatch(OpenHub(hubId))              // list → detail (busy)
    // Cancel any prior tree subscription
    treeJob?.cancel(); treeJob = null
    // Trigger a sync so tree rows arrive in the DB
    scope.launch { syncEngine.syncNow() }
    // Subscribe to DB tree flow → dispatch HubTreeLoaded
    treeJob = scope.launch {
      contentStore.hubTreeFlow(hubId).collect { tree ->
        if (tree != null) store.dispatch(HubTreeLoaded(tree))
        // null = hub not in cache yet (or tombstoned); hubsBusy stays true until tree arrives
      }
    }
  }

  // Cancel the tree subscription when closing the hub detail view.
  suspend fun closeHub() = mutex.withLock {
    treeJob?.cancel(); treeJob = null
    store.dispatch(CloseHub)
  }

  suspend fun loadAudience(hubId: String) = mutex.withLock {
    val fid = fid(); val s = session()
    if (fid == null || s == null) return@withLock
    try {
      val aud = callWithRefresh(s) { hubClient.audience(it.access, fid, hubId) }
      store.dispatch(HubAudienceLoaded(aud))
    } catch (_: Exception) { /* sheet shows a quiet loading/empty; non-fatal */ }
  }

  // Refresh-and-retry on 401 (mirrors AuthEngine.callWithRefresh): the access token
  // is 5m, so a single rotate + persist + dispatch + retry recovers transparently.
  private suspend fun <T> callWithRefresh(session: Session, block: suspend (Session) -> T): T =
    try {
      block(session)
    } catch (e: AuthHttpException) {
      if (e.status != 401) throw e
      val rotated = authClient.refresh(session.refresh)
      tokenStore.save(rotated)
      store.dispatch(SessionRotated(rotated))
      block(rotated)
    }

  fun stop() {
    treeJob?.cancel(); treeJob = null
    scope.cancel()
  }
}
