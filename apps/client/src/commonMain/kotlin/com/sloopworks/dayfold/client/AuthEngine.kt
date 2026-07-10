package com.sloopworks.dayfold.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

// AUTH-S5 T4 — orchestrates the session lifecycle (mirrors SyncEngine): sequences
// AuthClient I/O + TokenStore persistence and dispatches the auth actions. Pure
// state transitions live in the reducer (T1); all effects live here.
//
// Sign-in (S2, ADR 0023/0027): if a [firebaseSignIn] seam yields a Firebase ID
// token for the tapped provider, POST /auth/firebase mints a real session. Else
// it falls back to the gated dev-token path (local/test; the operator's dogfood
// identity). `firebaseSignIn == null && devSecret == null` ⇒ no path → sign-in
// fails closed with a clear message (a shipped build without Firebase config).
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
) {
  private val mutex = Mutex()

  // The in-flight background reconcile launched by restore() on the optimistic (cached) path.
  // internal so tests can join it deterministically; entrypoints ignore it (fire-and-forget).
  internal var reconcileJob: Job? = null

  /** Cold-start: restore a saved session (if any) and resolve memberships. */
  suspend fun restore() = mutex.withLock {
    store.dispatch(AuthRestoring)
    val saved = tokenStore.load()
    if (saved == null) {
      store.dispatch(SessionRestored(null))   // → SignIn
      return@withLock
    }
    store.dispatch(SessionRestored(saved))     // → Loading
    val cached = loadCachedMemberships()       // ADR 0052 — DB-first route gate
    if (cached.isNotEmpty()) {
      // Route off the LOCAL cache now (no network), then confirm over the network in the
      // background. routeFor sees the session (dispatched just above) + the cached families
      // together — a single atomic MembershipsLoaded, no second async source to race it.
      store.dispatch(MembershipsLoaded(cached))          // → Feed, off a local read
      reconcileJob = scope.launch { reconcile(saved) }   // background whoami confirm (hadCache=true)
    } else {
      // No cache (fresh install / post-wipe): nothing to show, so stay network-gated exactly as
      // before — await whoami inline; the splash is honest until it resolves. 401 → refresh-and-retry.
      loadMembershipsLocked(saved, hadCache = false)
    }
  }

  // Background whoami confirmation for the optimistic (cached) path (ADR 0052 §3). Takes the mutex
  // INSIDE the launched job — never held across restore() — so a concurrent signIn/op isn't blocked
  // by the network round-trip. On failure with a cache already on screen it leaves the Feed intact.
  private suspend fun reconcile(session: Session) = mutex.withLock {
    loadMembershipsLocked(session, hadCache = true)
  }

  /** Sign in: real Firebase ID token if the platform yields one, else dev-token. */
  suspend fun signIn(provider: String) = mutex.withLock {
    store.dispatch(SignInRequested(provider))
    try {
      val idToken = firebaseSignIn?.idToken(provider)
      val session = when {
        idToken != null -> authClient.firebaseToken(idToken)
        devSecret != null -> authClient.devToken(devProvider, devProviderUid, devSecret)
        else -> throw IllegalStateException("Sign-in needs a provider. Google/Apple arrive at S2.")
      }
      tokenStore.save(session)
      store.dispatch(SignInSucceeded(session))
      loadMembershipsLocked(session, hadCache = false)   // fresh sign-in: no optimistic cache
    } catch (e: Exception) {
      store.dispatch(SignInFailed(e.message ?: "Sign-in failed"))
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
  suspend fun devSignIn() = mutex.withLock {
    store.dispatch(SignInRequested("dev"))
    val session = Session(access = DEV_TOKEN, refresh = DEV_TOKEN, userId = "dev-user")
    store.dispatch(SignInSucceeded(session))
    store.dispatch(MembershipsLoaded(listOf(
      FamilyMembership(familyId = "dev-family", name = "Dev Family", role = "owner", status = "active"),
    )))
  }

  /** Create the caller's first family (owner) and route into it. */
  suspend fun createFamily(name: String) = mutex.withLock {
    store.dispatch(CreateFamilyRequested(name))
    val session = store.state.session
    if (session == null) { store.dispatch(AuthOpFailed("Not signed in")); return@withLock }
    try {
      val fid = callWithRefresh(session) { authClient.createFamily(it.access, name) }
      store.dispatch(FamilyCreated(fid, name))
    } catch (e: Exception) {
      store.dispatch(AuthOpFailed(e.message ?: "Couldn't create the family"))
    }
  }

  /** Sign out: revoke server-side (best-effort), clear local tokens, reset to SignIn. */
  suspend fun signOut() = mutex.withLock {
    store.dispatch(SignOutRequested)
    store.state.session?.let { s ->
      try { authClient.signout(s.access) } catch (_: Exception) {}   // best-effort; local clear is what matters
    }
    tokenStore.clear()
    clearCache()                 // drop the local family cache — data must not outlive logout
    store.dispatch(SignedOut)
  }

  /** Redeem an invite token (slice-2): success = a pending membership awaiting
   *  owner approval; everything else maps to a join-result the UI renders. */
  suspend fun redeemInvite(token: String) = mutex.withLock { redeemInviteLocked(token) }

  // No-mutex core — callable from an already-locked path (the deep-link resume runs
  // inside loadMembershipsLocked's lock; Mutex isn't reentrant — same convention as
  // lookupDeviceLocked).
  private suspend fun redeemInviteLocked(token: String) {
    store.dispatch(RedeemRequested(token))
    val session = store.state.session
    if (session == null) { store.dispatch(InviteRejected("error")); return }
    try {
      when (val res = callWithRefresh(session) { authClient.redeemInvite(it.access, token) }) {
        is RedeemResult.Pending -> store.dispatch(InviteRedeemed(res.familyName))
        RedeemResult.Expired -> store.dispatch(InviteRejected("expired"))
        RedeemResult.Locked -> store.dispatch(InviteRejected("locked"))
        RedeemResult.AlreadyMember -> store.dispatch(InviteRejected("already"))
        RedeemResult.Removed -> store.dispatch(InviteRejected("removed"))
      }
    } catch (e: Exception) {
      store.dispatch(InviteRejected("error"))            // transient 401/5xx/network → join-retry
    }
  }

  /** Deep-link (ADR 0048): an https://<app>/invite/<token> App Link. If signed in,
   *  redeem now (→ waiting-for-approval); else stash the token and redeem once
   *  memberships resolve (redeem is auth-first). Returns silently for non-invite URLs. */
  suspend fun openInviteLink(raw: String) {
    val token = parseInviteToken(raw) ?: return
    // The redeem reducers pin route=JoinInvite so the outcome shows (even against a
    // concurrent cold-start MembershipsLoaded→routeFor). Signed in → redeem now; else
    // stash and redeem after sign-in resolves memberships.
    if (store.state.session != null) redeemInvite(token)
    else store.dispatch(InviteLinkStashed(token))
  }

  // Consume a pre-sign-in stashed invite token once memberships resolve. Called at the
  // tail of loadMembershipsLocked (already holding the mutex — hence redeemInviteLocked).
  private suspend fun resumePendingInviteLink() {
    val token = store.state.pendingInviteLink ?: return
    if (store.state.session == null) return
    store.dispatch(InviteLinkConsumed)
    redeemInviteLocked(token)
  }

  /** Owner: load the pending-approval queue + outstanding invites (one GET → both). */
  suspend fun loadApprovals(fid: String) = mutex.withLock {
    val session = store.state.session ?: return@withLock
    loadApprovalsLocked(session, fid)
  }

  // No-mutex core — callable from an already-locked path (mintInvite/revokeInvite,
  // Mutex isn't reentrant; same convention as lookupDeviceLocked).
  private suspend fun loadApprovalsLocked(session: Session, fid: String) {
    store.dispatch(ApprovalsRequested)
    try {
      val q = callWithRefresh(session) { authClient.familyApprovals(it.access, fid) }
      store.dispatch(ApprovalsLoaded(q.pending, q.invites))
    } catch (e: Exception) {
      store.dispatch(ApprovalsFailed)
    }
  }

  /** Owner: mint an invite (qr|link) → show it, then refresh outstanding. The raw
   *  token is dispatched to state for display only — never persisted or logged. */
  suspend fun mintInvite(fid: String, mode: String) = mutex.withLock {
    val session = store.state.session ?: return@withLock
    val maxUses = if (mode == "qr") 1 else 5   // qr forced to 1 server-side; link default 5 ("0 of 5 used")
    store.dispatch(MintRequested)
    try {
      when (val r = callWithRefresh(session) { authClient.mintInvite(it.access, fid, mode, maxUses) }) {
        is MintResult.Ok -> { store.dispatch(InviteMinted(r.invite)); loadApprovalsLocked(session, fid) }
        MintResult.RateLimited -> store.dispatch(MintFailed("ratelimited"))
        MintResult.Forbidden -> store.dispatch(MintFailed("forbidden"))
      }
    } catch (e: Exception) { store.dispatch(MintFailed("error")) }
  }

  /** Owner: revoke an outstanding invite → drop it on success; reload on a guarded failure. */
  suspend fun revokeInvite(fid: String, id: String) = mutex.withLock {
    val session = store.state.session ?: return@withLock
    store.dispatch(InviteRevokeRequested(id))
    try {
      callWithRefresh(session) { authClient.revokeInvite(it.access, fid, id) }
      store.dispatch(InviteRevoked(id))
    } catch (e: Exception) { loadApprovalsLocked(session, fid) }
  }

  /** Owner: approve / decline a pending member → drop them from the queue on success. */
  suspend fun approveMember(fid: String, uid: String) = resolveMember(fid, uid, approve = true)
  suspend fun declineMember(fid: String, uid: String) = resolveMember(fid, uid, approve = false)

  private suspend fun resolveMember(fid: String, uid: String, approve: Boolean) = mutex.withLock {
    val session = store.state.session ?: return@withLock
    store.dispatch(MemberOpRequested(uid))
    try {
      callWithRefresh(session) { if (approve) authClient.approveMember(it.access, fid, uid) else authClient.declineMember(it.access, fid, uid) }
      store.dispatch(MemberResolved(uid))
    } catch (e: Exception) {
      store.dispatch(ApprovalsFailed)
    }
  }

  /** Load the active member roster for a family. */
  suspend fun loadMembers(fid: String) = mutex.withLock {
    val session = store.state.session ?: return@withLock
    store.dispatch(RosterRequested)
    try {
      store.dispatch(RosterLoaded(callWithRefresh(session) { authClient.familyMembers(it.access, fid) }))
    } catch (e: Exception) { store.dispatch(RosterFailed("Couldn't load members. Try again.")) }
  }

  // Eager, quiet roster load so a checklist doneBy byline resolves to a name ANYWHERE the
  // content renders (not only after opening the Members screen). No RosterRequested/Failed
  // noise — a failure just leaves bylines on the "a family member" fallback. No-mutex core
  // (called from loadMembershipsLocked, which already holds the lock).
  private suspend fun loadRosterLocked(fid: String?) {
    val session = store.state.session ?: return
    if (fid.isNullOrEmpty()) return
    try { store.dispatch(RosterLoaded(callWithRefresh(session) { authClient.familyMembers(it.access, fid) })) }
    catch (e: Exception) { /* quiet */ }
  }

  /** Owner removes a member → drop from the roster on success (409 last-owner → reload). */
  suspend fun removeMember(fid: String, uid: String) = mutex.withLock {
    val session = store.state.session ?: return@withLock
    store.dispatch(MemberOpRequested(uid))
    try {
      callWithRefresh(session) { authClient.removeMember(it.access, fid, uid) }
      store.dispatch(MemberRemoved(uid))
    } catch (e: Exception) {
      store.dispatch(RosterLoaded(runCatching { callWithRefresh(session) { authClient.familyMembers(it.access, fid) } }.getOrDefault(store.state.members)))
    }
  }

  /** Load the caller's connected devices/apps. */
  suspend fun loadDevices() = mutex.withLock {
    val session = store.state.session ?: return@withLock
    store.dispatch(DevicesRequested)
    try { store.dispatch(DevicesLoaded(callWithRefresh(session) { authClient.credentials(it.access) })) }
    catch (e: Exception) { store.dispatch(DevicesFailed("Couldn't load devices. Try again.")) }
  }

  /** Revoke one of the caller's credentials → drop on success (reload on a guarded failure). */
  suspend fun revokeDevice(id: String) = mutex.withLock {
    val session = store.state.session ?: return@withLock
    store.dispatch(DeviceOpRequested(id))
    try {
      callWithRefresh(session) { authClient.revokeCredential(it.access, id) }
      store.dispatch(DeviceRevoked(id))
    } catch (e: Exception) {
      store.dispatch(DevicesLoaded(runCatching { callWithRefresh(session) { authClient.credentials(it.access) } }.getOrDefault(store.state.devices)))
    }
  }

  // ── CLI/device approval (S6-D) ──

  /** Look up a pending device grant by user_code (session-auth) → AuthorizeDevice. */
  suspend fun lookupDevice(code: String) = mutex.withLock { lookupDeviceLocked(code) }

  // Lookup core WITHOUT the mutex — callable from already-locked paths (the
  // deep-link resume runs inside restore()/signIn()'s lock; Mutex isn't reentrant).
  private suspend fun lookupDeviceLocked(code: String) {
    val session = store.state.session ?: return
    store.dispatch(DeviceLookupRequested)
    try {
      when (val r = callWithRefresh(session) { authClient.devicePending(it.access, code) }) {
        is DeviceLookupResult.Found -> store.dispatch(DevicePendingLoaded(r.device))
        DeviceLookupResult.NotFound -> store.dispatch(DeviceLookupNotFound)
        DeviceLookupResult.Locked -> store.dispatch(DeviceLookupFailed("Too many tries — wait about 15 minutes."))
      }
    } catch (e: Exception) {
      store.dispatch(DeviceLookupFailed("Couldn't check that code. Try again."))
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
    val code = parseDeviceCode(raw) ?: return
    if (store.state.session != null) lookupDevice(code)
    else store.dispatch(DeviceLinkStashed(code))
  }

  // If a deep-link code was stashed before sign-in, consume it and open the approve
  // screen now. Called at the tail of loadMembershipsLocked (already holding the mutex).
  private suspend fun resumePendingDeviceLink() {
    val code = store.state.pendingDeviceLink ?: return
    if (store.state.session == null) return
    store.dispatch(DeviceLinkConsumed)
    lookupDeviceLocked(code)
  }

  /** Owner approves the pending device against [fid] → DeviceApproved / expired / failed. */
  suspend fun approveDevice(fid: String, code: String) = mutex.withLock {
    val session = store.state.session ?: return@withLock
    store.dispatch(ApproveDeviceRequested)
    try {
      when (callWithRefresh(session) { authClient.deviceApprove(it.access, fid, code) }) {
        DeviceActionResult.Ok -> store.dispatch(DeviceApproved)
        DeviceActionResult.Expired -> store.dispatch(DeviceApproveExpired)
        DeviceActionResult.Locked -> store.dispatch(DeviceOpFailed("Too many tries — wait about 15 minutes."))
        DeviceActionResult.Forbidden -> store.dispatch(DeviceOpFailed("You're not an owner of that family."))
      }
    } catch (e: Exception) {
      store.dispatch(DeviceOpFailed("Couldn't approve. Try again."))
    }
  }

  /** Owner denies the pending device → DeviceDenied (Ok or already-gone) / failed. */
  suspend fun denyDevice(fid: String, code: String) = mutex.withLock {
    val session = store.state.session ?: return@withLock
    store.dispatch(DenyDeviceRequested)
    try {
      when (callWithRefresh(session) { authClient.deviceDeny(it.access, fid, code) }) {
        DeviceActionResult.Ok, DeviceActionResult.Expired -> store.dispatch(DeviceDenied)  // gone == denied
        DeviceActionResult.Locked -> store.dispatch(DeviceOpFailed("Too many tries — wait about 15 minutes."))
        DeviceActionResult.Forbidden -> store.dispatch(DeviceOpFailed("You're not an owner of that family."))
      }
    } catch (e: Exception) {
      store.dispatch(DeviceOpFailed("Couldn't deny. Try again."))
    }
  }

  /** Current access token (for the SyncClient token provider, wired at T6). */
  fun accessToken(): String? = store.state.session?.access

  // ── internals ──

  // [hadCache] (ADR 0052): true when a cached Feed is already on screen (the optimistic cold-start
  // path). It only changes the error terminal — a reachable-but-erroring/unreachable server must
  // NOT strand the user on AuthError when a usable cached view is already showing; AuthError is
  // reserved for the no-cache case (nothing to show). The 401 dead-session path is unconditional.
  private suspend fun loadMembershipsLocked(session: Session, hadCache: Boolean) {
    try {
      val who = callWithRefresh(session) { authClient.whoami(it.access) }
      store.dispatch(MembershipsLoaded(who.families))
      saveMemberships(who.families)   // ADR 0052 — persist the fresh list for the next cold start
      resumePendingDeviceLink()   // cold-install resume: open a link stashed pre-sign-in
      resumePendingInviteLink()   // ADR 0048: redeem an invite link stashed pre-sign-in
      loadRosterLocked(store.state.activeFamilyId)   // eager roster for doneBy bylines
    } catch (e: AuthHttpException) {
      // 401 here = access expired AND refresh couldn't recover (revoked/expired/
      // reused) → the saved session is dead. Clear it and fall back to Sign-in so
      // the spinner never wedges. Other statuses = a reachable-but-erroring server.
      if (e.status == 401) {
        tokenStore.clear()
        clearCache()             // dead session = same data boundary as logout — drop the cache (incl. memberships)
        store.dispatch(SessionExpired)
      } else if (!hadCache) {
        store.dispatch(RestoreFailed("Dayfold had a problem (HTTP ${e.status}). Tap retry."))
      }
      // else: cached Feed already showing → stay on it (ADR 0052 §3), reconcile again on next poll/foreground.
    } catch (e: Exception) {
      // Network/unknown → keep the session; offer Retry only when there's nothing cached to show.
      if (!hadCache) store.dispatch(RestoreFailed("Couldn't reach Dayfold. Check your connection and retry."))
      // else: stay on the cached Feed.
    }
  }

  /**
   * Run an access-token call; on 401 refresh once (rotate + persist + update
   * state) and retry. Any other failure (incl. a failed refresh) propagates.
   */
  private suspend fun <T> callWithRefresh(session: Session, block: suspend (Session) -> T): T =
    try {
      block(session)
    } catch (e: AuthHttpException) {
      if (e.status != 401) throw e
      val rotated = authClient.refresh(session.refresh)
      tokenStore.save(rotated)
      store.dispatch(SessionRotated(rotated))
      block(rotated)
    }
}
