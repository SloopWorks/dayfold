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
   *
   * Returns whether the pass actually synced. False when there is nothing to sync (no family bound,
   * or its session snapshot is gone) and when the pass failed — a headless background wake reports
   * that verbatim in its one log line (`RefreshOutcome.synced`), so "the call returned" must not be
   * mistaken for "content was refreshed".
   */
  internal suspend fun syncNow(
    reason: SyncReason = SyncReason.MANUAL_REFRESH,
    isConflatedRerun: Boolean = false,
  ): Boolean {
    val familyId = store.state.session.activeFamilyId ?: return false
    val context = sessionCoordinator.familySnapshot(familyId) ?: return false
    adoptStatusBoundary(context)
    var statusStarted = false
    val startStatus = {
      if (!statusStarted) statusStarted = publishStarted(context)
    }
    // Direct user refresh gives immediate feedback. Poll/resume/background work and conflated
    // reruns stay silent unless they discover a real delta or outbox operation.
    if (reason == SyncReason.MANUAL_REFRESH && !isConflatedRerun) startStatus()
    return try {
      drain(context, startStatus)
      drainOutbox(context, startStatus)
      Log.i("sync") { "sync succeeded" }
      if (statusStarted) publishSucceeded(context)
      true
    } catch (e: SyncHttpException) {
      onSyncHttpError(context, e)
      false
    } catch (e: AuthHttpException) {
      if (e.status == 401 && sessionCoordinator.isCurrent(context)) onSessionInvalidated(context, true)
      else publishFailed(context, e.message ?: "sync error")
      false
    } catch (e: CancellationException) {
      if (statusStarted) publishStopped(context)
      throw e
    } catch (e: Exception) {
      publishFailed(context, e.message ?: "sync error")
      false
    }
  }

  /** Drain all /sync pages into the DB in order (each page is its own atomic applyDelta).
   *  The loop itself lives in [SyncDrainer] so the background pass reuses it (ADR 0020 R3). */
  private suspend fun drain(
    context: FamilySessionContext,
    onActivity: () -> Unit,
  ) {
    SyncDrainer(
      contentStore = contentStore,
      databaseDispatcher = databaseDispatcher,
      nowIso = nowProvider,
      fetch = { since ->
        sessionCoordinator.authorizedCall(context) { current ->
          current.withFamilyAndAccessToken { familyId, accessToken ->
            syncClient.fetchPage(familyId, accessToken, since)
          }
        }
      },
      commit = { block -> sessionCoordinator.commitIfCurrent(context) { block() } },
      onActivity = onActivity,
    ).drain()
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
            // ADR 0064 — dispatch on targetKind FIRST: a response op goes to the response
            // endpoints, which take no If-Match. Falling through to the block path would PUT
            // a rule at /blocks/<ruleId> and 404 into a silent Drop.
            val sent = when {
              op.targetKind == "response" && op.type == "delete" ->
                syncClient.deleteResponse(familyId, accessToken, op.targetId, op.opId)
              op.targetKind == "response" ->
                syncClient.putResponse(familyId, accessToken, op.targetId, op.payload, op.opId)
              // CAL-10 (ADR 0063 §6, import contract spec §3.1) — the two-branch addition for the
              // Calendar→Dayfold import's hub/section ops. base_version is always null here (a
              // client-minted, not-yet-existing row — spec §3.4), matching op.baseVersion already.
              op.targetKind == "hub" ->
                syncClient.putHub(familyId, accessToken, op.targetId, op.payload, op.baseVersion, op.opId)
              op.targetKind == "section" ->
                syncClient.putSection(familyId, accessToken, op.targetId, op.payload, op.baseVersion, op.opId)
              op.targetKind == "card" ->
                syncClient.putCard(familyId, accessToken, op.targetId, op.payload, op.opId)
              op.type == "delete" ->
                syncClient.deleteBlock(familyId, accessToken, op.targetId, op.opId)
              else ->
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
        // CAL-10 — the import's own op types have no toggle successor to advance and no local
        // block row to re-merge (a brand-new client-minted row): ack/re-merge just record the
        // outbox row's own state, leaving the import engine's poll (outboxOpStates) to observe it.
        val isImportOp = op.type == "upsertHub" || op.type == "upsertSection" || op.type == "upsertBlock"
        val committed = sessionCoordinator.commitIfCurrent(context) {
          when (OutboxSender.classify(result.status, op.attempts.toInt())) {
            SendOutcome.Acked -> stop = when {
              isImportOp -> {
                contentStore.ackCalendarImportOp(op, result.version, nowProvider()); false
              }
              op.targetKind == "response" -> {
                contentStore.ackResponseOp(
                  opId = op.opId,
                  targetId = op.targetId,
                  resultVersion = result.version,
                  isDelete = op.type == "delete" || result.status == 204,
                ); false
              }
              op.targetKind == "hub" || op.targetKind == "card" || op.targetKind == "section" -> {
                contentStore.ackOp(op.opId, result.version); false
              }
              else -> contentStore.ackOpAndAdvanceSuccessor(
                opId = op.opId,
                targetId = op.targetId,
                resultVersion = result.version,
                nowIso = nowProvider(),
              )
            }
            SendOutcome.ReMerge -> if (isImportOp) {
              contentStore.failOp(op.opId, op.targetId); contentStore.cascadeDropDependents(op.opId)
            } else {
              contentStore.rebaseOpFromLocal(op.opId, op.targetId, nowProvider())
            }
            SendOutcome.Drop -> {
              if (op.targetKind == "response") {
                val canonical = result.canonicalResponse
                when {
                  result.problemType == "subject-already-done" && canonical != null -> {
                    contentStore.resolveResponseConflict(op.opId, op.targetId, canonical)
                    contentStore.cascadeDropDependents(op.opId)
                  }
                  // Compatibility during a rolling API deployment: an older server may return
                  // the typed conflict without the canonical row. Keep the optimistic suppressor
                  // and retry later instead of exposing the completed subject or showing a false
                  // byline. The new server resolves this branch on the next attempt.
                  result.problemType == "subject-already-done" -> {
                    contentStore.deferResponseConflict(
                      op.opId,
                      op.targetId,
                      giveUp = op.attempts.toInt() + 1 >= OutboxSender.MAX_ATTEMPTS,
                    )
                    stop = true
                  }
                  else -> {
                    contentStore.rejectResponseOp(
                      op.opId, op.targetId, op.type == "delete", failed = false,
                      // A response DELETE 404 means the subject or private row is no longer
                      // readable. Restoring its captured payload would re-expose tenant data.
                      rollbackPayload = op.payload.takeUnless { op.type == "delete" && result.status == 404 },
                    )
                    contentStore.cascadeDropDependents(op.opId)
                  }
                }
                publishResponseRejection(op, result)
              } else {
                contentStore.dropOp(op.opId, op.targetId)
                contentStore.cascadeDropDependents(op.opId)
              }
            }
            SendOutcome.Failed -> {
              if (op.targetKind == "response") {
                contentStore.rejectResponseOp(
                  op.opId, op.targetId, op.type == "delete", failed = true,
                  rollbackPayload = op.payload,
                )
                publishResponseRejection(op, result)
              } else contentStore.failOp(op.opId, op.targetId)
              contentStore.cascadeDropDependents(op.opId)
            }
            is SendOutcome.Backoff -> { contentStore.bumpOpAttempt(op.opId); stop = true }
          }
        }
        if (!committed) throw CancellationException("Family session replaced")
        stop
      }
      if (shouldReturn) return
    }
  }

  /** Replace an optimistic success receipt when the server terminally rejects that response. */
  private fun publishResponseRejection(op: OutboxOp, result: PutResult) {
    val current = store.state.responses.lastReceipt
    // Do not overwrite feedback for a newer response; a dismissed or matching receipt can be
    // safely replaced with the authoritative outcome.
    if (current != null && current.responseId != op.targetId) return
    val isDone = op.payload.contains("\"kind\":\"done\"")
    val message = when {
      result.problemType == "subject-already-done" -> "Already marked done"
      op.type == "delete" -> "Couldn't remove response"
      isDone -> "Couldn't mark done"
      else -> "Couldn't save response"
    }
    store.dispatch(
      ResponseReceiptShown(
        ResponseReceipt(
          responseId = op.targetId,
          message = message,
          undoable = false,
          offline = false,
        ),
      ),
    )
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
