package com.sloopworks.dayfold.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import org.reduxkotlin.Store

// Orchestrates the offline-first dataflow (ADR 0020): owns the DB→store bridge,
// the drain loop (network→DB), status dispatch, and the foreground poll loop.
// start() = bridge only (cold-start). resume() = sync + poll. pause() = stop poll.
class SyncEngine(
  private val store: Store<AppState>,
  private val contentStore: ContentStore,
  private val syncClient: SyncClient,
  private val pollIntervalMs: Long = 45_000L,
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
  private val nowProvider: () -> String = { Clock.System.now().toString() },
  // Refresh-on-401 (mirrors AuthEngine/HubEngine.callWithRefresh). Null = no refresh
  // (tests / not-yet-wired entrypoints), in which case a 401 surfaces as SyncFailed.
  private val authClient: AuthClient? = null,
  private val tokenStore: TokenStore? = null,
) {
  private val syncMutex = Mutex()
  private var bridgeJob: Job? = null
  private var hubBridgeJob: Job? = null
  private var pollJob: Job? = null

  /**
   * Cold-start hydration: project the DB into the store. First emission = cached rows, zero network.
   * Not thread-safe — must be called from the main thread ([bridgeJob] guard is non-atomic).
   * The second bridge (hubBridgeJob) keeps state.hubs in sync with the DB — it is the ONLY
   * writer of state.hubs (one-writer-per-slice: no other path dispatches HubsLoaded).
   */
  fun start() {
    if (bridgeJob != null) return
    bridgeJob = scope.launch {
      contentStore.activeCardsFlow().collect { store.dispatch(CardsLoaded(it)) }
    }
    hubBridgeJob = scope.launch {
      contentStore.activeHubsFlow().collect { store.dispatch(HubsLoaded(it)) }
    }
  }

  /**
   * Foreground: sync immediately + (re)start the poll loop.
   * Not thread-safe — must be called from the main thread ([pollJob] guard is non-atomic).
   */
  fun resume() {
    scope.launch { syncNow() }
    if (pollJob == null) {
      pollJob = scope.launch {
        while (isActive) { delay(pollIntervalMs); syncNow() }
      }
    }
  }

  /** Background: stop polling (the bridge keeps running so the store stays live). */
  fun pause() { pollJob?.cancel(); pollJob = null }

  /** One full sync: drain all pages into the DB in order; status to the store. Mutex-guarded so
   *  poll / resume / future-push never overlap and race the cursor. Public for the future push hook. */
  suspend fun syncNow() = syncMutex.withLock {
    store.dispatch(SyncStarted)
    try {
      drain()
      store.dispatch(SyncSucceeded)
    } catch (e: SyncHttpException) {
      // 401 = the 5-min access token expired (or is stale). Refresh it and retry
      // ONCE (mirrors AuthEngine/HubEngine) — without this the foreground sync +
      // 45s poll 401 forever and the feed wedges on "Couldn't refresh".
      if (e.status == 401 && refreshSession()) {
        try {
          drain()
          store.dispatch(SyncSucceeded)
        } catch (e2: SyncHttpException) {
          onSyncHttpError(e2)
        } catch (e2: Exception) {
          store.dispatch(SyncFailed(e2.message ?: "sync error"))
        }
      } else {
        onSyncHttpError(e)
      }
    } catch (e: Exception) {
      store.dispatch(SyncFailed(e.message ?: "sync error"))
    }
  }

  /** Drain all /sync pages into the DB in order (each page is its own atomic applyDelta). */
  private suspend fun drain() {
    var hasMore = true
    while (hasMore) {
      val resp = syncClient.fetchPage(contentStore.cursor())
      contentStore.applyDelta(
        changedCards = resp.changes.cards,
        changedHubs = resp.changes.hubs,
        changedSections = resp.changes.sections,
        changedBlocks = resp.changes.blocks,
        tombstones = resp.tombstones,
        nextCursor = resp.nextCursor,
        nowIso = nowProvider(),
      )
      hasMore = resp.hasMore
    }
  }

  /** Refresh the access token after a sync 401; rotate it into the store + keychain so
   *  the retry (and the SyncClient token provider) use the fresh token. Returns true
   *  iff rotated. No-op (false) when refresh isn't wired or there's no session. */
  private suspend fun refreshSession(): Boolean {
    val ac = authClient ?: return false
    val session = store.state.session ?: return false
    println("[sync] 401 — refreshing access token")
    val rotated = try {
      ac.refresh(session.refresh)
    } catch (e: Exception) {
      println("[sync] token refresh failed: ${e.message ?: "error"}")
      return false
    }
    tokenStore?.save(rotated)
    store.dispatch(SessionRotated(rotated))
    println("[sync] token refreshed — retrying sync")
    return true
  }

  // ADR 0030 (round-1 P0-2): 403 (removed) / 404 (non-member) = tenancy revocation →
  // the cache is forbidden content; wipe it + sign out. Anything else (incl. a 401 the
  // refresh couldn't recover) surfaces as a normal, non-destructive failure.
  private fun onSyncHttpError(e: SyncHttpException) {
    if (e.status == 403 || e.status == 404) {
      contentStore.wipe()
      store.dispatch(SignedOut)
    } else {
      println("[sync] failed: HTTP ${e.status}")
      store.dispatch(SyncFailed("HTTP ${e.status}"))
    }
  }

  fun stop() {
    bridgeJob?.cancel(); bridgeJob = null
    hubBridgeJob?.cancel(); hubBridgeJob = null
    pollJob?.cancel(); pollJob = null
    scope.cancel()
  }
}
