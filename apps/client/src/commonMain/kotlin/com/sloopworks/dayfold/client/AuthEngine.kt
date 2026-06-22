package com.sloopworks.dayfold.client

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
) {
  private val mutex = Mutex()

  /** Cold-start: restore a saved session (if any) and resolve memberships. */
  suspend fun restore() = mutex.withLock {
    store.dispatch(AuthRestoring)
    val saved = tokenStore.load()
    if (saved == null) {
      store.dispatch(SessionRestored(null))   // → SignIn
      return@withLock
    }
    store.dispatch(SessionRestored(saved))     // → Loading
    loadMemberships(saved)                      // 401 → refresh-and-retry
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
      loadMemberships(session)
    } catch (e: Exception) {
      store.dispatch(SignInFailed(e.message ?: "Sign-in failed"))
    }
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
    store.dispatch(SignedOut)
  }

  /** Redeem an invite token (slice-2): success = a pending membership awaiting
   *  owner approval; everything else maps to a join-result the UI renders. */
  suspend fun redeemInvite(token: String) = mutex.withLock {
    store.dispatch(RedeemRequested(token))
    val session = store.state.session
    if (session == null) { store.dispatch(InviteRejected("error")); return@withLock }
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

  /** Owner: load the pending-approval queue for a family. */
  suspend fun loadApprovals(fid: String) = mutex.withLock {
    val session = store.state.session ?: return@withLock
    store.dispatch(ApprovalsRequested)
    try {
      store.dispatch(ApprovalsLoaded(callWithRefresh(session) { authClient.familyApprovals(it.access, fid) }))
    } catch (e: Exception) {
      store.dispatch(ApprovalsFailed)
    }
  }

  /** Owner: approve / decline a pending member → drop them from the queue on success. */
  suspend fun approveMember(fid: String, uid: String) = resolveMember(fid, uid, approve = true)
  suspend fun declineMember(fid: String, uid: String) = resolveMember(fid, uid, approve = false)

  private suspend fun resolveMember(fid: String, uid: String, approve: Boolean) = mutex.withLock {
    val session = store.state.session ?: return@withLock
    try {
      callWithRefresh(session) { if (approve) authClient.approveMember(it.access, fid, uid) else authClient.declineMember(it.access, fid, uid) }
      store.dispatch(MemberResolved(uid))
    } catch (e: Exception) {
      store.dispatch(ApprovalsFailed)   // already-handled / transient → the next load reconciles
    }
  }

  /** Load the active member roster for a family. */
  suspend fun loadMembers(fid: String) = mutex.withLock {
    val session = store.state.session ?: return@withLock
    try {
      store.dispatch(RosterLoaded(callWithRefresh(session) { authClient.familyMembers(it.access, fid) }))
    } catch (e: Exception) { /* keep the last roster; a retry/load reconciles */ }
  }

  /** Owner removes a member → drop from the roster on success (409 last-owner → reload). */
  suspend fun removeMember(fid: String, uid: String) = mutex.withLock {
    val session = store.state.session ?: return@withLock
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
    try { store.dispatch(DevicesLoaded(callWithRefresh(session) { authClient.credentials(it.access) })) }
    catch (e: Exception) { /* keep the last list; a reload reconciles */ }
  }

  /** Revoke one of the caller's credentials → drop on success (reload on a guarded failure). */
  suspend fun revokeDevice(id: String) = mutex.withLock {
    val session = store.state.session ?: return@withLock
    try {
      callWithRefresh(session) { authClient.revokeCredential(it.access, id) }
      store.dispatch(DeviceRevoked(id))
    } catch (e: Exception) {
      store.dispatch(DevicesLoaded(runCatching { callWithRefresh(session) { authClient.credentials(it.access) } }.getOrDefault(store.state.devices)))
    }
  }

  /** Current access token (for the SyncClient token provider, wired at T6). */
  fun accessToken(): String? = store.state.session?.access

  // ── internals ──

  private suspend fun loadMemberships(session: Session) {
    try {
      val who = callWithRefresh(session) { authClient.whoami(it.access) }
      store.dispatch(MembershipsLoaded(who.families))
    } catch (e: AuthHttpException) {
      // 401 here = access expired AND refresh couldn't recover (revoked/expired/
      // reused) → the saved session is dead. Clear it and fall back to Sign-in so
      // the spinner never wedges. Other statuses = a reachable-but-erroring server.
      if (e.status == 401) {
        tokenStore.clear()
        store.dispatch(SessionExpired)
      } else {
        store.dispatch(RestoreFailed("Dayfold had a problem (HTTP ${e.status}). Tap retry."))
      }
    } catch (e: Exception) {
      // Network/unknown → keep the session, offer Retry (don't strand on Loading).
      store.dispatch(RestoreFailed("Couldn't reach Dayfold. Check your connection and retry."))
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
