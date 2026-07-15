package com.sloopworks.dayfold.client

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.reduxkotlin.Store

// Orchestrates the Hubs surface (ADR 0006). PR2: openHub is now DB-fed — it dispatches
// OpenHub, triggers a background sync, and subscribes to contentStore.hubTreeFlow(hubId)
// dispatching HubTreeLoaded whenever the DB delivers tree rows. Removes the direct
// hubTree network call; keeps HubClient for audience(). Mutex-guarded like AuthEngine.
class HubEngine(
  private val store: Store<AppState>,
  private val hubClient: HubClient,
  private val authClient: AuthClient,
  private val tokenStore: TokenStore,
  private val contentStore: ContentStore,
  private val syncEngine: SyncEngine,
  private val hubTreeFlowProvider: (String) -> Flow<HubTree?> = { hubId ->
    contentStore.hubTreeFlow(hubId)
  },
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
  // Seams (Slice 4) — injectable so the toggle path is testable without the wall clock/RNG.
  private val nowProvider: () -> String = { kotlin.time.Clock.System.now().toString() },
  private val idProvider: () -> String = { Ulid.next() },
  // ContentStore mutation methods are synchronous; commands hop to this KMP-safe dispatcher
  // after capturing their session/clock/id context at the caller edge.
  private val databaseDispatcher: CoroutineDispatcher = Dispatchers.Default,
  private val suppliedSessionCoordinator: SessionCoordinator? = null,
  private val onSessionExpired: suspend (FamilySessionContext) -> Unit = {},
) {
  // Tree lifecycle, audience reads, and audience writes have independent serialization.
  // In particular, closeHub never enters either network mutex and cannot queue behind a request.
  private val treeMutex = Mutex()
  private val audienceLoadMutex = Mutex()
  private val audienceMutationMutex = Mutex()
  private val treeJob = atomic<Job?>(null)
  private val treeHubId = atomic<String?>(null)
  private val treeRequest = atomic<HubRequestKey?>(null)
  private val audienceLoadJob = atomic<Job?>(null)
  private val audienceMutationJob = atomic<Job?>(null)
  private val requestCounter = atomic(0L)
  private val fallbackAdmissionOpen = atomic(true)
  private val familyOwnerGate = SynchronizedObject()
  private var runtimeFamilyOwnership = false
  private var familyOwner: FamilyWorkOwner? = null
  private val sessionCoordinator: SessionCoordinator = suppliedSessionCoordinator
    ?: SessionCoordinator(
      refreshScope = scope,
      refreshSession = { context -> context.refreshWith(authClient::refresh) },
      commitRotation = { session ->
        tokenStore.save(session)
        store.dispatch(SessionRotated(session))
      },
    ).also { coordinator ->
      store.state.session?.let { session ->
        val auth = coordinator.install(session)
        coordinator.selectFamily(auth, store.state.activeFamilyId)
      }
    }

  private fun familyContext(): FamilySessionContext? =
    store.state.activeFamilyId?.let(sessionCoordinator::familySnapshot)

  private fun requestKey(context: FamilySessionContext): HubRequestKey = HubRequestKey(
    generation = HubTenantGeneration(
      identityEpoch = context.authContext.identityEpoch,
      familyRevision = context.familyRevision,
    ),
    requestId = requestCounter.incrementAndGet(),
  )

  private class FamilyWorkOwner(
    val identityEpoch: Long,
    val familyId: String,
    val familyRevision: Long,
    val scope: CoroutineScope,
    val publication: PublicationBoundary?,
  ) {
    fun matches(context: FamilySessionContext): Boolean =
      identityEpoch == context.authContext.identityEpoch &&
        familyId == context.familyId &&
        familyRevision == context.familyRevision
  }

  /** Binds Hub jobs to the runtime's current replaceable family child. */
  internal fun bindFamilyWork(
    context: FamilySessionContext,
    ownerScope: CoroutineScope,
    publication: PublicationBoundary,
  ) {
    check(sessionCoordinator.isCurrent(context)) { "Cannot bind stale Hub family work" }
    synchronized(familyOwnerGate) {
      check(familyOwner == null) { "Close the previous Hub family admission before replacement" }
      runtimeFamilyOwnership = true
      familyOwner = FamilyWorkOwner(
        identityEpoch = context.authContext.identityEpoch,
        familyId = context.familyId,
        familyRevision = context.familyRevision,
        scope = ownerScope,
        publication = publication,
      )
    }
  }

  /** Closes runtime Hub admission synchronously before the family child is cancelled and joined. */
  internal fun closeFamilyAdmission() {
    synchronized(familyOwnerGate) {
      runtimeFamilyOwnership = true
      familyOwner = null
    }
    treeHubId.value = null
    treeRequest.value = null
    treeJob.getAndSet(null)?.cancel()
    cancelAudienceJobs()
  }

  private fun workOwner(context: FamilySessionContext): FamilyWorkOwner? =
    synchronized(familyOwnerGate) {
      if (!runtimeFamilyOwnership && fallbackAdmissionOpen.value) {
        FamilyWorkOwner(
          identityEpoch = context.authContext.identityEpoch,
          familyId = context.familyId,
          familyRevision = context.familyRevision,
          scope = scope,
          publication = null,
        )
      } else {
        familyOwner?.takeIf { it.matches(context) && it.publication?.isOpen == true }
      }
    }

  private fun commitIfAdmitted(
    owner: FamilyWorkOwner,
    context: FamilySessionContext,
    commit: () -> Unit,
  ): Boolean = synchronized(familyOwnerGate) {
    val publication = owner.publication
    val ownerIsAdmitted = if (runtimeFamilyOwnership) {
      familyOwner === owner && publication?.isOpen == true
    } else {
      fallbackAdmissionOpen.value && publication == null
    }
    if (!ownerIsAdmitted) return@synchronized false

    var committed = false
    val publish = {
      committed = sessionCoordinator.commitIfCurrent(context, commit)
    }
    if (publication == null) publish() else publication.publish(publish)
    committed
  }

  // Slice 4 (ADR 0038 §5.4) — a member checklist toggle. The optimistic apply + outbox
  // enqueue is one atomic ContentStore call; we then kick a sync so the egress drains
  // promptly (the 45s poll would otherwise carry it). doneBy = the acting member (the
  // LWW tiebreak + "✓ Mom" byline); the stamp is the merge clock for un-check too.
  suspend fun toggleItem(blockId: String, itemId: String, done: Boolean) {
    val context = familyContext() ?: return
    toggleItem(context, blockId, itemId, done)
  }

  internal suspend fun toggleItem(
    context: FamilySessionContext,
    blockId: String,
    itemId: String,
    done: Boolean,
  ) {
    val doneBy = context.actorUserId()
    val nowIso = nowProvider()
    val opId = idProvider()
    val committed = withContext(databaseDispatcher) {
      sessionCoordinator.commitIfCurrent(context) {
        contentStore.enqueueBlockToggle(blockId, itemId, done, doneBy, nowIso, opId)
      }
    }
    if (committed) syncEngine.requestSync(SyncReason.OUTBOX_MUTATION)
  }

  // Slice 5b (ADR 0038 §W4) — an author-gated member delete. The optimistic mark ("Removing…")
  // + outbox enqueue is one atomic ContentStore call; we then kick a sync so the DELETE drains
  // promptly. The row is removed only when the inbound /sync tombstone confirms. The author gate
  // (createdBy == userId) lives in the UI (the option is absent for non-authors); the server
  // re-checks (403 non-author / no scope → the op drops).
  suspend fun deleteBlock(blockId: String) {
    val context = familyContext() ?: return
    deleteBlock(context, blockId)
  }

  internal suspend fun deleteBlock(context: FamilySessionContext, blockId: String) {
    val nowIso = nowProvider()
    val opId = idProvider()
    val committed = withContext(databaseDispatcher) {
      sessionCoordinator.commitIfCurrent(context) { contentStore.enqueueBlockDelete(blockId, nowIso, opId) }
    }
    if (committed) syncEngine.requestSync(SyncReason.OUTBOX_MUTATION)
  }

  // Slice 4 — manual Retry of a block parked 'failed': re-arm its op(s) + kick a sync.
  suspend fun retryBlock(blockId: String) {
    val context = familyContext() ?: return
    retryBlock(context, blockId)
  }

  internal suspend fun retryBlock(context: FamilySessionContext, blockId: String) {
    val committed = withContext(databaseDispatcher) {
      sessionCoordinator.commitIfCurrent(context) { contentStore.retryBlock(blockId) }
    }
    if (committed) syncEngine.requestSync(SyncReason.OUTBOX_MUTATION)
  }

  // Slice 5b (ADR 0038 §W5) — hide is LOCAL-ONLY + personal + reversible. No sync, no outbox,
  // no family-visible signal. The DB write re-emits via the hidden bridge → the view re-partitions.
  suspend fun hideBlock(blockId: String) {
    val context = familyContext() ?: return
    hideBlock(context, blockId)
  }

  internal suspend fun hideBlock(context: FamilySessionContext, blockId: String) {
    val nowIso = nowProvider()
    withContext(databaseDispatcher) {
      sessionCoordinator.commitIfCurrent(context) { contentStore.hide(blockId, nowIso) }
    }
  }

  suspend fun unhideBlock(blockId: String) {
    val context = familyContext() ?: return
    unhideBlock(context, blockId)
  }

  internal suspend fun unhideBlock(context: FamilySessionContext, blockId: String) {
    withContext(databaseDispatcher) {
      sessionCoordinator.commitIfCurrent(context) { contentStore.unhide(blockId) }
    }
  }

  // PR1: the hub LIST is now DB-fed via the SyncEngine hub bridge — this method is a
  // no-op. The bridge (SyncEngine.hubBridgeJob) is the sole writer of state.hubs via
  // HubsLoaded. Callers (shells' onLoadHubs) should request a manual sync instead.
  suspend fun loadHubs() = Unit

  // DB-fed openHub. The collector captures one exact family generation and one request key;
  // reducer correlation is the final guard even if a cancelled/non-cooperative flow emits late.
  suspend fun openHub(
    hubId: String,
    focusBlockId: String? = null,
    returnDestination: HubReturnDestination = HubReturnDestination.HUB_LIST,
  ) {
    val context = familyContext() ?: return
    openHub(context, hubId, focusBlockId, returnDestination)
  }

  internal suspend fun openHub(
    context: FamilySessionContext,
    hubId: String,
    focusBlockId: String? = null,
    returnDestination: HubReturnDestination = HubReturnDestination.HUB_LIST,
    onAdmitted: () -> Unit = {},
  ): Boolean = treeMutex.withLock {
    val owner = workOwner(context) ?: return@withLock false
    val request = requestKey(context)
    Log.i("hub") { "hub opened id=$hubId" }

    // Close old network admission immediately. Neither cancel waits for a transport to finish.
    cancelAudienceJobs()
    // Do not wait for a cancellation-resistant old collector before admitting the new hub.
    // Its request key can no longer reduce once OpenHub below installs the replacement key.
    treeJob.getAndSet(null)?.cancel()
    treeHubId.value = null
    treeRequest.value = null

    // Navigation + focus are one admitted commit. If family replacement closed admission or
    // invalidated the generation, no request action lands and no collector is created.
    val admitted = commitIfAdmitted(owner, context) {
      store.dispatch(OpenHub(hubId, request, focusBlockId, returnDestination))
    }
    if (!admitted) return@withLock false
    try {
      // The callback cannot influence admission: navigation is already committed and the
      // family-owner gate has been released. A broken platform acknowledgement must not make the
      // coordinator retry an action that Redux has accepted.
      onAdmitted()
    } catch (error: Exception) {
      Log.e("hub", error) { "external Hub acknowledgement failed" }
    }
    syncEngine.requestSync(SyncReason.MANUAL_REFRESH)

    val collector = owner.scope.launch(start = CoroutineStart.LAZY) {
      hubTreeFlowProvider(hubId).collect { tree ->
        if (tree != null) {
          commitIfAdmitted(owner, context) {
            store.dispatch(HubTreeLoaded(hubId, request, tree))
          }
        }
        // null = hub not in cache yet (or tombstoned); hubsBusy stays true until tree arrives
      }
    }
    treeJob.value = collector
    treeHubId.value = hubId
    treeRequest.value = request
    collector.start()
    true
  }

  /**
   * Cancels low-level Hub work without dispatching navigation.
   *
   * The command/UI layer owns the single CloseHub/CloseHubToFeed action. Audience jobs are
   * cancelled but deliberately not joined, so a slow or cancellation-resistant network request
   * cannot delay navigation. Its correlated result is reducer-rejected after the close action.
   */
  suspend fun closeHub(
    expectedHubId: String? = null,
    expectedRequest: HubRequestKey? = null,
  ) {
    treeMutex.withLock {
      val idMatches = expectedHubId == null || treeHubId.value == expectedHubId
      val requestMatches = expectedRequest == null || treeRequest.value == expectedRequest
      if (idMatches && requestMatches) {
        // Audience work belongs to the same open request. A delayed cleanup for A must not cancel
        // an audience load or mutation started after B replaced it.
        cancelAudienceJobs()
        treeHubId.value = null
        treeRequest.value = null
        treeJob.getAndSet(null)?.cancelAndJoin()
      }
    }
  }

  /**
   * Cancels and joins Hub family work for legacy hosts that do not yet use [DayfoldRuntime].
   *
   * Admission is closed only for the duration of this call, so a terminal cleanup can prove all
   * tree/audience work stopped before wiping tenant data without permanently disabling a reused
   * legacy engine. This method performs no Redux navigation or terminal-state dispatch.
   */
  suspend fun cancelFamilyWork() {
    val legacy = synchronized(familyOwnerGate) {
      (!runtimeFamilyOwnership).also { isLegacy ->
        if (isLegacy) fallbackAdmissionOpen.value = false
      }
    }
    val cancelled = mutableListOf<Job>()
    fun detach(reference: kotlinx.atomicfu.AtomicRef<Job?>) {
      reference.getAndSet(null)?.let { job ->
        job.cancel()
        cancelled += job
      }
    }

    try {
      treeMutex.withLock { treeHubId.value = null; treeRequest.value = null; detach(treeJob) }
      audienceLoadMutex.withLock { detach(audienceLoadJob) }

      // The mutation mutex serializes the full mutate+reload operation. Cancel any job it admits
      // while waiting to acquire the now-closed admission, then take/release the mutex to prove no
      // starter remains between admission and job registration.
      while (!audienceMutationMutex.tryLock()) {
        detach(audienceMutationJob)
        yield()
      }
      try {
        detach(audienceMutationJob)
      } finally {
        audienceMutationMutex.unlock()
      }
      cancelled.joinAll()
    } finally {
      if (legacy) {
        synchronized(familyOwnerGate) { fallbackAdmissionOpen.value = true }
      }
    }
  }

  suspend fun loadAudience(hubId: String) {
    val context = familyContext() ?: return
    loadAudience(context, hubId)
  }

  internal suspend fun loadAudience(context: FamilySessionContext, hubId: String) {
    val owner = workOwner(context) ?: return
    val request = requestKey(context)
    if (!admitAudienceRequest(owner, context, hubId, request)) return

    val job = audienceLoadMutex.withLock {
      audienceLoadJob.getAndSet(null)?.cancel()
      owner.scope.launch(start = CoroutineStart.LAZY) {
        try {
          val audience = authorized(context) { access, familyId ->
            hubClient.audience(access, familyId, hubId)
          }
          publishAudience(owner, context, hubId, request, audience)
        } catch (error: CancellationException) {
          throw error
        } catch (error: AuthHttpException) {
          if (error.status == 401 && sessionCoordinator.isCurrent(context)) {
            onSessionExpired(context)
          } else {
            publishAudienceFailure(owner, context, hubId, request, LOAD_AUDIENCE_ERROR)
          }
        } catch (_: Exception) {
          publishAudienceFailure(owner, context, hubId, request, LOAD_AUDIENCE_ERROR)
        }
      }.also { started ->
        audienceLoadJob.value = started
        started.start()
      }
    }
    job.join()
    audienceLoadJob.compareAndSet(job, null)
  }

  // ADR 0053 DC4 — participant/visibility management (the People sheet, DC5 builds
  // the UI on top). All three ops share one shape: mutate, then RELOAD the audience
  // on success (server is truth — no separate optimistic apply/reconcile needed,
  // the reload's HubAudienceLoaded is itself the "updated" dispatch) or dispatch
  // HubManageFailed on error. Serialized with the audience-management mutex.

  /** Owner/co_owner sets a member's allow-list role (viewer|contributor|co_owner). */
  suspend fun setParticipant(hubId: String, uid: String, role: String) {
    val context = familyContext() ?: return
    setParticipant(context, hubId, uid, role)
  }

  internal suspend fun setParticipant(
    context: FamilySessionContext,
    hubId: String,
    uid: String,
    role: String,
  ) = mutateAudience(context, hubId, "Couldn't update that person's access. Try again.") {
    authorized(it) { access, familyId -> hubClient.setParticipant(access, familyId, hubId, uid, role) }
  }

  /** Owner/co_owner drops a member's allow-list row (author's row is immutable). */
  suspend fun removeParticipant(hubId: String, uid: String) {
    val context = familyContext() ?: return
    removeParticipant(context, hubId, uid)
  }

  internal suspend fun removeParticipant(context: FamilySessionContext, hubId: String, uid: String) =
    mutateAudience(context, hubId, "Couldn't remove that person. Try again.") {
      authorized(it) { access, familyId -> hubClient.removeParticipant(access, familyId, hubId, uid) }
    }

  /** Owner/co_owner flips the hub's visibility (family<->restricted). */
  suspend fun setVisibility(hubId: String, visibility: String) {
    val context = familyContext() ?: return
    setVisibility(context, hubId, visibility)
  }

  internal suspend fun setVisibility(
    context: FamilySessionContext,
    hubId: String,
    visibility: String,
  ) = mutateAudience(context, hubId, "Couldn't update who can see this. Try again.") {
    authorized(it) { access, familyId -> hubClient.setVisibility(access, familyId, hubId, visibility) }
    Log.i("hub") { "visibility updated" }
  }

  // Re-fetch + dispatch under the same captured family generation. The coordinator
  // substitutes a rotated credential revision without mixing tenant snapshots.
  private suspend fun reloadAudience(
    owner: FamilyWorkOwner,
    context: FamilySessionContext,
    hubId: String,
    request: HubRequestKey,
  ) {
    val audience = authorized(context) { access, familyId -> hubClient.audience(access, familyId, hubId) }
    publishAudience(owner, context, hubId, request, audience)
  }

  private suspend fun mutateAudience(
    context: FamilySessionContext,
    hubId: String,
    failureMessage: String,
    mutation: suspend (FamilySessionContext) -> Unit,
  ) {
    audienceMutationMutex.withLock {
      val owner = workOwner(context) ?: return@withLock
      val request = requestKey(context)
      if (!admitAudienceRequest(owner, context, hubId, request)) return@withLock

      val job = owner.scope.launch(start = CoroutineStart.LAZY) {
        try {
          mutation(context)
          reloadAudience(owner, context, hubId, request)
        } catch (error: CancellationException) {
          throw error
        } catch (error: AuthHttpException) {
          handleAuthorizedFailure(owner, context, hubId, request, error, failureMessage)
        } catch (error: Exception) {
          Log.w("hub", error) { "audience mutation failed" }
          publishManageFailure(owner, context, hubId, request, failureMessage)
        }
      }
      audienceMutationJob.value = job
      job.start()
      job.join()
      audienceMutationJob.compareAndSet(job, null)
    }
  }

  private fun admitAudienceRequest(
    owner: FamilyWorkOwner,
    context: FamilySessionContext,
    hubId: String,
    request: HubRequestKey,
  ): Boolean {
    var admitted = false
    commitIfAdmitted(owner, context) {
      store.dispatch(HubAudienceRequested(hubId, request))
      admitted = store.state.currentHubAudienceRequest == request
    }
    return admitted
  }

  private fun publishAudience(
    owner: FamilyWorkOwner,
    context: FamilySessionContext,
    hubId: String,
    request: HubRequestKey,
    audience: HubAudience,
  ) {
    commitIfAdmitted(owner, context) {
      store.dispatch(HubAudienceLoaded(hubId, request, audience))
    }
  }

  private fun publishAudienceFailure(
    owner: FamilyWorkOwner,
    context: FamilySessionContext,
    hubId: String,
    request: HubRequestKey,
    message: String,
  ) {
    commitIfAdmitted(owner, context) {
      store.dispatch(AudienceFailed(hubId, request, message))
    }
  }

  private fun publishManageFailure(
    owner: FamilyWorkOwner,
    context: FamilySessionContext,
    hubId: String,
    request: HubRequestKey,
    message: String,
  ) {
    commitIfAdmitted(owner, context) {
      store.dispatch(HubManageFailed(hubId, request, message))
    }
  }

  private suspend fun <T> authorized(
    context: FamilySessionContext,
    block: suspend (accessToken: String, familyId: String) -> T,
  ): T = sessionCoordinator.authorizedCall(context) { current ->
    current.withFamilyAndAccessToken { familyId, accessToken -> block(accessToken, familyId) }
  }

  private suspend fun handleAuthorizedFailure(
    owner: FamilyWorkOwner,
    context: FamilySessionContext,
    hubId: String,
    request: HubRequestKey,
    error: AuthHttpException,
    message: String,
  ) {
    if (error.status == 401 && sessionCoordinator.isCurrent(context)) onSessionExpired(context)
    else publishManageFailure(owner, context, hubId, request, message)
  }

  private fun cancelAudienceJobs() {
    audienceLoadJob.getAndSet(null)?.cancel()
    audienceMutationJob.getAndSet(null)?.cancel()
  }

  fun stop() {
    treeJob.getAndSet(null)?.cancel()
    cancelAudienceJobs()
  }

  private companion object {
    const val LOAD_AUDIENCE_ERROR = "Couldn't load who can see this. Try again."
  }
}
