package com.sloopworks.dayfold.client

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
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
  private val syncPass: suspend (reason: SyncReason, isConflatedRerun: Boolean) -> Unit,
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
  // what [resumed] means for anyone else. See [requestSyncOnce].
  private var oneShotArmed = false

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
   * stopped the 45s poll loop. [pause] exists to stop that POLL LOOP for battery, not to refuse
   * work outright — a headless background wake (WorkManager, BGAppRefreshTask) is exactly the
   * legitimate one-shot the poll loop was paused in favour of.
   *
   * Never starts or restarts the poller and never flips [resumed] — a caller cannot observe this
   * coordinator as "resumed" afterward, and no polling resumes as a side effect. Serialized
   * through the SAME worker Job, generation, and `pending`/`pendingReason` conflation slot as
   * every other pass: [syncPass] is still ever invoked by exactly one coroutine at a time (the
   * ADR 0058 invariant this file depends on) — a request made while a pass is already in flight
   * (started by [resume]'s worker or an earlier call to this method) conflates into that pass's
   * existing rerun loop rather than starting a second, concurrent one.
   *
   * If [resume] has never been called yet for this coordinator (no worker exists for any
   * generation), this only records the pending request and returns — exactly what [requestSync]
   * already does in that state. That state means no family session has ever been bound, so there
   * is nothing to sync yet regardless; the request is picked up, tagged with whatever reason a
   * subsequent [resume] assigns, same as today.
   */
  fun requestSyncOnce(reason: SyncReason): Boolean {
    val worker = synchronized(gate) {
      if (closed) return false
      pending = true
      pendingReason = reason
      oneShotArmed = true
      active?.takeIf { it.worker.isActive }
    }
    worker?.signal?.trySend(Unit)
    return true
  }

  /** Cancels the worker and poller, rejects future requests, and clears any pending rerun. */
  fun close() {
    val previous = synchronized(gate) {
      if (closed) return
      closed = true
      resumed = false
      pending = false
      pendingReason = null
      oneShotArmed = false
      active.also { active = null }
    }
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
          val reason = claimPass(expectedGeneration) ?: break
          Log.d("sync") { "running conflated pass: ${reason.name}" }
          syncPass(reason, isConflatedRerun)
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

  private fun claimPass(expectedGeneration: Long): SyncReason? = synchronized(gate) {
    if (closed || active?.generation != expectedGeneration || !pending || !(resumed || oneShotArmed)) {
      null
    } else {
      pending = false
      oneShotArmed = false
      checkNotNull(pendingReason.also { pendingReason = null }) {
        "A pending sync pass must have a reason"
      }
    }
  }

  private fun nextGeneration(current: Long): Long =
    if (current == Long.MAX_VALUE) 1L else current + 1L
}
