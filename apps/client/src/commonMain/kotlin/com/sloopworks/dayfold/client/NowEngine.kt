package com.sloopworks.dayfold.client

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import org.reduxkotlin.Store

/**
 * Serializes the local-only persistence effects behind the Now surface.
 *
 * UI reports visible or dismissed subjects; one actor batches and debounces those commands, writes
 * anti-nag state off-main, and publishes only for the still-current tenant generation. The engine
 * does not rank content, decide visibility, write from composition, or own its supplied scope.
 */
class NowEngine(
  private val store: Store<AppState>,
  private val contentStore: ContentStore,
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
  private val nowProvider: () -> String = { Clock.System.now().toString() },
  private val debounceMs: Long = 750L,
  private val databaseDispatcher: CoroutineDispatcher = Dispatchers.Default,
  private val sessionCoordinator: SessionCoordinator? = null,
) {
  private data class TenantGeneration(
    val identityEpoch: Long,
    val familyRevision: Long,
    val familyId: String,
  )

  private data class TenantContext(
    val family: FamilySessionContext?,
    val generation: TenantGeneration?,
  )

  private data class ShownBatch(
    val context: TenantContext,
    val subjectKeys: Set<String>,
  )

  private sealed interface Command {
    data object ShownWake : Command
    data class Dismiss(
      val context: TenantContext,
      val subjectKey: String,
      val nowIso: String,
    ) : Command
    data class Flush(val completion: CompletableDeferred<Unit>) : Command
  }

  // Render may report on the UI thread. Conflate those reports synchronously without launching a
  // coroutine per recomposition; one ShownWake represents the union accumulated behind this gate.
  private val ingressGate = SynchronizedObject()
  private val ingressShown = mutableMapOf<TenantGeneration?, ShownBatch>()
  private var shownWakeQueued = false
  private val commands = Channel<Command>(Channel.UNLIMITED)
  private val actorJob: Job = scope.launch { commandLoop() }

  /**
   * Reports the currently surfaced subjects. Reports within one fixed debounce window are
   * conflated; later reports join the active window without moving its deadline, so a changing
   * feed cannot starve the write indefinitely.
   */
  fun noteShown(subjectKeys: Set<String>) {
    if (subjectKeys.isEmpty()) return
    val context = currentTenantContext() ?: return
    synchronized(ingressGate) {
      val previous = ingressShown[context.generation]
      ingressShown[context.generation] = ShownBatch(
        context = context,
        subjectKeys = previous?.subjectKeys.orEmpty() + subjectKeys,
      )
      if (!shownWakeQueued) {
        shownWakeQueued = true
        if (commands.trySend(Command.ShownWake).isFailure) shownWakeQueued = false
      }
    }
  }

  /** Dismisses a subject from future ranking. The timestamp is captured at the command edge. */
  fun dismiss(subjectKey: String) {
    val context = currentTenantContext() ?: return
    Log.d("now") { "subject dismissed" }
    commands.trySend(Command.Dismiss(context, subjectKey, nowProvider()))
  }

  /** Flushes the active shown batch through the actor; used by deterministic lifecycle tests. */
  internal suspend fun flushPending() {
    val completion = CompletableDeferred<Unit>()
    commands.send(Command.Flush(completion))
    completion.await()
  }

  private suspend fun commandLoop() {
    val pending = mutableMapOf<TenantGeneration?, ShownBatch>()

    suspend fun flush() {
      if (pending.isEmpty()) return
      val batches = pending.values.toList()
      pending.clear()
      val now = nowProvider()
      Log.d("now") { "surfacing computed count=${batches.sumOf { it.subjectKeys.size }}" }
      withContext(databaseDispatcher) {
        batches.forEach { batch ->
          commitIfCurrent(batch.context) {
            // SQL's write-if-new constraint is authoritative across delayed bridge delivery,
            // process restarts, and headless writers.
            batch.subjectKeys.forEach { key -> contentStore.recordShownIfNew(key, now) }
          }
        }
      }
    }

    fun acceptShown() {
      val reported = synchronized(ingressGate) {
        val result = ingressShown.values.toList()
        ingressShown.clear()
        shownWakeQueued = false
        result
      }
      reported.forEach { batch ->
        val previous = pending[batch.context.generation]
        pending[batch.context.generation] = ShownBatch(
          context = batch.context,
          subjectKeys = previous?.subjectKeys.orEmpty() + batch.subjectKeys,
        )
      }
    }

    suspend fun handle(command: Command): Boolean {
      when (command) {
        Command.ShownWake -> acceptShown()
        is Command.Dismiss -> withContext(databaseDispatcher) {
          commitIfCurrent(command.context) {
            contentStore.recordDismissed(command.subjectKey, command.nowIso)
          }
        }
        is Command.Flush -> {
          flush()
          command.completion.complete(Unit)
          return false
        }
      }
      return true
    }

    suspend fun runFixedWindow() = coroutineScope {
      val timer = async { delay(debounceMs) }
      var receiving = true
      while (receiving && pending.isNotEmpty()) {
        val command = select<Command?> {
          timer.onAwait { null }
          commands.onReceiveCatching { it.getOrNull() }
        }
        if (command == null) {
          flush()
          receiving = false
        } else {
          receiving = handle(command)
        }
      }
      timer.cancel()
    }

    while (currentCoroutineContext().isActive) {
      val command = commands.receiveCatching().getOrNull() ?: break
      handle(command)
      if (pending.isNotEmpty()) runFixedWindow()
    }
  }

  private fun currentTenantContext(): TenantContext? {
    val coordinator = sessionCoordinator ?: return TenantContext(null, null)
    val familyId = store.state.activeFamilyId ?: return null
    val family = coordinator.familySnapshot(familyId) ?: return null
    return TenantContext(
      family = family,
      generation = TenantGeneration(
        identityEpoch = family.authContext.identityEpoch,
        familyRevision = family.familyRevision,
        familyId = family.familyId,
      ),
    )
  }

  private fun commitIfCurrent(context: TenantContext, block: () -> Unit): Boolean =
    context.family?.let { family -> sessionCoordinator?.commitIfCurrent(family, block) }
      ?: run { block(); true }

  /** Cancels only work owned by this engine; the injected runtime scope remains caller-owned. */
  fun stop() {
    commands.close()
    actorJob.cancel()
  }
}
