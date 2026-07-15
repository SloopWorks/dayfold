package com.sloopworks.dayfold.client

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The externally observable lifecycle of [DayfoldRuntime]. */
enum class DayfoldRuntimeState {
  NEW,
  STARTING,
  PAUSED,
  ACTIVE,
  CLOSING,
  CLOSED,
}

/**
 * Runtime-owned dependencies that must be created in [scope].
 *
 * The factory shape prevents the session coordinator and content collectors from silently owning
 * process-global coroutine scopes outside the runtime's structured-concurrency tree.
 */
internal class DayfoldRuntimeComponents(
  val sessionCoordinator: SessionCoordinator,
  val contentBridge: ContentBridge,
  val bindFamilyWork: (
    context: FamilySessionContext,
    scope: CoroutineScope,
    publication: PublicationBoundary,
  ) -> Unit = { _, _, _ -> },
  val closeFamilyWorkAdmission: () -> Unit = {},
)

/**
 * Owns the shared client's structured lifetime and its replaceable family-work boundary.
 *
 * The runtime starts and stops engine work, scopes every child, fences publication, and orders family
 * replacement as close, join, wipe, then bind. It delegates authentication, sync, Hub, database, and
 * presentation rules to their owning components. It does not retain platform UI objects, replace the
 * Redux store, or serve as the database lock. [cancel] closes admission synchronously;
 * [awaitClosed] provides ordered teardown for hosts and tests.
 */
