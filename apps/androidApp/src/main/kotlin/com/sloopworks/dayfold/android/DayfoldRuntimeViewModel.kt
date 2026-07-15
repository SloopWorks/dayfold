package com.sloopworks.dayfold.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sloopworks.dayfold.client.AppState
import com.sloopworks.dayfold.client.DayfoldCommands
import com.sloopworks.dayfold.client.DayfoldRuntimeGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.reduxkotlin.Store
import java.util.concurrent.atomic.AtomicBoolean

/** Application-safe runtime surface retained across Activity recreation. */
internal interface DayfoldRuntimeHandle {
  val store: Store<AppState>
  val commands: DayfoldCommands

  suspend fun start()
  suspend fun resume()
  suspend fun pause()
  fun cancel()
  suspend fun awaitClosed()
}

/** Adapts the common runtime graph without adding any Android UI dependency. */
internal class GraphDayfoldRuntimeHandle(
  private val graph: DayfoldRuntimeGraph,
) : DayfoldRuntimeHandle {
  override val store: Store<AppState> get() = graph.store
  override val commands: DayfoldCommands get() = graph.commands

  override suspend fun start() = graph.start()
  override suspend fun resume() = graph.resume()
  override suspend fun pause() = graph.pause()
  override fun cancel() = graph.cancel()
  override suspend fun awaitClosed() = graph.awaitClosed()
}

/** Runtime plus immutable host configuration created once per retained ViewModel. */
internal data class RetainedDayfoldRuntime(
  val handle: DayfoldRuntimeHandle,
  val isFakeBackend: Boolean,
  val beforeStart: suspend () -> Unit = {},
)

/** Injectable construction seam; implementations must capture application-safe dependencies only. */
internal fun interface RetainedDayfoldRuntimeFactory {
  fun create(): RetainedDayfoldRuntime
}

/**
 * Retains one Dayfold runtime across configuration changes without retaining an Activity or native
 * provider UI. Runtime closure is non-blocking on main; resource joining runs in an owned scope.
 */
internal class DayfoldRuntimeViewModel(
  factory: RetainedDayfoldRuntimeFactory,
  private val teardownScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : ViewModel() {
  /** Opaque Activity-generation token used to reject stale lifecycle callbacks. */
  internal class HostToken internal constructor(internal val generation: Long)

  private val retained = factory.create()
  private val runtime = retained.handle
  private val ownerLock = Any()
  private val lifecycleMutex = Mutex()
  private val firstHost = AtomicBoolean(true)
  private val startupLock = Any()
  private val closeLock = Any()
  private var nextGeneration = 0L
  private var latestAttachedGeneration = 0L
  private var activeGeneration: Long? = null
  private var startupJob: Deferred<Unit>? = null
  private var closeJob: Job? = null

  val store: Store<AppState> get() = runtime.store
  val commands: DayfoldCommands get() = runtime.commands
  val isFakeBackend: Boolean get() = retained.isFakeBackend

  /** Starts once after the Activity has synchronously restored its saved navigation state. */
  fun start(): Job = startup()

  private fun startup(): Deferred<Unit> = synchronized(startupLock) {
    startupJob ?: viewModelScope.async {
      retained.beforeStart()
      runtime.start()
    }.also { startupJob = it }
  }

  /** Allocates a new Activity generation without making it the foreground owner yet. */
  fun attachHost(): HostToken = synchronized(ownerLock) {
    nextGeneration += 1L
    latestAttachedGeneration = nextGeneration
    HostToken(nextGeneration)
  }

  /** True only for the first Activity attached to this retained graph, including process restore. */
  fun consumeInitialStateRestore(): Boolean = firstHost.compareAndSet(true, false)

  /** Resumes only the newest attached Activity and serializes the transition with stale pauses. */
  suspend fun resume(token: HostToken) = lifecycleMutex.withLock {
    val newest = synchronized(ownerLock) { token.generation == latestAttachedGeneration }
    if (!newest) return@withLock
    startup().await()
    runtime.resume()
    synchronized(ownerLock) { activeGeneration = token.generation }
  }

  /** Pauses only when [token] still owns the active foreground generation. */
  suspend fun pause(token: HostToken) = lifecycleMutex.withLock {
    val active = synchronized(ownerLock) { activeGeneration == token.generation }
    if (!active) return@withLock
    runtime.pause()
    synchronized(ownerLock) {
      if (activeGeneration == token.generation) activeGeneration = null
    }
  }

  /** Starts idempotent non-blocking cancellation and returns the owned resource-join job. */
  internal fun close(): Job = synchronized(closeLock) {
    closeJob?.let { return@synchronized it }
    runtime.cancel()
    val created = teardownScope.launch(start = CoroutineStart.LAZY) { runtime.awaitClosed() }
    closeJob = created
    created.invokeOnCompletion { teardownScope.cancel() }
    created.start()
    created
  }

  override fun onCleared() {
    close()
    super.onCleared()
  }

  /** ViewModelProvider adapter that does not retain [runtimeFactory] after construction. */
  internal class Factory(
    private val runtimeFactory: RetainedDayfoldRuntimeFactory,
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      require(modelClass.isAssignableFrom(DayfoldRuntimeViewModel::class.java)) {
        "Unsupported ViewModel ${modelClass.name}"
      }
      return DayfoldRuntimeViewModel(runtimeFactory) as T
    }
  }
}
