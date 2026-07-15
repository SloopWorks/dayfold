package com.sloopworks.dayfold.client

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.reduxkotlin.Store

/**
 * A cancellable group of content projection collectors.
 *
 * Cancellation closes the publication boundary synchronously, then cancels only this handle's
 * child job. It never cancels the scope supplied to [ContentBridge]. Call [awaitClosed] when the
 * database must not be wiped or replaced until every collector has finished.
 */
internal class ContentBridgeHandle internal constructor(
  private val publication: PublicationBoundary,
  private val job: Job,
) {
  private val open = atomic(true)

  /** Stops future publication and begins cancellation without blocking the caller. */
  fun cancel() {
    open.value = false
    publication.close()
    job.cancel()
  }

  /** Waits until every collector owned by this handle has stopped. */
  suspend fun awaitClosed() {
    job.join()
  }

  /** Closes publication, cancels the collectors, and waits for their completion. */
  suspend fun cancelAndJoin() {
    cancel()
    awaitClosed()
  }

  internal fun publish(block: () -> Unit): Boolean = publication.publish(block)

  internal fun closePublication() {
    open.value = false
    publication.close()
  }

  internal val isOpen: Boolean
    get() = open.value
}

/**
 * Projects SQLDelight flows into their single-writer Redux slices.
 *
 * Family content and device-local state have different lifetimes. [startFamily] returns a handle
 * for one exact family generation; callers must cancel and join it before wiping or replacing the
 * family database. [startDevice] is independent and remains active across family replacement.
 * The injected [scope] remains owned by the runtime.
 */
internal class ContentBridge(
  private val store: Store<AppState>,
  private val contentStore: ContentStore,
  private val sessionCoordinator: SessionCoordinator,
  private val scope: CoroutineScope,
  private val databaseDispatcher: CoroutineDispatcher,
  private val beforeFamilyPublicationCommit: () -> Unit = {},
) {
  private class FamilyKey(
    val identityEpoch: Long,
    val familyId: String,
    val familyRevision: Long,
  ) {
    fun matches(other: FamilyKey): Boolean =
      identityEpoch == other.identityEpoch &&
        familyId == other.familyId &&
        familyRevision == other.familyRevision
  }

  private class FamilySlot(
    val key: FamilyKey,
    val handle: ContentBridgeHandle,
  )

  private val gate = SynchronizedObject()
  private var familySlot: FamilySlot? = null
  private var deviceHandle: ContentBridgeHandle? = null

  /**
   * Starts the five family-owned projections for [context]. Repeating the call for the same active
   * generation is idempotent. A different generation must explicitly close and join the previous
   * handle first, preserving the required close -> join -> wipe -> restart ordering.
   */
  fun startFamily(context: FamilySessionContext): ContentBridgeHandle {
    val key = FamilyKey(
      identityEpoch = context.authContext.identityEpoch,
      familyId = context.familyId,
      familyRevision = context.familyRevision,
    )
    var started: ContentBridgeHandle? = null
    val current = sessionCoordinator.commitIfCurrent(context) {
      started = synchronized(gate) {
        familySlot?.let { active ->
          check(active.handle.isOpen) {
            "Close and join the active family content bridge before starting it again"
          }
          check(active.key.matches(key)) {
            "Close and join the active family content bridge before replacement"
          }
          return@synchronized active.handle
        }

        val handle = createHandle { collectorScope, created ->
          collectorScope.collectDistinct(contentStore.activeCardsFlow(databaseDispatcher)) { cards ->
            created.publishFamily(context, CardsLoaded(cards))
          }
          collectorScope.collectDistinct(contentStore.activeHubsFlow(databaseDispatcher)) { hubs ->
            created.publishFamily(context, HubsLoaded(hubs))
          }
          collectorScope.collectDistinct(contentStore.hiddenIdsFlow(databaseDispatcher)) { hidden ->
            created.publishFamily(context, HiddenLoaded(hidden))
          }
          collectorScope.collectDistinct(contentStore.nowContentFlow(databaseDispatcher)) { content ->
            created.publishFamily(context, NowContentLoaded(content))
          }
          collectorScope.collectDistinct(contentStore.surfacingFlow(databaseDispatcher)) { surfacing ->
            created.publishFamily(context, SurfacingLoaded(surfacing))
          }
        }
        familySlot = FamilySlot(key, handle)
        handle
      }
    }
    check(current) { "Cannot start a stale family content bridge" }
    return checkNotNull(started)
  }

  /** Starts the process/device-local notification-config projection idempotently. */
  fun startDevice(): ContentBridgeHandle = synchronized(gate) {
    deviceHandle?.let {
      check(it.isOpen) { "Close and join the device content bridge before starting it again" }
      return@synchronized it
    }
    val handle = createHandle { collectorScope, created ->
      collectorScope.collectDistinct(contentStore.notifConfigFlow(databaseDispatcher)) { config ->
        created.publish { store.dispatch(NotifConfigLoaded(config)) }
      }
    }
    deviceHandle = handle
    handle
  }

  private fun createHandle(
    startCollectors: (CoroutineScope, ContentBridgeHandle) -> Unit,
  ): ContentBridgeHandle {
    val publication = PublicationBoundary()
    val childJob = SupervisorJob(scope.coroutineContext[Job])
    val collectorScope = CoroutineScope(scope.coroutineContext + childJob)
    val handle = ContentBridgeHandle(publication, childJob)
    childJob.invokeOnCompletion {
      handle.closePublication()
      synchronized(gate) {
        if (familySlot?.handle === handle) familySlot = null
        if (deviceHandle === handle) deviceHandle = null
      }
    }
    startCollectors(collectorScope, handle)
    return handle
  }

  private fun ContentBridgeHandle.publishFamily(
    context: FamilySessionContext,
    action: Action,
  ) {
    publish {
      beforeFamilyPublicationCommit()
      sessionCoordinator.commitIfCurrent(context) {
        store.dispatch(action)
      }
    }
  }

  private fun <T> CoroutineScope.collectDistinct(
    flow: Flow<T>,
    publish: (T) -> Unit,
  ) {
    launch {
      flow.distinctUntilChanged().collect(publish)
    }
  }
}

internal class PublicationBoundary {
  private val gate = SynchronizedObject()
  private var open = true

  val isOpen: Boolean
    get() = synchronized(gate) { open }

  fun close() {
    synchronized(gate) { open = false }
  }

  fun publish(block: () -> Unit): Boolean = synchronized(gate) {
    if (!open) return@synchronized false
    block()
    true
  }
}
