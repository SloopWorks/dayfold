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
) {
  private val syncMutex = Mutex()
  private var bridgeJob: Job? = null
  private var pollJob: Job? = null

  /**
   * Cold-start hydration: project the DB into the store. First emission = cached rows, zero network.
   * Not thread-safe — must be called from the main thread ([bridgeJob] guard is non-atomic).
   */
  fun start() {
    if (bridgeJob != null) return
    bridgeJob = scope.launch {
      contentStore.activeCardsFlow().collect { store.dispatch(CardsLoaded(it)) }
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
      var hasMore = true
      while (hasMore) {
        val resp = syncClient.fetchPage(contentStore.cursor())
        contentStore.applyDelta(
          changed = resp.changes.cards,
          tombstoneIds = resp.tombstones.filter { it.type == "card" }.map { it.id },
          nextCursor = resp.nextCursor,
          nowIso = nowProvider(),
        )
        hasMore = resp.hasMore
      }
      store.dispatch(SyncSucceeded)
    } catch (e: Exception) {
      store.dispatch(SyncFailed(e.message ?: "sync error"))
    }
  }

  fun stop() { scope.cancel() }
}
