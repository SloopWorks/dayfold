package com.sloopworks.dayfold.client

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.reduxkotlin.Store

/**
 * Platform seam for obtaining a Firebase ID token for [provider] ("google" /
 * "apple") — S2 (ADR 0023/0027). Android wires Credential Manager + Google;
 * desktop/iOS return null until their native flows land. Returns null when the
 * platform can't produce a token (no Firebase config yet, or the user cancelled)
 * → AuthEngine falls back to the dev-token path.
 */
fun interface FirebaseSignIn { suspend fun idToken(provider: String): String? }

/** Sentinel access/refresh token for the debug-only fake sign-in (AuthEngine.devSignIn). */
const val DEV_TOKEN: String = "dev.local"
private const val SIGN_OUT_TIMEOUT_MS: Long = 5_000L

/**
 * Executes identity and account effects behind the application's command boundary.
 *
 * The engine orders provider exchange, token persistence, membership reconciliation, invitations,
 * profile, roster, and device operations; it validates session contexts before committing Redux or
 * durable state. It does not own native sign-in UI, the runtime scope, navigation, or reducer logic:
 * hosts supply immutable provider results and [DayfoldRuntime] owns its lifetime.
 */
class AuthEngine(
  private val store: Store<AppState>,
  private val authClient: AuthClient,
  private val tokenStore: TokenStore,
  private val devSecret: String? = null,
  private val devProvider: String = "dev",
  private val devProviderUid: String = "dev-user",
  private val firebaseSignIn: FirebaseSignIn? = null,
  // Data-boundary: drop the local content cache (ContentStore.wipe) when the session
  // ends, so a family's cards/hubs never outlive the logout. The DB→store bridge is
  // the sole writer of state.hubs, so resetting redux alone is not enough — the DB
  // must be cleared too or the bridge re-projects stale tenant data. Default no-op
  // for tests / entrypoints that don't own a cache. Mirrors SyncEngine's 403/404 path.
  private val clearCache: () -> Unit = {},
  // ADR 0052 — DB-first cold-start route gate. The membership cache seam (AuthEngine keeps NO
  // ContentStore dependency, so its tests stay DB-free — same pattern as clearCache): read the
  // last-known family list at cold start to route optimistically, persist it on every whoami.
  private val loadCachedMemberships: () -> List<FamilyMembership> = { emptyList() },
  private val saveMemberships: (List<FamilyMembership>) -> Unit = {},
  // Own scope for the background whoami reconcile (mirrors SyncEngine) — so restore() can route
  // off the local cache and confirm over the network WITHOUT blocking. Injected in tests to join.
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
  // ContentStore-backed seams are synchronous. Keep them off the caller/UI thread on every KMP
  // target; tests inject a named dispatcher to prove the boundary deterministically.
  private val databaseDispatcher: CoroutineDispatcher = Dispatchers.Default,
  // Runtime fence: close family publication admission and cancel/join every family job after
  // identity invalidation but before any tenant data is wiped. Isolated engine tests need no hook.
  private val beforeTerminalCleanup: suspend () -> Unit = {},
  // Production injects the runtime-owned coordinator shared by every engine. The default keeps
  // direct AuthEngine construction source-compatible and isolated in unit tests.
  sessionCoordinator: SessionCoordinator? = null,
) {
  private class RequestGate {
    private val gate = SynchronizedObject()
    private var latest = 0L
    private var active: Long? = null

    fun begin(): Long = synchronized(gate) {
      latest += 1L
      latest.also { active = it }
    }

    fun commit(request: Long, terminal: Boolean, block: () -> Boolean): Boolean = synchronized(gate) {
      if (latest != request) return@synchronized false
      block().also { if (terminal) active = null }
    }

    fun invalidateAndCommit(block: () -> Boolean): Boolean = synchronized(gate) {
      latest += 1L
      active = null
      block()
    }

    fun commitMutationTerminal(block: (loadActive: Boolean) -> Boolean): Boolean = synchronized(gate) {
      block(active != null)
    }
  }

  private val terminalMutex = Mutex()
  private val identityCommitMutex = Mutex()
  private val reconcileSequenceMutex = Mutex()
  private val reconcileMutationMutex = Mutex()
  private val reconcileJobsGate = SynchronizedObject()
  private val reconcileJobs = mutableSetOf<Job>()
  private val approvalsLoadRequests = RequestGate()
  private val rosterRequests = RequestGate()
  private val deviceRequests = RequestGate()
  private val deviceLookupRequests = RequestGate()
  private val inviteMutationMutex = Mutex()
  private val memberMutationMutex = Mutex()
  private val rosterMutationMutex = Mutex()
  private val deviceMutationMutex = Mutex()
  private val profileMutationMutex = Mutex()
  private val ownsSessionCoordinator = sessionCoordinator == null
  private val coordinator: SessionCoordinator = sessionCoordinator ?: SessionCoordinator(
    refreshScope = scope,
    refreshSession = { context -> context.refreshWith(authClient::refresh) },
    commitRotation = { session ->
      // Refresh executes in refreshScope, which is off-main in the default/test configuration.
      tokenStore.save(session)
      store.dispatch(SessionRotated(session))
    },
  ).also { isolated ->
    // Legacy isolated tests often construct a store already containing a session. Production
    // bootstraps the shared coordinator through restore/sign-in and never takes this path.
    store.state.session.session?.let { session ->
      val auth = isolated.install(session)
      store.state.session.activeFamilyId?.let { isolated.selectFamily(auth, it) }
    }
  }

  // The in-flight background reconcile launched by restore() on the optimistic (cached) path.
  // internal so tests can join it deterministically; entrypoints ignore it (fire-and-forget).
  private val reconcileJobRef = atomic<Job?>(null)
  internal val reconcileJob: Job? get() = reconcileJobRef.value
  internal var afterTerminalInvalidationHook: suspend () -> Unit = {}

  private fun trackReconcile(job: Job) = synchronized(reconcileJobsGate) { reconcileJobs += job }
  private fun untrackReconcile(job: Job) = synchronized(reconcileJobsGate) { reconcileJobs -= job }
  private fun isReconcile(job: Job): Boolean = synchronized(reconcileJobsGate) { job in reconcileJobs }

  /** Cold-start: restore a saved session (if any) and resolve memberships. */
  suspend fun restore() {
    cancelReconcile()
    val operation = coordinator.beginAuthOperation()
    coordinator.commitIfCurrent(operation) { store.dispatch(AuthRestoring) }
    val saved = withContext(databaseDispatcher) { tokenStore.load() }
    if (saved == null) {
      coordinator.commitIfCurrent(operation) {
        coordinator.invalidateAndCommit(operation.identityEpoch) {
          store.dispatch(SessionRestored(null)) // → SignIn
        }
      }
      return
    }
    val context = installWhenTerminalIdle(operation, saved) ?: return
    if (!publish(context, SessionRestored(saved))) return // → Loading
    val cached = withContext(databaseDispatcher) { loadCachedMemberships() } // ADR 0052 — DB-first route gate
    if (cached.isNotEmpty()) {
      // Route off the LOCAL cache now (no network), then confirm over the network in the
      // background. routeFor sees the session (dispatched just above) + the cached families
      // together — a single atomic MembershipsLoaded, no second async source to race it.
      if (!publishMemberships(context, cached, persist = false)) return
      startReconcile(context) // background whoami confirm (hadCache=true)
    } else {
      // No cache (fresh install / post-wipe): nothing to show, so stay network-gated exactly as
      // before — await whoami inline; the splash is honest until it resolves. 401 → refresh-and-retry.
      loadMembershipsLocked(context, hadCache = false)
    }
  }

  // Background whoami confirmation for the optimistic (cached) path (ADR 0052 §3). The captured
  // context suppresses every stale DB/Redux commit, so sign-out can invalidate and cancel it without
  // waiting for a network round-trip. On failure with a cache on screen the Feed remains intact.
  private suspend fun reconcile(context: AuthSessionContext) {
    loadMembershipsLocked(context, hadCache = true)
  }

  /**
   * Signs in with a host-scoped provider seam, falling back to the configured debug path.
   * Passing the seam per command lets a runtime graph avoid retaining an Activity/controller.
   */
  suspend fun signIn(
    provider: String,
    providerSignIn: FirebaseSignIn? = firebaseSignIn,
  ) {
    cancelReconcile()
    val operation = coordinator.beginAuthOperation()
    coordinator.commitIfCurrent(operation) { store.dispatch(SignInRequested(provider)) }
    var installed: AuthSessionContext? = null
    try {
      val idToken = providerSignIn?.idToken(provider)
      // Host-owned provider UI may outlive graph cancellation. Re-check its CAS ticket before
      // performing the token exchange so a closed runtime neither hits the network nor installs.
      if (!coordinator.isCurrent(operation)) return
      val session = when {
        idToken != null -> authClient.firebaseToken(idToken)
        devSecret != null -> authClient.devToken(devProvider, devProviderUid, devSecret)
        else -> throw IllegalStateException("Sign-in needs a provider. Google/Apple arrive at S2.")
      }
      val context = installNewIdentity(operation, session) ?: return
      installed = context
      val committed = withContext(databaseDispatcher) {
        coordinator.commitIfCurrent(context) {
          tokenStore.save(session)
          store.dispatch(SignInSucceeded(session))
        }
      }
      if (!committed) return
      Log.i("auth") { "sign-in succeeded provider=$provider" }
      loadMembershipsLocked(context, hadCache = false)   // fresh sign-in: no optimistic cache
    } catch (e: CancellationException) {
      installed?.let { active ->
        withContext(NonCancellable) {
          terminateSessionWithAction(active, SignedOut)
        }
      }
      throw e
    } catch (e: Exception) {
      Log.w("auth", e) { "sign-in failed provider=$provider" }
      val failure = SignInFailed(e.message ?: "Sign-in failed")
      val active = installed
      if (active == null) {
        coordinator.commitIfCurrent(operation) { store.dispatch(failure) }
      } else {
        withContext(NonCancellable) {
          terminateSessionWithAction(active, SignedOut, followup = failure)
        }
      }
    }
  }

  /**
   * Debug-only fake sign-in: mint a local session + a synthetic active membership
   * with NO network or Firebase, so a debug build can enter the app against ANY
   * backend (including an unreachable/real one). Gated at the call site
   * (BuildConfig.DEBUG on Android, omitted on iOS) — never wired in release. The
   * `dev.local` tokens are deliberately fake; a real backend rejects them (401 →
   * SessionExpired), so this grants no data access. Not persisted to the TokenStore:
   * a saved dev session would make the next cold-start restore() hit the backend.
   */
  suspend fun devSignIn() {
    cancelReconcile()
    val operation = coordinator.beginAuthOperation()
    coordinator.commitIfCurrent(operation) { store.dispatch(SignInRequested("dev")) }
    val session = Session(access = DEV_TOKEN, refresh = DEV_TOKEN, userId = "dev-user")
    val context = installNewIdentity(operation, session) ?: return
    publish(context, SignInSucceeded(session))
    publishMemberships(context, listOf(
      FamilyMembership(familyId = "dev-family", name = "Dev Family", role = "owner", status = "active"),
    ), persist = false)
  }

  /** Create the caller's first family (owner) and route into it. */
  suspend fun createFamily(name: String) {
    val context = coordinator.authSnapshot()
    if (context == null) { store.dispatch(AuthOpFailed("Not signed in")); return }
    createFamily(context, name)
  }

  internal suspend fun createFamily(context: AuthSessionContext, name: String) {
    publish(context, CreateFamilyRequested(name))
    try {
      val fid = authorized(context) { accessToken -> authClient.createFamily(accessToken, name) }
      val family = coordinator.selectFamily(context, fid) ?: return
      publish(family, FamilyCreated(fid, name))
      Log.i("auth") { "family created fid=$fid" }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      publish(context, AuthOpFailed(e.message ?: "Couldn't create the family"))
    }
  }

  /** Sign out: revoke server-side (best-effort), clear local tokens, reset to SignIn. */
  suspend fun signOut() {
    val context = coordinator.authSnapshot()
    if (context == null) {
      signOutCurrentIdentity()
    } else {
      signOut(context)
    }
  }

  internal suspend fun signOut(context: AuthSessionContext) {
    signOutCurrentIdentity(context)
  }

  private suspend fun signOutCurrentIdentity(expected: AuthSessionContext? = null) {
    val invalidated = coordinator.invalidateAndCommit(expected?.identityEpoch) {
      store.dispatch(SignOutRequested)
    }
    if (expected != null && invalidated == null) return
    var terminalFailure: Exception? = null
    terminalMutex.withLock {
      // Invalidation occurs before waiting for identity commit admission, so a blocked cache clear
      // or stale token exchange cannot install after this terminal boundary begins.
      try {
        terminalCleanup(SignedOut)
      } catch (failure: Exception) {
        terminalFailure = failure
      }
    }
    var remoteCancellation: CancellationException? = null
    invalidated?.let { context ->
      try {
        // Revoke uses only the captured old credential and runs outside terminal admission: a new
        // login need not wait for an unresponsive best-effort request after local cleanup commits.
        withTimeoutOrNull(SIGN_OUT_TIMEOUT_MS) { context.withAccessToken(authClient::signout) }
      } catch (e: CancellationException) {
        remoteCancellation = e
      } catch (_: Exception) {}   // best-effort; local clear is what matters
    }
    remoteCancellation?.let { throw it }
    terminalFailure?.let { throw it }
  }

  /** Redeem an invite token (slice-2): success = a pending membership awaiting
   *  owner approval; everything else maps to a join-result the UI renders. */
  suspend fun redeemInvite(token: String) {
    val context = coordinator.authSnapshot() ?: return
    redeemInvite(context, token)
  }

  internal suspend fun redeemInvite(context: AuthSessionContext, token: String) {
    redeemInviteLocked(token, context)
  }

  // No-mutex core — callable from restore/sign-in after their serialized auth phase.
  private suspend fun redeemInviteLocked(token: String, context: AuthSessionContext) {
    publish(context, RedeemRequested(token))
    try {
      when (val res = authorized(context) { accessToken -> authClient.redeemInvite(accessToken, token) }) {
        is RedeemResult.Pending -> { Log.i("auth") { "invite redeemed" }; publish(context, InviteRedeemed(res.familyName)) }
        RedeemResult.Expired -> publish(context, InviteRejected("expired"))
        RedeemResult.Locked -> publish(context, InviteRejected("locked"))
        RedeemResult.AlreadyMember -> publish(context, InviteRejected("already"))
        RedeemResult.Removed -> publish(context, InviteRejected("removed"))
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w("auth", e) { "invite redeem failed" }
      publish(context, InviteRejected("error"))          // transient 401/5xx/network → join-retry
    }
  }

  /** Deep-link (ADR 0048): an https://<app>/invite/<token> App Link. If signed in,
   *  redeem now (→ waiting-for-approval); else stash the token and redeem once
   *  memberships resolve (redeem is auth-first). Returns silently for non-invite URLs. */
  suspend fun openInviteLink(raw: String) {
    openInviteLink(raw, coordinator.authSnapshot())
  }

  internal suspend fun openInviteLink(raw: String, context: AuthSessionContext?) {
    val token = parseInviteToken(raw) ?: return
    // The redeem reducers pin route=JoinInvite so the outcome shows (even against a
    // concurrent cold-start MembershipsLoaded→routeFor). Signed in → redeem now; else
    // stash and redeem after sign-in resolves memberships.
    if (context != null) {
      if (coordinator.isCurrent(context)) redeemInviteLocked(token, context)
    } else if (coordinator.authSnapshot() == null) {
      store.dispatch(InviteLinkStashed(token))
    }
  }

  // Consume a pre-sign-in stashed invite token once memberships resolve. Called at the
  // tail of loadMembershipsLocked using the same captured identity context.
  private suspend fun resumePendingInviteLink(context: AuthSessionContext) {
    val token = store.state.session.pendingInviteLink ?: return
    if (!publish(context, InviteLinkConsumed)) return
    redeemInviteLocked(token, context)
  }

  /** Owner: load the pending-approval queue + outstanding invites (one GET → both). */
  suspend fun loadApprovals(fid: String) {
    val context = familyContext(fid) ?: return
    loadApprovalsLocked(context)
  }

  internal suspend fun loadApprovals(context: FamilySessionContext, fid: String) {
    if (context.familyId == fid) loadApprovalsLocked(context)
  }

  // No-mutex core — callable from already-serialized mint/revoke paths.
  private suspend fun loadApprovalsLocked(context: FamilySessionContext) {
    val request = approvalsLoadRequests.begin()
    publishLatest(approvalsLoadRequests, request, context, ApprovalsRequested, terminal = false)
    try {
      val q = authorized(context) { fid, accessToken -> authClient.familyApprovals(accessToken, fid) }
      publishLatest(
        approvalsLoadRequests,
        request,
        context,
        ApprovalsLoaded(q.pending, q.invites),
        terminal = true,
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // ApprovalsFailed also clears memberOpId, so a read failure must not use it while an
      // independent member mutation is active. Preserve the current data and only end load busy.
      publishLatest(
        approvalsLoadRequests,
        request,
        context,
        ApprovalsLoaded(store.state.familyAdmin.pendingApprovals, store.state.familyAdmin.outstandingInvites),
        terminal = true,
      )
    }
  }

  /** Owner: mint an invite (qr|link) → show it, then refresh outstanding. The raw
   *  token is dispatched to state for display only — never persisted or logged. */
  suspend fun mintInvite(fid: String, mode: String) {
    val context = familyContext(fid) ?: return
    mintInvite(context, mode)
  }

  internal suspend fun mintInvite(context: FamilySessionContext, mode: String): Unit = inviteMutationMutex.withLock {
    val maxUses = if (mode == "qr") 1 else 5   // qr forced to 1 server-side; link default 5 ("0 of 5 used")
    publish(context, MintRequested)
    try {
      when (val r = authorized(context) { familyId, accessToken ->
        authClient.mintInvite(accessToken, familyId, mode, maxUses)
      }) {
        is MintResult.Ok -> {
          if (approvalsLoadRequests.invalidateAndCommit { publish(context, InviteMinted(r.invite)) }) {
            loadApprovalsLocked(context)
          }
        }
        MintResult.RateLimited -> publish(context, MintFailed("ratelimited"))
        MintResult.Forbidden -> publish(context, MintFailed("forbidden"))
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) { publish(context, MintFailed("error")) }
  }

  /** Owner: revoke an outstanding invite → drop it on success; reload on a guarded failure. */
  suspend fun revokeInvite(fid: String, id: String) {
    val context = familyContext(fid) ?: return
    revokeInvite(context, id)
  }

  internal suspend fun revokeInvite(context: FamilySessionContext, id: String): Unit = inviteMutationMutex.withLock {
    publish(context, InviteRevokeRequested(id))
    try {
      authorized(context) { familyId, accessToken -> authClient.revokeInvite(accessToken, familyId, id) }
      if (approvalsLoadRequests.invalidateAndCommit { publish(context, InviteRevoked(id)) }) {
        loadApprovalsLocked(context)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      publish(context, InviteRevokeFailed(id))
    }
  }

  /** Owner: approve / decline a pending member → drop them from the queue on success. */
  suspend fun approveMember(fid: String, uid: String) {
    val context = familyContext(fid) ?: return
    resolveMember(context, uid, approve = true)
  }
  suspend fun declineMember(fid: String, uid: String) {
    val context = familyContext(fid) ?: return
    resolveMember(context, uid, approve = false)
  }
  internal suspend fun approveMember(context: FamilySessionContext, uid: String) =
    resolveMember(context, uid, approve = true)
  internal suspend fun declineMember(context: FamilySessionContext, uid: String) =
    resolveMember(context, uid, approve = false)

  private suspend fun resolveMember(
    context: FamilySessionContext,
    uid: String,
    approve: Boolean,
  ): Unit = memberMutationMutex.withLock {
    publish(context, MemberOpRequested(uid))
    try {
      authorized(context) { familyId, accessToken ->
        if (approve) authClient.approveMember(accessToken, familyId, uid)
        else authClient.declineMember(accessToken, familyId, uid)
      }
      if (approvalsLoadRequests.invalidateAndCommit { publish(context, MemberResolved(uid)) }) {
        loadApprovalsLocked(context)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      approvalsLoadRequests.commitMutationTerminal { loadActive ->
        val published = publish(context, ApprovalsFailed)
        if (published && loadActive) publish(context, ApprovalsRequested)
        published
      }
    }
  }

  /** Load the active member roster for a family. */
  suspend fun loadMembers(fid: String) {
    val context = familyContext(fid) ?: return
    loadMembers(context, fid)
  }

  internal suspend fun loadMembers(context: FamilySessionContext, fid: String) {
    if (context.familyId != fid) return
    val request = rosterRequests.begin()
    publishLatest(rosterRequests, request, context, RosterRequested, terminal = false)
    try {
      val members = authorized(context) { familyId, accessToken ->
        authClient.familyMembers(accessToken, familyId)
      }
      publishLatest(rosterRequests, request, context, RosterLoaded(members), terminal = true)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      publishLatest(
        rosterRequests,
        request,
        context,
        RosterFailed("Couldn't load members. Try again."),
        terminal = true,
      )
    }
  }

  // Eager, quiet roster load so a checklist doneBy byline resolves to a name ANYWHERE the
  // content renders (not only after opening the Members screen). No RosterRequested/Failed
  // noise — a failure just leaves bylines on the "a family member" fallback. No-mutex core
  // (called from loadMembershipsLocked with its captured family generation).
  private suspend fun loadRosterLocked(context: FamilySessionContext?) {
    if (context == null) return
    val request = rosterRequests.begin()
    try {
      val members = authorized(context) { fid, accessToken -> authClient.familyMembers(accessToken, fid) }
      publishLatest(rosterRequests, request, context, RosterLoaded(members), terminal = true)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) { /* quiet */ }
  }

  /** Owner removes a member → drop from the roster on success (409 last-owner → reload). */
  suspend fun removeMember(fid: String, uid: String) {
    val context = familyContext(fid) ?: return
    removeMember(context, fid, uid)
  }

  internal suspend fun removeMember(
    context: FamilySessionContext,
    fid: String,
    uid: String,
  ): Unit = rosterMutationMutex.withLock {
    if (context.familyId != fid) return@withLock
    publish(context, MemberOpRequested(uid))
    try {
      authorized(context) { familyId, accessToken -> authClient.removeMember(accessToken, familyId, uid) }
      if (rosterRequests.invalidateAndCommit { publish(context, MemberRemoved(uid)) }) loadMembers(context, fid)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      val fallback = try {
        authorized(context) { familyId, accessToken -> authClient.familyMembers(accessToken, familyId) }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        store.state.familyAdmin.members
      }
      rosterRequests.invalidateAndCommit { publish(context, RosterLoaded(fallback)) }
    }
  }

  // Eager, quiet own-profile load (name + avatar) — same posture as loadRosterLocked:
  // no *Requested/*Failed noise, a failure just leaves my* fields at their prior
  // value (defaults on cold start) so a flaky GET /auth/me can never wedge the
  // restore/route flow. No-mutex core bound to the restore/sign-in identity context.
  private suspend fun loadProfileLocked(context: AuthSessionContext) = profileMutationMutex.withLock {
    try {
      val profile = authorized(context) { accessToken -> authClient.getMe(accessToken) }
      publish(context, ProfileLoaded(profile))
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) { /* quiet */ }
  }

  /**
   * Update the caller's own avatar (color + bundled avatar ref; `null` clears a
   * field). Optimistic: the picked value applies to state immediately (mirrors
   * MemberOpRequested/DeviceOpRequested) — the picker (task 5) never waits on the
   * PATCH round-trip, and avatarOpId is busy for the lifetime of the request so an
   * in-flight indicator can render. On success the SERVER-returned value replaces
   * the optimistic one (server is truth). On failure the optimistic value is
   * REVERTED to what it was before this call (mirrors removeMember/revokeDevice's
   * reconcile-on-failure) and avatarError is set (mirrors rosterError/devicesError).
   */
  suspend fun updateAvatar(avatarColor: String?, avatarRef: String?) {
    val context = coordinator.authSnapshot() ?: return
    updateAvatar(context, avatarColor, avatarRef)
  }

  internal suspend fun updateAvatar(
    context: AuthSessionContext,
    avatarColor: String?,
    avatarRef: String?,
  ): Unit = profileMutationMutex.withLock {
    val prevAvatarColor = store.state.profile.avatarColor
    val prevAvatarRef = store.state.profile.avatarRef
    publish(context, AvatarOpRequested(avatarColor, avatarRef))
    try {
      val profile = authorized(context) { accessToken ->
        authClient.updateAvatar(accessToken, avatarColor, avatarRef)
      }
      publish(context, AvatarUpdated(profile.avatarColor, profile.avatarRef))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      publish(context, AvatarUpdateFailed(prevAvatarColor, prevAvatarRef, "Couldn't save your avatar. Try again."))
    }
  }

  /** Update the caller's own display name — optimistic + reconcile-on-failure (mirrors updateAvatar). */
  suspend fun updateDisplayName(name: String) {
    val context = coordinator.authSnapshot() ?: return
    updateDisplayName(context, name)
  }

  internal suspend fun updateDisplayName(context: AuthSessionContext, name: String): Unit =
    profileMutationMutex.withLock {
    val prevName = store.state.profile.displayName
    publish(context, NameOpRequested(name))
    try {
      val profile = authorized(context) { accessToken -> authClient.updateDisplayName(accessToken, name) }
      publish(context, NameUpdated(profile.displayName))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      publish(context, NameUpdateFailed(prevName, "Couldn't save your name. Try again."))
    }
  }

  /** Load the caller's connected devices/apps. */
  suspend fun loadDevices() {
    val context = coordinator.authSnapshot() ?: return
    loadDevices(context)
  }

  internal suspend fun loadDevices(context: AuthSessionContext) {
    val request = deviceRequests.begin()
    publishLatest(deviceRequests, request, context, DevicesRequested, terminal = false)
    try {
      val devices = authorized(context) { accessToken -> authClient.credentials(accessToken) }
      publishLatest(deviceRequests, request, context, DevicesLoaded(devices), terminal = true)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      publishLatest(
        deviceRequests,
        request,
        context,
        DevicesFailed("Couldn't load devices. Try again."),
        terminal = true,
      )
    }
  }

  /** Revoke one of the caller's credentials → drop on success (reload on a guarded failure). */
  suspend fun revokeDevice(id: String) {
    val context = coordinator.authSnapshot() ?: return
    revokeDevice(context, id)
  }

  internal suspend fun revokeDevice(context: AuthSessionContext, id: String): Unit = deviceMutationMutex.withLock {
    publish(context, DeviceOpRequested(id))
    try {
      authorized(context) { accessToken -> authClient.revokeCredential(accessToken, id) }
      if (deviceRequests.invalidateAndCommit { publish(context, DeviceRevoked(id)) }) loadDevices(context)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      val fallback = try {
        authorized(context) { accessToken -> authClient.credentials(accessToken) }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        store.state.devices.devices
      }
      deviceRequests.invalidateAndCommit { publish(context, DevicesLoaded(fallback)) }
    }
  }

  // ── CLI/device approval (S6-D) ──

  /** Look up a pending device grant by user_code (session-auth) → AuthorizeDevice. */
  suspend fun lookupDevice(code: String) {
    val context = coordinator.authSnapshot() ?: return
    lookupDeviceLocked(code, context)
  }

  internal suspend fun lookupDevice(context: AuthSessionContext, code: String) =
    lookupDeviceLocked(code, context)

  // Lookup core WITHOUT the mutex — callable from the deep-link resume path with
  // the restore/sign-in identity context it was captured under.
  private suspend fun lookupDeviceLocked(code: String, context: AuthSessionContext) {
    val request = deviceLookupRequests.begin()
    publishLatest(deviceLookupRequests, request, context, DeviceLookupRequested, terminal = false)
    try {
      when (val r = authorized(context) { accessToken -> authClient.devicePending(accessToken, code) }) {
        is DeviceLookupResult.Found -> publishLatest(
          deviceLookupRequests,
          request,
          context,
          DevicePendingLoaded(r.device),
          terminal = true,
        )
        DeviceLookupResult.NotFound -> publishLatest(
          deviceLookupRequests,
          request,
          context,
          DeviceLookupNotFound,
          terminal = true,
        )
        DeviceLookupResult.Locked -> publishLatest(
          deviceLookupRequests,
          request,
          context,
          DeviceLookupFailed("Too many tries — wait about 15 minutes."),
          terminal = true,
        )
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      publishLatest(
        deviceLookupRequests,
        request,
        context,
        DeviceLookupFailed("Couldn't check that code. Try again."),
        terminal = true,
      )
    }
  }

  /**
   * Deep-link entry (Phase 2): an App/Universal Link or scanned QR resolved to
   * [raw] (`<origin>/device?user_code=…` or a bare code). Signed-in → look it up
   * now (→ AuthorizeDevice); not signed-in → stash it and resume after sign-in.
   * Malformed payloads are ignored (no nav, no stash). Platform intent/userActivity
   * handlers call this; the App-Links/Universal-Links host verification + manifest
   * intent-filters are the operator-gated half of Phase 2 (cert fingerprint / Team ID).
   */
  suspend fun openDeviceLink(raw: String) {
    openDeviceLink(raw, coordinator.authSnapshot())
  }

  internal suspend fun openDeviceLink(raw: String, context: AuthSessionContext?) {
    val code = parseDeviceCode(raw) ?: return
    if (context != null) {
      if (coordinator.isCurrent(context)) lookupDeviceLocked(code, context)
    } else if (coordinator.authSnapshot() == null) {
      store.dispatch(DeviceLinkStashed(code))
    }
  }

  // If a deep-link code was stashed before sign-in, consume it and open the approve
  // screen now. Called at the tail of loadMembershipsLocked with the same identity context.
  private suspend fun resumePendingDeviceLink(context: AuthSessionContext) {
    val code = store.state.devices.pendingLink ?: return
    if (!publish(context, DeviceLinkConsumed)) return
    lookupDeviceLocked(code, context)
  }

  /**
   * Owner approves the pending device against [fid] → DeviceApproved / expired / failed.
   * [hubIds] null = full/blanket grant (existing behavior); non-null + non-empty =
   * per-hub scope selection (ADR 0029 T3), threaded straight to AuthClient.
   */
  suspend fun approveDevice(fid: String, code: String, hubIds: List<String>? = null) {
    val context = coordinator.authSnapshot() ?: return
    approveDevice(context, fid, code, hubIds)
  }

  internal suspend fun approveDevice(
    context: AuthSessionContext,
    fid: String,
    code: String,
    hubIds: List<String>? = null,
  ) {
    publish(context, ApproveDeviceRequested)
    try {
      when (authorized(context) { accessToken ->
        authClient.deviceApprove(accessToken, fid, code, hubIds)
      }) {
        DeviceActionResult.Ok -> publish(context, DeviceApproved)
        DeviceActionResult.Expired -> publish(context, DeviceApproveExpired)
        DeviceActionResult.Locked -> publish(context, DeviceOpFailed("Too many tries — wait about 15 minutes."))
        DeviceActionResult.Forbidden -> publish(context, DeviceOpFailed("You're not an owner of that family."))
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      publish(context, DeviceOpFailed("Couldn't approve. Try again."))
    }
  }

  /** Owner denies the pending device → DeviceDenied (Ok or already-gone) / failed. */
  suspend fun denyDevice(fid: String, code: String) {
    val context = coordinator.authSnapshot() ?: return
    denyDevice(context, fid, code)
  }

  internal suspend fun denyDevice(context: AuthSessionContext, fid: String, code: String) {
    publish(context, DenyDeviceRequested)
    try {
      when (authorized(context) { accessToken ->
        authClient.deviceDeny(accessToken, fid, code)
      }) {
        DeviceActionResult.Ok, DeviceActionResult.Expired -> publish(context, DeviceDenied) // gone == denied
        DeviceActionResult.Locked -> publish(context, DeviceOpFailed("Too many tries — wait about 15 minutes."))
        DeviceActionResult.Forbidden -> publish(context, DeviceOpFailed("You're not an owner of that family."))
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      publish(context, DeviceOpFailed("Couldn't deny. Try again."))
    }
  }

  /** Current access token (for the SyncClient token provider, wired at T6). */
  fun accessToken(): String? = store.state.session.session?.access

  // ── internals ──

  // [hadCache] (ADR 0052): true when a cached Feed is already on screen (the optimistic cold-start
  // path). It only changes the error terminal — a reachable-but-erroring/unreachable server must
  // NOT strand the user on AuthError when a usable cached view is already showing; AuthError is
  // reserved for the no-cache case (nothing to show). The 401 dead-session path is unconditional.
  private suspend fun loadMembershipsLocked(context: AuthSessionContext, hadCache: Boolean) {
    try {
      val who = authorized(context) { accessToken -> authClient.whoami(accessToken) }
      if (!publishMemberships(context, who.families, persist = true)) return
      resumePendingDeviceLink(context)   // cold-install resume: open a link stashed pre-sign-in
      resumePendingInviteLink(context)   // ADR 0048: redeem an invite link stashed pre-sign-in
      loadRosterLocked(store.state.session.activeFamilyId?.let(coordinator::familySnapshot))
      loadProfileLocked(context)
    } catch (e: CancellationException) {
      throw e
    } catch (e: AuthHttpException) {
      // 401 here = access expired AND refresh couldn't recover (revoked/expired/
      // reused) → the saved session is dead. Clear it and fall back to Sign-in so
      // the spinner never wedges. Other statuses = a reachable-but-erroring server.
      if (e.status == 401) {
        expireSession(context)
      } else if (!hadCache) {
        publish(context, RestoreFailed("Dayfold had a problem (HTTP ${e.status}). Tap retry."))
      }
      // else: cached Feed already showing → stay on it (ADR 0052 §3), reconcile again on next poll/foreground.
    } catch (e: Exception) {
      // Network/unknown → keep the session; offer Retry only when there's nothing cached to show.
      if (!hadCache) publish(context, RestoreFailed("Couldn't reach Dayfold. Check your connection and retry."))
      // else: stay on the cached Feed.
    }
  }

  private suspend fun expireSession(context: AuthSessionContext) =
    terminateSession(context, expired = true)

  /**
   * Applies a terminal session rejection reported by another coordinator-backed engine.
   * The shared runtime factory wires Sync and Hub 401/tenancy failures through this one
   * cleanup path so reconcile cancellation, token/cache clearing, and Redux reset stay aligned.
   */
  internal suspend fun terminateSession(
    context: AuthSessionContext,
    expired: Boolean,
  ) = terminalMutex.withLock {
    if (coordinator.invalidate(context.identityEpoch) == null) return@withLock
    finishTerminalInvalidation(if (expired) SessionExpired else SignedOut)
  }

  private suspend fun terminateSessionWithAction(
    context: AuthSessionContext,
    action: Any,
    followup: Any? = null,
  ) =
    terminalMutex.withLock {
      if (coordinator.invalidate(context.identityEpoch) == null) return@withLock
      finishTerminalInvalidation(action, followup)
    }

  /**
   * Applies terminal cleanup only if [context] is still the active family generation.
   * Hosts use this for captured Sync/Hub failures so an old tenant's late rejection cannot
   * expire a newer family selected within the same identity.
   */
  suspend fun terminateFamilySession(
    context: FamilySessionContext,
    expired: Boolean,
  ) = terminalMutex.withLock {
    if (coordinator.invalidate(context) == null) return@withLock
    finishTerminalInvalidation(if (expired) SessionExpired else SignedOut)
  }

  private suspend fun finishTerminalInvalidation(action: Any, followup: Any? = null) {
    afterTerminalInvalidationHook()
    val currentJob = currentCoroutineContext()[Job]
    val expiringReconcile = currentJob != null && isReconcile(currentJob)
    if (expiringReconcile) reconcileJobRef.compareAndSet(currentJob, null)
    terminalCleanup(
      action = action,
      followup = followup,
      reconcileAlreadyClosing = expiringReconcile,
    )
  }

  private suspend fun terminalCleanup(
    action: Any,
    followup: Any? = null,
    reconcileAlreadyClosing: Boolean = false,
  ) {
    var firstFailure: Exception? = null
    fun capture(block: () -> Unit) {
      try {
        block()
      } catch (failure: Exception) {
        if (firstFailure == null) firstFailure = failure
      }
    }

    withContext(NonCancellable) {
      try {
        beforeTerminalCleanup()
      } catch (failure: Exception) {
        firstFailure = failure
      }
      if (!reconcileAlreadyClosing) {
        try {
          cancelReconcile()
        } catch (failure: Exception) {
          if (firstFailure == null) firstFailure = failure
        }
      }
      withContext(databaseDispatcher) {
        capture(tokenStore::clear)
        capture(clearCache)
        capture { store.dispatch(action) }
        if (followup != null) capture { store.dispatch(followup) }
      }
    }
    firstFailure?.let { throw it }
  }

  private suspend fun cancelReconcile() {
    val currentJob = currentCoroutineContext()[Job]
    if (currentJob != null && isReconcile(currentJob)) {
      reconcileJobRef.compareAndSet(currentJob, null)
      return
    }
    reconcileSequenceMutex.withLock {
      val job = reconcileMutationMutex.withLock {
        reconcileJobRef.getAndSet(null)
      }
      // Joining while holding reconcileMutationMutex deadlocks if the old reconciliation is
      // concurrently entering terminal cleanup. The sequence mutex preserves replacement order;
      // the mutation mutex protects only the detach/install state transitions.
      job?.cancelAndJoin()
    }
  }

  private suspend fun startReconcile(context: AuthSessionContext) {
    reconcileSequenceMutex.withLock {
      val prior = reconcileMutationMutex.withLock {
        reconcileJobRef.getAndSet(null)
      }
      prior?.cancelAndJoin()
      if (!coordinator.isCurrent(context)) return@withLock
      lateinit var replacement: Job
      replacement = scope.launch(start = CoroutineStart.LAZY) {
        try {
          reconcile(context)
        } finally {
          reconcileJobRef.compareAndSet(replacement, null)
          untrackReconcile(replacement)
        }
      }
      trackReconcile(replacement)
      val installed = reconcileMutationMutex.withLock {
        if (!coordinator.isCurrent(context)) false
        else reconcileJobRef.compareAndSet(null, replacement)
      }
      if (installed) replacement.start()
      else {
        untrackReconcile(replacement)
        replacement.cancel()
      }
    }
  }

  private suspend fun installNewIdentity(
    operation: AuthOperationContext,
    session: Session,
  ): AuthSessionContext? = identityCommitMutex.withLock {
    terminalMutex.withLock terminal@{
      if (!coordinator.isCurrent(operation)) return@terminal null
      val cleared = withContext(databaseDispatcher) {
        // Validate and clear under the coordinator gate. This closes the final check/write gap:
        // graph cancellation or a newer sign-in cannot interleave after validation but before the
        // destructive cache write.
        coordinator.commitIfCurrent(operation) { clearCache() }
      }
      if (!cleared) return@terminal null
      if (!coordinator.isCurrent(operation)) return@terminal null
      coordinator.install(operation, session)
    }
  }

  private suspend fun installWhenTerminalIdle(
    operation: AuthOperationContext,
    session: Session,
  ): AuthSessionContext? = terminalMutex.withLock {
    coordinator.install(operation, session)
  }

  private suspend fun <T> authorized(
    context: AuthSessionContext,
    block: suspend (accessToken: String) -> T,
  ): T = try {
    coordinator.authorizedCall(context) { current -> current.withAccessToken(block) }
  } catch (error: AuthHttpException) {
    if (error.status == 401) expireSession(context)
    throw error
  }

  private suspend fun <T> authorized(
    context: FamilySessionContext,
    block: suspend (familyId: String, accessToken: String) -> T,
  ): T = try {
    coordinator.authorizedCall(context) { current -> current.withFamilyAndAccessToken(block) }
  } catch (error: AuthHttpException) {
    if (error.status == 401) terminateFamilySession(context, expired = true)
    throw error
  }

  private fun publish(context: AuthSessionContext, action: Any): Boolean =
    coordinator.commitIfCurrent(context) { store.dispatch(action) }

  private fun publish(context: FamilySessionContext, action: Any): Boolean =
    coordinator.commitIfCurrent(context) { store.dispatch(action) }

  private fun publishLatest(
    gate: RequestGate,
    request: Long,
    context: AuthSessionContext,
    action: Any,
    terminal: Boolean,
  ): Boolean = gate.commit(request, terminal) { publish(context, action) }

  private fun publishLatest(
    gate: RequestGate,
    request: Long,
    context: FamilySessionContext,
    action: Any,
    terminal: Boolean,
  ): Boolean = gate.commit(request, terminal) { publish(context, action) }

  private suspend fun publishMemberships(
    context: AuthSessionContext,
    memberships: List<FamilyMembership>,
    persist: Boolean,
  ): Boolean = withContext(databaseDispatcher) {
    val selectedId = activeFamilyIdFor(memberships)
    val family = coordinator.selectFamily(context, selectedId)
    if (family != null) {
      coordinator.commitIfCurrent(family) {
        if (persist) saveMemberships(memberships)
        store.dispatch(MembershipsLoaded(memberships))
      }
    } else {
      coordinator.commitIfCurrent(context) {
        if (persist) saveMemberships(memberships)
        store.dispatch(MembershipsLoaded(memberships))
      }
    }
  }

  private fun familyContext(fid: String): FamilySessionContext? {
    coordinator.familySnapshot(fid)?.let { return it }
    // Source-compatible isolated tests historically supplied only AppState.session. Production's
    // shared coordinator requires an explicit selected family and does not use this fallback.
    if (!ownsSessionCoordinator) return null
    return coordinator.authSnapshot()?.let { coordinator.selectFamily(it, fid) }
  }
}
