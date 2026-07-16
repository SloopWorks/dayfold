package com.sloopworks.dayfold.client

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import org.reduxkotlin.Store

/**
 * Performs one offline-first sync pass for an exact family session context.
 *
 * A pass drains pending operations, calls the sync API, persists the response, and publishes
 * delta-only status while rejecting stale tenant work. [SyncCoordinator] owns request conflation,
 * polling, serialization, and cancellation; this engine owns neither UI lifecycle nor the source of
 * truth for synchronized content, which remains the database.
 */
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
  private val suppliedSessionCoordinator: SessionCoordinator? = null,
  private val databaseDispatcher: CoroutineDispatcher = Dispatchers.Default,
  private val onSessionInvalidated: suspend (FamilySessionContext, Boolean) -> Unit = { _, expired ->
    withContext(databaseDispatcher) { contentStore.wipe() }
    store.dispatch(if (expired) SessionExpired else SignedOut)
  },
) {
  private var bridgeJob: Job? = null
  private var hubBridgeJob: Job? = null
  private var hiddenBridgeJob: Job? = null
  private var nowContentBridgeJob: Job? = null
  private var surfacingBridgeJob: Job? = null
  private var notifConfigBridgeJob: Job? = null
  private val coordinatorGate = SynchronizedObject()
  private var attachedCoordinator: SyncCoordinator? = null
  private val statusGate = SynchronizedObject()
  private var statusOwner: FamilySessionContext? = null
  private val sessionCoordinator: SessionCoordinator = suppliedSessionCoordinator
    ?: SessionCoordinator(
      refreshScope = scope,
      refreshSession = { context ->
        val client = authClient ?: throw SyncHttpException(401)
        context.refreshWith(client::refresh)
      },
      commitRotation = { session ->
        tokenStore?.save(session)
        store.dispatch(SessionRotated(session))
      },
    ).also { coordinator ->
      store.state.session.session?.let { session ->
        val auth = coordinator.install(session)
        coordinator.selectFamily(auth, store.state.session.activeFamilyId)
      }
    }

  /**
   * Cold-start hydration: project the DB into the store. First emission = cached rows, zero network.
   * Not thread-safe — must be called from the main thread ([bridgeJob] guard is non-atomic).
   * The second bridge (hubBridgeJob) keeps state.hubs in sync with the DB — it is the ONLY
   * writer of state.hubs (one-writer-per-slice: no other path dispatches HubsLoaded).
   */
  fun start() {
    if (bridgeJob != null) return
    // Issue #283 — heal a cache written by an older content-model BEFORE the bridges collect it,
    // so stale rows (e.g. checklist items missing their ADR 0038 id → non-interactive) never flash
    // to the UI. A no-op unless the stored client-schema version is behind; when it heals it wipes
    // synced content + cursor → the first syncNow() below rebuilds from -∞.
    contentStore.reconcileSchemaVersion()
    bridgeJob = scope.launch {
      contentStore.activeCardsFlow().collect { store.dispatch(CardsLoaded(it)) }
    }
    hubBridgeJob = scope.launch {
      contentStore.activeHubsFlow().collect { store.dispatch(HubsLoaded(it)) }
    }
    // W5 hide (ADR 0038 §W5): the hidden-id set is DB-fed too — the sole writer of
    // state.hiddenIds. Local-only; nothing here is ever synced.
    hiddenBridgeJob = scope.launch {
      contentStore.hiddenIdsFlow().collect { store.dispatch(HiddenLoaded(it)) }
    }
    // ADR 0043 Phase A — the derived-lane candidate inputs + local-only engine state. Sole
    // writers of state.nowContent / state.surfacing; the nowFeed selector reads them at render.
    nowContentBridgeJob = scope.launch {
      contentStore.nowContentFlow().collect { store.dispatch(NowContentLoaded(it)) }
    }
    surfacingBridgeJob = scope.launch {
      contentStore.surfacingFlow().collect { store.dispatch(SurfacingLoaded(it)) }
    }
    // ADR 0044 Phase B — the device-local notif config is DB-fed too (sole writer of state.notifConfig).
    // Local-only; never synced. The OS-permission slices are bridged separately from the platform
    // controllers (NOT here — they are OS-owned, not DB-owned).
    notifConfigBridgeJob = scope.launch {
      contentStore.notifConfigFlow().collect { store.dispatch(NotifConfigLoaded(it)) }
    }
  }

  /**
   * Legacy host adapter: delegates foreground serialization and polling to [SyncCoordinator].
   * New runtime code owns and resumes its coordinator directly.
   */
  fun resume(ownerScope: CoroutineScope = scope) {
    coordinator().resume(ownerScope)
  }

  /** Legacy host adapter: stop polling while the bridge stays live. */
  fun pause() { coordinator().pause() }

  /** Requests a conflated pass through the attached runtime or legacy-host coordinator. */
  fun requestSync(reason: SyncReason): Boolean = coordinator().requestSync(reason)

  /** Attaches the runtime-owned coordinator used by all subsequent feature sync requests. */
  internal fun attachCoordinator(coordinator: SyncCoordinator) {
    synchronized(coordinatorGate) {
      check(attachedCoordinator == null || attachedCoordinator === coordinator) {
        "SyncEngine already has a different coordinator"
      }
      attachedCoordinator = coordinator
    }
  }

  /**
   * Performs one full captured-context pass: inbound pages then rebased outbox writes.
   * Production callers use [requestSync]; direct invocation is retained for deterministic tests.
   */
  internal suspend fun syncNow(
    reason: SyncReason = SyncReason.MANUAL_REFRESH,
    isConflatedRerun: Boolean = false,
  ) {
    val familyId = store.state.session.activeFamilyId ?: return
    val context = sessionCoordinator.familySnapshot(familyId) ?: return
    adoptStatusBoundary(context)
    var statusStarted = false
    val startStatus = {
      if (!statusStarted) statusStarted = publishStarted(context)
    }
    // Direct user refresh gives immediate feedback. Poll/resume/background work and conflated
    // reruns stay silent unless they discover a real delta or outbox operation.
    if (reason == SyncReason.MANUAL_REFRESH && !isConflatedRerun) startStatus()
    try {
      drain(context, startStatus)
      drainOutbox(context, startStatus) // ADR 0038 — push local member writes after pulling fresh remote
      Log.i("sync") { "sync succeeded" }
      if (statusStarted) publishSucceeded(context)
    } catch (e: SyncHttpException) {
      onSyncHttpError(context, e)
    } catch (e: AuthHttpException) {
      if (e.status == 401 && sessionCoordinator.isCurrent(context)) onSessionInvalidated(context, true)
      else publishFailed(context, e.message ?: "sync error")
    } catch (e: CancellationException) {
      if (statusStarted) publishStopped(context)
      throw e
    } catch (e: Exception) {
      publishFailed(context, e.message ?: "sync error")
    }
  }

  /** Drain all /sync pages into the DB in order (each page is its own atomic applyDelta). */
  private suspend fun drain(
    context: FamilySessionContext,
    onActivity: () -> Unit,
  ) {
    var hasMore = true
    while (hasMore) {
      val cursor = withContext(databaseDispatcher) { contentStore.cursor() }
      val resp = sessionCoordinator.authorizedCall(context) { current ->
        current.withFamilyAndAccessToken { familyId, accessToken ->
          syncClient.fetchPage(familyId, accessToken, cursor)
        }
      }
      if (resp.hasMaterialChanges()) onActivity()
      // ADR 0040 §3 — stale-cursor directive: the server reset the scan to -∞ because our cursor
      // was older than the tombstone-retention floor (a needed delete may be GC'd). Wipe the
      // synced cache (keeping the outbox + hidden) before applying, so this page rebuilds clean.
      // Only the first rebuild page carries the flag; subsequent pages resume from a fresh cursor.
      val committed = withContext(databaseDispatcher) {
        sessionCoordinator.commitIfCurrent(context) {
          if (resp.fullResync) contentStore.wipeForResync()
          contentStore.applyDelta(
            changedCards = resp.changes.cards,
            changedHubs = resp.changes.hubs,
            changedSections = resp.changes.sections,
            changedBlocks = resp.changes.blocks,
            tombstones = resp.tombstones,
            nextCursor = resp.nextCursor,
            nowIso = nowProvider(),
            changedPlaces = resp.changes.places,
          )
        }
      }
      if (!committed) throw CancellationException("Family session replaced")
      hasMore = resp.hasMore
    }
  }

  /**
   * Egress (ADR 0038 §6): drain the outbox FIFO, pushing each pending op via the
   * whole-block PUT. Runs in the same coordinator pass right after the inbound drain, so a
   * pending op is always re-based on the freshest remote before it is sent (a benign
   * 412 then converges). The OutboxSender state machine decides each op's fate:
   *   Acked   → store the version (the inbound echo later drops the row + clears 'pending')
   *   ReMerge → re-base from the just-merged local block and retry (bounded by the cap)
   *   Drop    → 410/404/4xx → remove the op
   *   Failed  → cap reached → park the block 'failed' (calm surface)
   *   Backoff → transient (401/5xx/network) → stop this pass; the next poll retries
   */
  private suspend fun drainOutbox(
    context: FamilySessionContext,
    onActivity: () -> Unit,
  ) {
    val recovered = withContext(databaseDispatcher) {
      sessionCoordinator.commitIfCurrent(context) { contentStore.recoverInflightOps() }
    }
    if (!recovered) throw CancellationException("Family session replaced")

    while (true) {
      val op = withContext(databaseDispatcher) {
        var claimed: OutboxOp? = null
        val committed = sessionCoordinator.commitIfCurrent(context) {
          claimed = contentStore.claimNextPendingOp()
        }
        if (!committed) throw CancellationException("Family session replaced")
        claimed
      } ?: return
      onActivity()
      val result = try {
        // ADR 0038 §W4 — dispatch by op type: a "delete" op is a DELETE (no body/If-Match);
        // every other op (toggle, future upsert) is a whole-block PUT.
        sessionCoordinator.authorizedCall(context) { current ->
          current.withFamilyAndAccessToken { familyId, accessToken ->
            val sent = if (op.type == "delete") {
              syncClient.deleteBlock(familyId, accessToken, op.targetId, op.opId)
            } else {
              syncClient.putBlock(
                familyId, accessToken, op.targetId, op.payload, op.baseVersion, op.opId,
              )
            }
            if (sent.status == 401) throw SyncHttpException(401)
            sent
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: AuthHttpException) {
        throw e
      } catch (e: SyncHttpException) {
        throw e
      } catch (e: Exception) {
        PutResult(null, null) // transport/network error → transient
      }
      val shouldReturn = withContext(databaseDispatcher) {
        var stop = false
        val committed = sessionCoordinator.commitIfCurrent(context) {
          when (OutboxSender.classify(result.status, op.attempts.toInt())) {
            SendOutcome.Acked -> stop = contentStore.ackOpAndAdvanceSuccessor(
              opId = op.opId,
              targetId = op.targetId,
              resultVersion = result.version,
              nowIso = nowProvider(),
            )
            SendOutcome.ReMerge -> contentStore.rebaseOpFromLocal(op.opId, op.targetId, nowProvider())
            SendOutcome.Drop -> contentStore.dropOp(op.opId, op.targetId)
            SendOutcome.Failed -> contentStore.failOp(op.opId, op.targetId)
            is SendOutcome.Backoff -> { contentStore.bumpOpAttempt(op.opId); stop = true }
          }
        }
        if (!committed) throw CancellationException("Family session replaced")
        stop
      }
      if (shouldReturn) return
    }
  }

  // ADR 0030 (round-1 P0-2): 403 (removed) / 404 (non-member) = tenancy revocation →
  // the cache is forbidden content; wipe it + sign out. A rejected refresh expires the
  // identity globally; other statuses surface as normal, non-destructive failures.
  private suspend fun onSyncHttpError(context: FamilySessionContext, e: SyncHttpException) {
    if (!sessionCoordinator.isCurrent(context)) return
    if (e.status == 403 || e.status == 404) {
      onSessionInvalidated(context, false)
    } else {
      Log.w("sync") { "failed: HTTP ${e.status}" }
      publishFailed(context, "HTTP ${e.status}")
    }
  }

  private fun SyncResponse.hasMaterialChanges(): Boolean =
    fullResync ||
      changes.cards.isNotEmpty() ||
      changes.hubs.isNotEmpty() ||
      changes.sections.isNotEmpty() ||
      changes.blocks.isNotEmpty() ||
      changes.places.isNotEmpty() ||
      tombstones.isNotEmpty()

  /** Clears a prior family generation's busy flag only from a currently admitted family pass. */
  private fun adoptStatusBoundary(context: FamilySessionContext) {
    sessionCoordinator.commitIfCurrent(context) {
      synchronized(statusGate) {
        val owner = statusOwner
        val stale = owner != null && !owner.sameBoundary(context) && store.state.content.syncing
        if (stale) {
          statusOwner = null
          store.dispatch(SyncStopped)
        }
      }
    }
  }

  private fun publishStarted(context: FamilySessionContext): Boolean {
    var admitted = false
    sessionCoordinator.commitIfCurrent(context) {
      synchronized(statusGate) {
        statusOwner = context
        val current = store.state
        if (!current.content.syncing || current.content.error != null) store.dispatch(SyncStarted)
        admitted = true
      }
    }
    return admitted
  }

  private fun publishSucceeded(context: FamilySessionContext) {
    sessionCoordinator.commitIfCurrent(context) {
      synchronized(statusGate) {
        val owns = statusOwner?.sameBoundary(context) == true
        if (owns) {
          statusOwner = null
          if (store.state.content.syncing || store.state.content.error != null) store.dispatch(SyncSucceeded)
        }
      }
    }
  }

  private fun publishStopped(context: FamilySessionContext) {
    // Cancellation commonly follows family invalidation, so this neutral cleanup must not require
    // the session context to remain current. Ownership correlation is the fence: an old pass cannot
    // clear a newer family's status after that family has installed itself as [statusOwner].
    synchronized(statusGate) {
      if (statusOwner?.sameBoundary(context) == true) {
        statusOwner = null
        if (store.state.content.syncing) store.dispatch(SyncStopped)
      }
    }
  }

  private fun publishFailed(context: FamilySessionContext, message: String) {
    sessionCoordinator.commitIfCurrent(context) {
      synchronized(statusGate) {
        if (statusOwner?.sameBoundary(context) == true) statusOwner = null
        val current = store.state
        if (current.content.syncing || current.content.error != message) store.dispatch(SyncFailed(message))
      }
    }
  }

  private fun FamilySessionContext.sameBoundary(other: FamilySessionContext): Boolean =
    authContext.identityEpoch == other.authContext.identityEpoch &&
      familyId == other.familyId &&
      familyRevision == other.familyRevision

  fun stop() {
    bridgeJob?.cancel(); bridgeJob = null
    hubBridgeJob?.cancel(); hubBridgeJob = null
    hiddenBridgeJob?.cancel(); hiddenBridgeJob = null
    nowContentBridgeJob?.cancel(); nowContentBridgeJob = null
    surfacingBridgeJob?.cancel(); surfacingBridgeJob = null
    notifConfigBridgeJob?.cancel(); notifConfigBridgeJob = null
    synchronized(coordinatorGate) {
      attachedCoordinator.also { attachedCoordinator = null }
    }?.close()
  }

  private fun coordinator(): SyncCoordinator = synchronized(coordinatorGate) {
    attachedCoordinator ?: SyncCoordinator(
      syncEngine = this,
      pollIntervalMs = pollIntervalMs,
    ).also { attachedCoordinator = it }
  }
}
