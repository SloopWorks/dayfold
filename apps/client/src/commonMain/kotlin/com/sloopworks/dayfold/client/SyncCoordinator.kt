package com.sloopworks.dayfold.client

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Identifies why a conflated sync pass was requested without carrying request or tenant data. */
enum class SyncReason {
  RESUME,
  POLL,
  MANUAL_REFRESH,
  OUTBOX_MUTATION,
  PUSH,
  BACKGROUND,
}

/**
 * Owns sync scheduling while delegating the contents of each pass to [syncPass].
 *
 * It serializes passes, conflates requests made during a pass into one rerun, and applies foreground
 * polling and pause/resume policy. It does not perform network or database work, validate sessions,
 * publish Redux state, or own the runtime-supplied [CoroutineScope]; [close] cancels only its jobs.
 */
class SyncCoordinator internal constructor(
  // Returns whether the pass actually synced. A pass can legitimately do nothing (no family bound
  // yet, or the pass failed) and a headless caller must be able to tell — see
  // [requestSyncOnceAndAwait] and `RefreshOutcome.synced`.
  private val syncPass: suspend (reason: SyncReason, isConflatedRerun: Boolean) -> Boolean,
  private val pollIntervalMs: Long = 45_000L,
) {
  init {
    require(pollIntervalMs > 0L) { "pollIntervalMs must be positive" }
  }

  /** Creates a coordinator that serializes passes performed by [syncEngine]. */
  constructor(
    syncEngine: SyncEngine,
    pollIntervalMs: Long = 45_000L,
  ) : this(
    syncPass = { reason: SyncReason, isConflatedRerun: Boolean ->
      syncEngine.syncNow(reason, isConflatedRerun)
    },
    pollIntervalMs = pollIntervalMs,
  )

  private class ActiveWorker(
    val generation: Long,
    val signal: Channel<Unit>,
    val worker: Job,
    var poller: Job? = null,
  )

  private val gate = SynchronizedObject()
  private var generation = 0L
  private var active: ActiveWorker? = null
  private var resumed = false
  private var pending = false
  private var pendingReason: SyncReason? = null
  private var closed = false
  // Lets ONE already-claimed pending pass through claimPass() while paused, without changing
  // what [resumed] means for anyone else. See [requestSyncOnceAndAwait].
  private var oneShotArmed = false
  // Waiters registered by [requestSyncOnceAndAwait], drained by whichever [claimPass] call
  // actually consumes the pending request they registered under — never completed early. See
  // that method's doc for why this is safe under conflation, and [close] for how a waiter still
  // outstanding when the coordinator closes is failed rather than left hanging.
  private val oneShotWaiters = mutableListOf<CompletableDeferred<Boolean>>()

  /**
   * Starts or resumes the worker in [ownerScope], requests one immediate pass, and starts polling.
   * Repeated calls while resumed are idempotent and do not create another worker or poller.
   */
  fun resume(ownerScope: CoroutineScope) {
    val (worker, needsStart) = synchronized(gate) {
      if (closed) return
      val current = active
      val creating = current == null || !current.worker.isActive
      val selected = if (creating) {
        generation = nextGeneration(generation)
        val expectedGeneration = generation
        val signal = Channel<Unit>(Channel.CONFLATED)
        val job = ownerScope.launch(start = CoroutineStart.LAZY) {
          workerLoop(expectedGeneration, signal)
        }
        val started = ActiveWorker(expectedGeneration, signal, job)
        active = started
        started
      } else {
        current
      }
      val wasResumed = resumed
      resumed = true
      // A replacement worker always gets an immediate recovery pass, even if the prior worker
      // terminated unexpectedly while the foreground lifecycle remained resumed.
      if (!wasResumed || creating) {
        pending = true
        pendingReason = SyncReason.RESUME
      }
      if (selected.poller?.isActive != true) {
        selected.poller = ownerScope.launch {
          while (isActive) {
            delay(pollIntervalMs)
            requestSync(SyncReason.POLL)
          }
        }
      }
      selected to creating
    }
    if (needsStart) worker.worker.start()
    worker.signal.trySend(Unit)
  }

  /** Stops foreground polling and holds any pending rerun until [resume]. */
  fun pause() {
    val poller = synchronized(gate) {
      if (closed) return
      resumed = false
      active?.poller.also { active?.poller = null }
    }
    poller?.cancel()
  }

  /**
   * Conflates [reason] into the next pass. Returns false only after this coordinator is closed.
   * The reason is intentionally diagnostic-only; no credentials or tenant identifiers enter it.
   */
  fun requestSync(reason: SyncReason): Boolean {
    val worker = synchronized(gate) {
      if (closed) return false
      pending = true
      pendingReason = reason
      active?.takeIf { resumed && it.worker.isActive }
    }
    worker?.signal?.trySend(Unit)
    return true
  }

  /**
   * Runs exactly one pass through the worker for the current generation even while [pause] has
   * stopped the 45s poll loop, and SUSPENDS until the pass that actually services this request has
   * finished (or this coordinator [close]s first). [pause] exists to stop that POLL LOOP for
   * battery, not to refuse work outright — a headless background wake (WorkManager,
   * BGAppRefreshTask) is exactly the legitimate one-shot the poll loop was paused in favour of, and
   * it has a completion promise to keep: the OS is only told "done" once this returns (see
   * `DayfoldRuntimeGraph.requestBackgroundSync`'s doc and `RefreshDeps.delegateToRuntime`).
   *
   * Never starts or restarts the poller and never flips [resumed] — a caller cannot observe this
   * coordinator as "resumed" afterward, and no polling resumes as a side effect. Serialized
   * through the SAME worker Job, generation, and `pending`/`pendingReason` conflation slot as
   * every other pass: [syncPass] is still ever invoked by exactly one coroutine at a time (the
   * ADR 0058 invariant this file depends on) — a request made while a pass is already in flight
   * (started by [resume]'s worker or an earlier call to this method) conflates into that pass's
   * existing rerun loop rather than starting a second, concurrent one.
   *
   * Returns whether a pass actually SYNCED for this request — [syncPass]'s own answer, which is
   * false when the pass ran but did nothing (no family bound) or failed. False also when this
   * coordinator is closed, and when no worker exists to service the request (below). The caller is
   * a background wake whose only observability is one log line, so "I was accepted" is not a
   * useful answer; "content was refreshed" is.
   *
   * Registers a private [CompletableDeferred] in [oneShotWaiters] in the SAME synchronized block
   * that arms [pending]/[pendingReason]/[oneShotArmed], then awaits it OUTSIDE that block — never
   * holding [gate] while suspended. [claimPass] drains the current waiters list at the exact
   * moment it claims a pending request (single shared `pending` flag, exactly like every other
   * conflation path here), so a waiter is only ever completed by the pass that runs AFTER its
   * registration:
   * - Already resumed and idle: the signal wakes the worker, [claimPass] claims immediately, this
   *   waiter's pass runs next.
   * - A pass is already in flight when this is called (the race the review flagged): this only
   *   adds to [pending]/[oneShotWaiters] and sends a signal that conflates harmlessly — no second
   *   concurrent [syncPass] call is ever made (ADR 0058's one-caller invariant is unchanged by
   *   this method). When the in-flight pass returns, the SAME worker's existing inner conflation
   *   loop calls [claimPass] again, drains this waiter along with the pending reason, and runs
   *   the conflated rerun — completing this waiter only once THAT pass finishes, never the one
   *   already running when it registered.
   * - No worker has ever been created ([resume] never called): arms [pending]/[pendingReason]
   *   exactly like [requestSync] does in that state (a later [resume] will eventually pick it
   *   up), but returns WITHOUT awaiting — see the second review round below for why this branch
   *   must not block.
   *
   * ADR 0020 R3 review round 2 — the delegate holder (`RuntimeHandleHolder`) registers the moment
   * a runtime graph is CONSTRUCTED, not once a family session is bound (`resumeSync` only calls [resume] after that). So a live-but-signed-
   * out or pre-family-bind runtime takes the `delegateToRuntime` branch with `active == null` —
   * no worker exists, nothing will ever claim this request until some future [resume]. Awaiting
   * in that state previously stalled the caller for its ENTIRE budget (~25s iOS, ~60s Android)
   * doing nothing, which is worse than the pre-await regression this method was added to fix.
   * When [active] is null at registration time, this returns `false` immediately without adding a
   * waiter or suspending — there is nothing to wait FOR yet, and nothing synced.
   *
   * Also removes its own waiter from [oneShotWaiters] in a `finally` around `await()`: if the
   * caller itself is cancelled before any [claimPass] call drains this request (e.g. the
   * surrounding `withTimeoutOrNull(budget)` in `backgroundRefreshPass` elapses while a slow pass
   * is still running), an unremoved entry would sit in [oneShotWaiters] until some later pass
   * eventually claims and uselessly completes it — harmless on its own, but combined with
   * repeated timed-out callers it accumulates one orphaned entry per wake. Removing it here is a
   * no-op when [claimPass] already drained it (the normal completion path).
   */
  suspend fun requestSyncOnceAndAwait(reason: SyncReason): Boolean {
    val done = CompletableDeferred<Boolean>()
    val worker = synchronized(gate) {
      if (closed) return false
      pending = true
      pendingReason = reason
      oneShotArmed = true
      val selected = active?.takeIf { it.worker.isActive }
      if (selected != null) oneShotWaiters += done
      selected
    }
    if (worker == null) {
      // Nothing will service this until a later resume() creates a worker — do not block a
      // budget-limited caller waiting on that, which may never happen this wake. Nothing synced.
      return false
    }
    worker.signal.trySend(Unit)
    return try {
      done.await()
    } finally {
      synchronized(gate) { oneShotWaiters.remove(done) }
    }
  }

  /** Cancels the worker and poller, rejects future requests, and clears any pending rerun. */
  fun close() {
    val (previous, waiters) = synchronized(gate) {
      if (closed) return
      closed = true
      resumed = false
      pending = false
      pendingReason = null
      oneShotArmed = false
      val waiters = oneShotWaiters.toList()
      oneShotWaiters.clear()
      (active.also { active = null }) to waiters
    }
    // Fail rather than complete: a waiter still outstanding here never got its pass run, and
    // completing it as "done" would tell a delegated caller a sync happened when it didn't.
    // CancellationException so it flows through the SAME `runCatching` path a real cancellation
    // would (see backgroundRefreshPass's delegate branch) rather than surfacing as a novel error.
    waiters.forEach { it.completeExceptionally(CancellationException("SyncCoordinator closed")) }
    previous?.signal?.close()
    previous?.poller?.cancel()
    previous?.worker?.cancel()
  }

  private suspend fun workerLoop(
    expectedGeneration: Long,
    signal: Channel<Unit>,
  ) {
    try {
      while (signal.receiveCatching().isSuccess) {
        var isConflatedRerun = false
        while (true) {
          val claimed = claimPass(expectedGeneration) ?: break
          Log.d("sync") { "running conflated pass: ${claimed.reason.name}" }
          val didSync = try {
            syncPass(claimed.reason, isConflatedRerun)
          } catch (error: Throwable) {
            claimed.waiters.forEach { it.completeExceptionally(error) }
            throw error
          }
          claimed.waiters.forEach { it.complete(didSync) }
          isConflatedRerun = true
        }
      }
    } catch (error: CancellationException) {
      throw error
    } finally {
      synchronized(gate) {
        val current = active
        if (current?.generation == expectedGeneration) {
          current.poller?.cancel()
          active = null
        }
      }
    }
  }

  /** A pending request claimed by [claimPass], paired with the [requestSyncOnceAndAwait] waiters
   *  (if any) that this specific claim is responsible for completing. */
  private class ClaimedPass(val reason: SyncReason, val waiters: List<CompletableDeferred<Boolean>>)

  private fun claimPass(expectedGeneration: Long): ClaimedPass? = synchronized(gate) {
    if (closed || active?.generation != expectedGeneration || !pending || !(resumed || oneShotArmed)) {
      null
    } else {
      pending = false
      oneShotArmed = false
      val reason = checkNotNull(pendingReason.also { pendingReason = null }) {
        "A pending sync pass must have a reason"
      }
      val waiters = oneShotWaiters.toList()
      oneShotWaiters.clear()
      ClaimedPass(reason, waiters)
    }
  }

  private fun nextGeneration(current: Long): Long =
    if (current == Long.MAX_VALUE) 1L else current + 1L
}
