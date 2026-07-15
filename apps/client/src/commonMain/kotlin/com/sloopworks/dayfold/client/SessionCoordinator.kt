package com.sloopworks.dayfold.client

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Opaque compare-and-set ticket for a replaceable restore or sign-in operation. */
class AuthOperationContext internal constructor(
  internal val identityEpoch: Long,
  internal val operationRevision: Long,
) {
  /** Returns a redacted representation safe for diagnostics. */
  override fun toString(): String = "AuthOperationContext(<redacted>)"
}

/**
 * An immutable identity-session snapshot.
 *
 * Credentials are intentionally available only through [withAccessToken] and the
 * coordinator's refresh callback. This type must never be put in Redux state,
 * DevTools data, SWIP payloads, or logs.
 */
class AuthSessionContext internal constructor(
  internal val identityEpoch: Long,
  internal val credentialRevision: Long,
  private val session: Session,
) {
  /** Runs an authorized transport call without exposing credentials as properties. */
  suspend fun <T> withAccessToken(call: suspend (accessToken: String) -> T): T =
    call(session.access)

  internal suspend fun refreshWith(call: suspend (refreshToken: String) -> Session): Session =
    call(session.refresh)

  internal fun actorUserId(): String? = session.userId

  internal fun rotated(session: Session): AuthSessionContext = AuthSessionContext(
    identityEpoch = identityEpoch,
    credentialRevision = credentialRevision + 1L,
    session = session,
  )

  /** Returns a credential-redacted representation safe for diagnostics. */
  override fun toString(): String = "AuthSessionContext(<redacted>)"
}

/**
 * An immutable identity-and-family snapshot for tenant-bound work.
 *
 * Capture one instance for an entire network/DB operation and validate it with
 * [SessionCoordinator.isCurrent] immediately before family-scoped publication.
 */
class FamilySessionContext internal constructor(
  internal val authContext: AuthSessionContext,
  internal val familyId: String,
  internal val familyRevision: Long,
) {
  /** Runs a tenant-bound transport call without exposing credentials as properties. */
  suspend fun <T> withFamilyAndAccessToken(
    call: suspend (familyId: String, accessToken: String) -> T,
  ): T = authContext.withAccessToken { accessToken -> call(familyId, accessToken) }

  /** Returns the authenticated actor id used only for client-authored attribution. */
  internal fun actorUserId(): String? = authContext.actorUserId()

  /** Returns a credential- and tenant-redacted representation safe for diagnostics. */
  override fun toString(): String = "FamilySessionContext(<redacted>)"
}

/**
 * Owns atomic identity/family snapshots and serializes access-token refresh.
 *
 * [refreshScope] is owned by the runtime. Refresh is launched in that scope so a
 * cancelled caller does not cancel a refresh still awaited by other callers.
 * [invalidate] cancels the active epoch's refresh for every waiter.
 * [commitRotation] must synchronously persist and publish the new session; it is
 * invoked exactly once while rotation is serialized. Refresh-driven rotation runs
 * in [refreshScope]; direct callers must likewise stay off the UI thread if the
 * commit performs blocking persistence.
 */
