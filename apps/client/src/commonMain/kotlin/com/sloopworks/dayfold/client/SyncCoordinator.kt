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
 * Serializes sync passes behind one conflated request signal.
 *
 * Requests made during a pass schedule at most one rerun. Pausing stops polling and holds a pending
 * rerun until [resume], while allowing the active pass to finish under its session currentness
 * checks. Closing cancels both the poller and active pass through their runtime-owned [CoroutineScope].
 * The coordinator owns jobs, never the supplied scope.
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

  /** Cancels the worker and poller, rejects future requests, and clears any pending rerun. */
  fun close() {
    val previous = synchronized(gate) {
      if (closed) return
      closed = true
      resumed = false
      pending = false
      pendingReason = null
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
    if (closed || !resumed || !pending || active?.generation != expectedGeneration) {
      null
    } else {
      pending = false
      checkNotNull(pendingReason.also { pendingReason = null }) {
        "A pending sync pass must have a reason"
      }
    }
  }

  private fun nextGeneration(current: Long): Long =
    if (current == Long.MAX_VALUE) 1L else current + 1L
}