class DayfoldRuntime internal constructor(
  backgroundDispatcher: CoroutineDispatcher,
  componentsFactory: (scope: CoroutineScope) -> DayfoldRuntimeComponents,
  private val prepareSchema: suspend () -> Unit,
  private val restoreAuth: suspend (
    scope: CoroutineScope,
    publication: PublicationBoundary,
  ) -> Unit = { _, _ -> },
  private val resumeSync: suspend (
    scope: CoroutineScope,
    family: FamilySessionContext?,
    publication: PublicationBoundary,
  ) -> Unit = { _, _, _ -> },
  private val pauseSync: suspend () -> Unit = {},
  private val wipeFamily: suspend () -> Unit,
  private val closeResources: () -> Unit,
) {
  private val rootJob = SupervisorJob()
  private val rootScope = CoroutineScope(rootJob + backgroundDispatcher)
  private val components = componentsFactory(rootScope)
  private val lifecycleMutex = Mutex()
  private val stateGate = SynchronizedObject()
  private val publication = PublicationBoundary()
  private val closed = CompletableDeferred<Unit>()

  private var state = DayfoldRuntimeState.NEW
  private var authJob: Job? = null
  private var familyJob: Job? = null
  private var familyHandle: ContentBridgeHandle? = null
  private var familyPublication: PublicationBoundary? = null
  private var deviceHandle: ContentBridgeHandle? = null
  private var activeFamily: FamilySessionContext? = null

  // This observer is intentionally not a root child: it must run only after the root completes.
  // Keeping it on the background dispatcher also preserves cancel() as a non-blocking boundary.
  private val closeObserver = CoroutineScope(backgroundDispatcher).launch {
    rootJob.join()
    run {
      val closeFailure = runCatching(closeResources).exceptionOrNull()
      synchronized(stateGate) { state = DayfoldRuntimeState.CLOSED }
      if (closeFailure == null) closed.complete(Unit) else closed.completeExceptionally(closeFailure)
    }
  }

  /** Current lifecycle state. The value is safe to read from platform lifecycle threads. */
  val lifecycleState: DayfoldRuntimeState
    get() = synchronized(stateGate) { state }

  /**
   * Prepares the content schema before starting any DB projection, then begins auth restoration.
   * Repeated and concurrent calls share the serialized runtime transition and have no extra effect.
   */
  suspend fun start() {
    if (lifecycleState == DayfoldRuntimeState.CLOSING || lifecycleState == DayfoldRuntimeState.CLOSED) {
      return
    }
    runLifecycle {
      if (lifecycleState != DayfoldRuntimeState.NEW) return@runLifecycle
      setState(DayfoldRuntimeState.STARTING)
      try {
        prepareSchema()
        if (!publication.isOpen) return@runLifecycle

        publication.publish {
          val started = components.contentBridge.startDevice()
          synchronized(stateGate) { deviceHandle = started }
        }
        if (!publication.isOpen) return@runLifecycle

        startAuthChild()
        setStateIfOpen(DayfoldRuntimeState.PAUSED)
      } catch (error: Throwable) {
        cancel()
        throw error
      }
    }
  }

  /** Starts foreground sync once; calling it from NEW performs [start] first. */
  suspend fun resume() {
    start()
    if (lifecycleState == DayfoldRuntimeState.CLOSING || lifecycleState == DayfoldRuntimeState.CLOSED) {
      return
    }
    runLifecycle {
      if (lifecycleState != DayfoldRuntimeState.PAUSED) return@runLifecycle
      val familyScope = ensureFamilyScope()
      val (family, admission) = synchronized(stateGate) {
        activeFamily to (familyPublication ?: publication)
      }
      resumeSync(familyScope, family, admission)
      setStateIfOpen(DayfoldRuntimeState.ACTIVE)
    }
  }

  /** Stops foreground polling while leaving device and family DB projections alive. */
  suspend fun pause() {
    if (lifecycleState == DayfoldRuntimeState.CLOSING || lifecycleState == DayfoldRuntimeState.CLOSED) {
      return
    }
    runLifecycle {
      if (lifecycleState != DayfoldRuntimeState.ACTIVE) return@runLifecycle
      pauseSync()
      setStateIfOpen(DayfoldRuntimeState.PAUSED)
    }
  }

  /**
   * Replaces the selected family with strict close -> cancel/join -> wipe -> mint -> restart order.
   *
   * Repeating the current identity/family is idempotent. An A-to-B-to-A replacement still mints a
   * different family revision, so callbacks captured during the first A cannot publish into it.
   */
  suspend fun replaceFamily(
    auth: AuthSessionContext,
    familyId: String?,
  ): FamilySessionContext? {
    if (lifecycleState == DayfoldRuntimeState.CLOSING || lifecycleState == DayfoldRuntimeState.CLOSED) {
      return null
    }
    return runLifecycle {
      checkStarted()
      val selected = familyId?.takeIf(String::isNotBlank)
      val current = synchronized(stateGate) { activeFamily }
      if (
        current != null &&
        selected == current.familyId &&
        current.authContext.identityEpoch == auth.identityEpoch &&
        components.sessionCoordinator.isCurrent(current)
      ) {
        return@runLifecycle current
      }

      // AuthEngine selects the family before notifying the runtime. On the first bind, that
      // selected context belongs to either a restored identity (whose offline cache must survive)
      // or a fresh sign-in (which clears the previous cache before installing). Reuse it without
      // wiping. Every actual replacement still invalidates, joins, and wipes first.
      val selectedContext = selected?.let(components.sessionCoordinator::familySnapshot)
        ?.takeIf { it.authContext.identityEpoch == auth.identityEpoch }
      val preserveInitialCache = current == null && selectedContext != null

      closeFamilyChild(invalidateGeneration = !preserveInitialCache)
      if (!publication.isOpen) return@runLifecycle null

      if (!preserveInitialCache) {
        wipeFamily()
        if (!publication.isOpen) return@runLifecycle null
      }

      val next = selectedContext ?: components.sessionCoordinator.selectFamily(auth, selected)
      if (next == null) return@runLifecycle null

      val familyScope = newFamilyScope()
      val nextPublication = PublicationBoundary()
      var nextHandle: ContentBridgeHandle? = null
      try {
        publication.publish {
          components.bindFamilyWork(next, familyScope, nextPublication)
          nextHandle = components.contentBridge.startFamily(next)
          synchronized(stateGate) {
            familyHandle = nextHandle
            familyPublication = nextPublication
            activeFamily = next
          }
        }
      } catch (error: Throwable) {
        nextPublication.close()
        components.closeFamilyWorkAdmission()
        familyScope.cancel()
        throw error
      }
      if (nextHandle == null) {
        nextPublication.close()
        components.closeFamilyWorkAdmission()
        familyScope.cancel()
        return@runLifecycle null
      }

      if (lifecycleState == DayfoldRuntimeState.ACTIVE) {
        resumeSync(familyScope, next, nextPublication)
      }
      next
    }
  }

  /**
   * Fences family publication and joins all family-owned work before terminal cache/state cleanup.
   *
   * This is the runtime hook for Auth/Sync/Hub expiry paths. It deliberately leaves the device
   * bridge and runtime alive so the caller can wipe tenant data and publish SignedOut/Expired.
   */
  suspend fun closeFamilyForTerminal() {
    if (lifecycleState == DayfoldRuntimeState.CLOSING || lifecycleState == DayfoldRuntimeState.CLOSED) {
      return
    }
    runLifecycle { closeFamilyChild(invalidateGeneration = true) }
  }

  /** Cancels and joins a previous restore/sign-in operation before starting its replacement. */
  suspend fun restartAuth() {
    start()
    if (lifecycleState == DayfoldRuntimeState.CLOSING || lifecycleState == DayfoldRuntimeState.CLOSED) {
      return
    }
    runLifecycle {
      val previous = synchronized(stateGate) {
        val current = authJob
        authJob = null
        current
      }
      previous?.cancel()
      previous?.join()
      if (publication.isOpen) startAuthChild()
    }
  }

  /**
   * Immediately rejects publication and begins cancellation without blocking a platform callback.
   * Resource closure happens automatically after every runtime-owned child terminates.
   */
  fun cancel() {
    publication.close()

    val handlesAndJobs = synchronized(stateGate) {
      if (state == DayfoldRuntimeState.CLOSING || state == DayfoldRuntimeState.CLOSED) return
      state = DayfoldRuntimeState.CLOSING
      Triple(
        listOf(familyHandle, deviceHandle),
        listOf(authJob, familyJob),
        familyPublication,
      )
    }

    // ContentBridgeHandle.cancel closes its own narrower boundary before cancelling collectors.
    handlesAndJobs.third?.close()
    components.closeFamilyWorkAdmission()
    handlesAndJobs.first.forEach { it?.cancel() }
    handlesAndJobs.second.forEach { it?.cancel() }
    rootJob.cancel()
  }

  /** Waits for ordered teardown. Safe to call repeatedly and concurrently. */
  suspend fun awaitClosed() {
    closed.await()
  }

  /**
   * Starts terminal session cleanup as a root child rather than as work owned by the family being
   * closed. [CoroutineStart.UNDISPATCHED] transfers the callback synchronously until its first
   * suspension, so the caller cannot outrun the cleanup's family-generation invalidation. The
   * detached root child can then wait for the triggering family child to finish without self-join.
   */
  internal fun launchTerminalCleanup(cleanup: suspend () -> Unit) {
    rootScope.launch(start = CoroutineStart.UNDISPATCHED) {
      try {
        // Token/cache cleanup and the terminal Redux reset are security boundaries. Once admitted,
        // runtime or family cancellation must not interrupt them.
        withContext(NonCancellable) { cleanup() }
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        // AuthEngine already attempts every reset step and rethrows the first failure. The detached
        // supervisor child has no engine caller to receive it, so report it without crashing an
        // unrelated runtime sibling or repeating destructive cleanup.
        Log.e("runtime", error) { "terminal session cleanup failed" }
      }
    }
  }

  private suspend fun <T> runLifecycle(block: suspend () -> T): T =
    rootScope.async { lifecycleMutex.withLock { block() } }.await()

  private fun startAuthChild() {
    val owner = SupervisorJob(rootJob)
    val scope = CoroutineScope(rootScope.coroutineContext + owner)
    synchronized(stateGate) {
      authJob?.cancel()
      authJob = owner
    }
    scope.launch { restoreAuth(scope, publication) }
  }

  private fun ensureFamilyScope(): CoroutineScope {
    val current = synchronized(stateGate) { familyJob }
    return if (current?.isActive == true) {
      CoroutineScope(rootScope.coroutineContext + current)
    } else {
      newFamilyScope()
    }
  }

  private fun newFamilyScope(): CoroutineScope {
    val owner = SupervisorJob(rootJob)
    synchronized(stateGate) {
      check(familyJob == null || familyJob?.isActive == false) {
        "The previous family child must be joined before replacement"
      }
      familyJob = owner
    }
    return CoroutineScope(rootScope.coroutineContext + owner)
  }

  private suspend fun closeFamilyChild(invalidateGeneration: Boolean) {
    val previous = synchronized(stateGate) {
      val captured = (familyHandle to familyJob) to (activeFamily to familyPublication)
      familyHandle = null
      familyJob = null
      activeFamily = null
      familyPublication = null
      captured
    }

    // Admission closes first. Deselecting then invalidates the captured generation before any
    // cancelled/non-cooperative callback could race the subsequent cache wipe.
    previous.second.second?.close()
    previous.first.first?.closePublication()
    if (previous.second.first != null || previous.first.second != null || previous.first.first != null) {
      components.closeFamilyWorkAdmission()
    }
    if (invalidateGeneration) {
      previous.second.first?.let { context ->
        if (components.sessionCoordinator.isCurrent(context)) {
          components.sessionCoordinator.selectFamily(context.authContext, null)
        }
      }
    }
    previous.first.first?.cancel()
    previous.first.second?.cancel()
    previous.first.first?.awaitClosed()
    previous.first.second?.join()
  }

  private fun checkStarted() {
    check(
      lifecycleState == DayfoldRuntimeState.PAUSED ||
        lifecycleState == DayfoldRuntimeState.ACTIVE,
    ) { "DayfoldRuntime must be started before selecting a family" }
  }

  private fun setState(next: DayfoldRuntimeState) {
    synchronized(stateGate) { state = next }
  }

  private fun setStateIfOpen(next: DayfoldRuntimeState) {
    synchronized(stateGate) {
      if (state != DayfoldRuntimeState.CLOSING && state != DayfoldRuntimeState.CLOSED) state = next
    }
  }
}