class SessionCoordinator(
  private val refreshScope: CoroutineScope,
  private val refreshSession: suspend (AuthSessionContext) -> Session,
  private val commitRotation: (Session) -> Unit,
) {
  private data class State(
    val epoch: Long = 0L,
    val auth: AuthSessionContext? = null,
    val familyId: String? = null,
    val familyRevision: Long = 0L,
    val authOperationRevision: Long = 0L,
  )

  private class RefreshFlight(
    val expected: AuthSessionContext,
    val result: Deferred<AuthSessionContext?>,
  )

  private class InstallResult(
    val context: AuthSessionContext,
    val staleFlight: RefreshFlight?,
  )

  private val gate = SynchronizedObject()
  private var state = State()
  private var refreshFlight: RefreshFlight? = null

  /** Installs a different login, allocates a new identity epoch, and clears family selection. */
  fun install(session: Session): AuthSessionContext {
    val result = synchronized(gate) { installLocked(session) }
    result.staleFlight?.result?.cancel(SessionInvalidatedException())
    return result.context
  }

  /**
   * Starts a replaceable restore/sign-in operation. A later ticket or identity invalidation makes
   * this ticket stale, preventing a blocked network result from unconditionally installing.
   */
  fun beginAuthOperation(): AuthOperationContext = synchronized(gate) {
    val revision = nextRevision(state.authOperationRevision)
    state = state.copy(authOperationRevision = revision)
    AuthOperationContext(state.epoch, revision)
  }

  /**
   * Installs a login only if [expected] is still the latest auth operation in its identity epoch.
   * Network-driven restore and sign-in paths must use this overload rather than [install].
   */
  fun install(expected: AuthOperationContext, session: Session): AuthSessionContext? {
    val result = synchronized(gate) {
      if (!isCurrentLocked(expected)) return null
      installLocked(session)
    }
    result.staleFlight?.result?.cancel(SessionInvalidatedException())
    return result.context
  }

  /** Returns the complete current identity snapshot, or null when signed out. */
  fun authSnapshot(): AuthSessionContext? = synchronized(gate) { state.auth }

  /**
   * Atomically selects (or clears) a family within [expected]'s identity session.
   * Returns null when clearing selection or when [expected] is stale.
   */
  fun selectFamily(expected: AuthSessionContext, familyId: String?): FamilySessionContext? =
    synchronized(gate) {
      val currentAuth = state.auth
      if (currentAuth == null || currentAuth.identityEpoch != expected.identityEpoch) {
        return@synchronized null
      }
      val selected = familyId?.takeIf(String::isNotBlank)
      val revision = nextRevision(state.familyRevision)
      state = state.copy(auth = currentAuth, familyId = selected, familyRevision = revision)
      selected?.let { FamilySessionContext(currentAuth, it, revision) }
    }

  /** Returns a family snapshot only when [familyId] is the currently selected tenant. */
  fun familySnapshot(familyId: String): FamilySessionContext? = synchronized(gate) {
    val auth = state.auth
    if (auth == null || state.familyId != familyId) {
      null
    } else {
      FamilySessionContext(auth, familyId, state.familyRevision)
    }
  }

  /**
   * Atomically rotates credentials in [expected]'s epoch without changing identity or family.
   * Returns null, without committing, when [expected] is not the active credential revision.
   */
  fun rotate(expected: AuthSessionContext, session: Session): AuthSessionContext? =
    synchronized(gate) {
      if (state.auth !== expected) return@synchronized null
      val rotated = expected.rotated(session)
      state = state.copy(auth = rotated)
      try {
        // Publish after installing the new context so synchronous observers cannot see Redux's
        // rotated session paired with the old coordinator credentials.
        commitRotation(session)
      } catch (error: Exception) {
        // The server-side refresh token may already be consumed. Do not restore credentials that
        // can no longer be trusted when local persistence/publication fails.
        state = State(
          epoch = nextEpoch(state.epoch),
          familyRevision = nextRevision(state.familyRevision),
          authOperationRevision = nextRevision(state.authOperationRevision),
        )
        throw error
      }
      rotated.takeIf { state.auth === it }
    }

  /**
   * Invalidates the active identity, advances its epoch, clears family selection, and cancels
   * its in-flight refresh. Supplying a stale [expectedEpoch] makes this a no-op.
   */
  fun invalidate(expectedEpoch: Long? = null): AuthSessionContext? {
    val (invalidated, staleFlight) = synchronized(gate) {
      if (expectedEpoch != null && state.epoch != expectedEpoch) return null
      val previous = state.auth
      state = State(
        epoch = nextEpoch(state.epoch),
        familyRevision = nextRevision(state.familyRevision),
        authOperationRevision = nextRevision(state.authOperationRevision),
      )
      val previousFlight = refreshFlight
      refreshFlight = null
      previous to previousFlight
    }
    staleFlight?.result?.cancel(SessionInvalidatedException())
    return invalidated
  }

  /**
   * Invalidates only when [expected] is still the active identity and family generation.
   * This is the terminal boundary for tenant-scoped 401/403 work: an old family's late failure
   * must never expire a newer family selected within the same authenticated identity.
   */
  fun invalidate(expected: FamilySessionContext): AuthSessionContext? {
    val (invalidated, staleFlight) = synchronized(gate) {
      if (
        state.auth?.identityEpoch != expected.authContext.identityEpoch ||
        state.familyId != expected.familyId ||
        state.familyRevision != expected.familyRevision
      ) return null
      val previous = state.auth
      state = State(
        epoch = nextEpoch(state.epoch),
        familyRevision = nextRevision(state.familyRevision),
        authOperationRevision = nextRevision(state.authOperationRevision),
      )
      val previousFlight = refreshFlight
      refreshFlight = null
      previous to previousFlight
    }
    staleFlight?.result?.cancel(SessionInvalidatedException())
    return invalidated
  }

  /**
   * Invalidates an identity and performs its bounded local terminal commit before another
   * install/rotation can enter the coordinator. Call from a background dispatcher when [commit]
   * performs blocking token or database cleanup.
   */
  fun invalidateAndCommit(
    expectedEpoch: Long? = null,
    commit: () -> Unit,
  ): AuthSessionContext? {
    var invalidated: AuthSessionContext? = null
    var staleFlight: RefreshFlight? = null
    try {
      synchronized(gate) {
        if (expectedEpoch != null && state.epoch != expectedEpoch) return null
        invalidated = state.auth
        state = State(
          epoch = nextEpoch(state.epoch),
          familyRevision = nextRevision(state.familyRevision),
          authOperationRevision = nextRevision(state.authOperationRevision),
        )
        staleFlight = refreshFlight
        refreshFlight = null
        commit()
      }
    } finally {
      staleFlight?.result?.cancel(SessionInvalidatedException())
    }
    return invalidated
  }

  /**
   * Executes [call], refreshes once after a 401, then retries with current credentials.
   *
   * If another caller already rotated [context], this retries that same-epoch snapshot before
   * entering refresh. Concurrent 401s for the same current credentials share one refresh.
   */
  suspend fun <T> authorizedCall(
    context: AuthSessionContext,
    call: suspend (AuthSessionContext) -> T,
  ): T {
    var attempted = context
    var retriedStaleContext = false

    while (true) {
      // Reject an invalidated identity before any transport side effect. Credential rotation does
      // not make the captured context stale: currentness for auth work is identity-epoch based.
      currentInEpoch(attempted) ?: throw SessionInvalidatedException()
      try {
        return call(attempted)
      } catch (error: Exception) {
        if (!error.isUnauthorized()) throw error

        val current = currentInEpoch(attempted) ?: throw error
        if (current !== attempted && !retriedStaleContext) {
          attempted = current
          retriedStaleContext = true
          continue
        }

        if (current !== attempted) throw error
        val refreshed = refresh(current).await() ?: throw error
        currentInEpoch(refreshed) ?: throw SessionInvalidatedException()
        return call(refreshed)
      }
    }
  }

  /**
   * Executes a tenant-bound [call] with the same family generation across token refresh.
   * A refresh substitutes only the current credential revision; family replacement or an
   * A-to-B-to-A cycle cancels the operation instead of reviving its captured context.
   */
  suspend fun <T> authorizedCall(
    context: FamilySessionContext,
    call: suspend (FamilySessionContext) -> T,
  ): T {
    val initial = currentFamilyContext(context) ?: throw SessionInvalidatedException()
    return authorizedCall(initial.authContext) { currentAuth ->
      val currentFamily = currentFamilyContext(context, currentAuth)
        ?: throw SessionInvalidatedException()
      call(currentFamily)
    }
  }

  /** Returns true while [context]'s identity epoch remains active, including after rotation. */
  fun isCurrent(context: AuthSessionContext): Boolean = synchronized(gate) {
    state.auth?.identityEpoch == context.identityEpoch
  }

  /** Returns true only while [context] is the latest replaceable auth operation. */
  fun isCurrent(context: AuthOperationContext): Boolean = synchronized(gate) {
    isCurrentLocked(context)
  }

  /** Returns true while both [context]'s identity epoch and family remain active. */
  fun isCurrent(context: FamilySessionContext): Boolean = synchronized(gate) {
    state.auth?.identityEpoch == context.authContext.identityEpoch &&
      state.familyId == context.familyId &&
      state.familyRevision == context.familyRevision
  }

  /**
   * Runs [commit] atomically with identity-epoch validation.
   *
   * The block is non-suspending and executes under the coordinator gate, closing the gap between
   * a currentness check and Redux/token publication. Keep it bounded and run blocking persistence
   * from an appropriate background caller.
   */
  fun commitIfCurrent(context: AuthSessionContext, commit: () -> Unit): Boolean =
    synchronized(gate) {
      if (state.auth?.identityEpoch != context.identityEpoch) return@synchronized false
      commit()
      true
    }

  /** Atomically publishes a replaceable restore/sign-in result only while its ticket is current. */
  fun commitIfCurrent(context: AuthOperationContext, commit: () -> Unit): Boolean =
    synchronized(gate) {
      if (!isCurrentLocked(context)) return@synchronized false
      commit()
      true
    }

  /**
   * Runs [commit] atomically with identity-epoch, family, and family-generation validation.
   * This prevents invalidation or family replacement from interleaving between validation and a
   * family DB/Redux publication.
   */
  fun commitIfCurrent(context: FamilySessionContext, commit: () -> Unit): Boolean =
    synchronized(gate) {
      if (
        state.auth?.identityEpoch != context.authContext.identityEpoch ||
        state.familyId != context.familyId ||
        state.familyRevision != context.familyRevision
      ) {
        return@synchronized false
      }
      commit()
      true
    }

  private fun currentInEpoch(context: AuthSessionContext): AuthSessionContext? = synchronized(gate) {
    state.auth?.takeIf { it.identityEpoch == context.identityEpoch }
  }

  private fun currentFamilyContext(
    expected: FamilySessionContext,
    auth: AuthSessionContext? = null,
  ): FamilySessionContext? = synchronized(gate) {
    val currentAuth = state.auth ?: return@synchronized null
    if (auth != null && currentAuth.identityEpoch != auth.identityEpoch) return@synchronized null
    if (
      currentAuth.identityEpoch != expected.authContext.identityEpoch ||
      state.familyId != expected.familyId ||
      state.familyRevision != expected.familyRevision
    ) {
      return@synchronized null
    }
    FamilySessionContext(auth ?: currentAuth, expected.familyId, expected.familyRevision)
  }

  private fun isCurrentLocked(context: AuthOperationContext): Boolean =
    state.epoch == context.identityEpoch && state.authOperationRevision == context.operationRevision

  private fun installLocked(session: Session): InstallResult {
    val nextEpoch = nextEpoch(state.epoch)
    val installed = AuthSessionContext(nextEpoch, credentialRevision = 0L, session)
    state = State(
      epoch = nextEpoch,
      auth = installed,
      familyRevision = nextRevision(state.familyRevision),
      authOperationRevision = nextRevision(state.authOperationRevision),
    )
    val previousFlight = refreshFlight
    refreshFlight = null
    return InstallResult(installed, previousFlight)
  }

  private fun refresh(expected: AuthSessionContext): Deferred<AuthSessionContext?> {
    lateinit var created: Deferred<AuthSessionContext?>
    synchronized(gate) {
      refreshFlight?.takeIf { it.expected === expected }?.let { return it.result }
      if (state.auth !== expected) return completedNullRefresh()

      created = refreshScope.async(start = CoroutineStart.LAZY) {
        try {
          val session = refreshSession(expected)
          currentCoroutineContext().ensureActive()
          rotate(expected, session) ?: currentInEpoch(expected)
        } finally {
          synchronized(gate) {
            if (refreshFlight?.result === created) refreshFlight = null
          }
        }
      }
      refreshFlight = RefreshFlight(expected, created)
    }
    created.start()
    return created
  }

  private fun completedNullRefresh(): Deferred<AuthSessionContext?> =
    CompletableDeferred(null)

  private fun nextEpoch(epoch: Long): Long = if (epoch == Long.MAX_VALUE) 1L else epoch + 1L

  private fun nextRevision(revision: Long): Long =
    if (revision == Long.MAX_VALUE) 1L else revision + 1L
}

/**
 * Creates a shared coordinator without exposing refresh credentials to platform composition roots.
 *
 * Platform hosts use this compatibility factory until they construct [DayfoldRuntimeFactory]
 * directly. [commitRotation] must persist and publish the rotated session synchronously.
 */
fun createSessionCoordinator(
  refreshScope: CoroutineScope,
  authClient: AuthClient,
  commitRotation: (Session) -> Unit,
): SessionCoordinator = SessionCoordinator(
  refreshScope = refreshScope,
  refreshSession = { context -> context.refreshWith(authClient::refresh) },
  commitRotation = commitRotation,
)

private class SessionInvalidatedException : CancellationException("Session invalidated")

private fun Exception.isUnauthorized(): Boolean = when (this) {
  is AuthHttpException -> status == 401
  is SyncHttpException -> status == 401
  else -> false
}
