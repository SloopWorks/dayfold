package com.sloopworks.dayfold.client

import kotlin.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// ADR 0020 R3 — the headless refresh pass both platforms call (Android WorkManager,
// iOS BGAppRefreshTask). Holds NO Redux store: a freshly-spawned background process has an
// empty SessionCoordinator, so family comes from the `membership` cache (ADR 0052) and
// credentials from TokenStore. Bounded because iOS grants ~30s per wake; safe to bound
// because SyncDrainer commits per page and the cursor only advances on commit.

/** Everything the pass touches, injected so it is testable with no platform and no network. */
class RefreshDeps(
  val memberships: () -> List<FamilyMembership>,
  val session: () -> Session?,
  /**
   * The live runtime's sync entry point when this process HAS one, else null; it reports whether it
   * actually synced. Delegating is mandatory, not an optimization: two independent refreshers race
   * refresh-token rotation, and the server's reuse detection revokes the lineage — signing the user
   * out. See Global Constraints.
   */
  val delegateToRuntime: (suspend () -> Boolean)?,
  val syncOnce: suspend (familyId: String, session: Session) -> Unit,
  val reconcile: () -> Unit,
)

/** What actually happened — for the Log line. Never rendered as a freshness promise.
 *  [synced] means content was actually refreshed, NOT merely that the sync step returned. */
data class RefreshOutcome(
  val synced: Boolean = false,
  val budgetExhausted: Boolean = false,
  val reconciled: Boolean = false,
  val delegated: Boolean = false,
  val skippedReason: String? = null,
)

/**
 * THE entry point for a headless wake that may WRITE to the cache (Android's RefreshWorker, iOS's
 * BGAppRefreshTask). Heals a cache written under an older content-schema
 * ([ContentStore.reconcileSchemaVersion], issue #283) and only then runs [backgroundRefreshPass].
 *
 * The ordering is not a nicety, it is the whole point. [ContentStore.applyDelta] ends by stamping
 * the cache with the RUNNING build's [CLIENT_SCHEMA_VERSION], and `reconcileSchemaVersion` skips
 * its wipe once the stored tag is >= the current one. So a headless drain onto an un-healed cache
 * stamps the new version over old-model rows and permanently disarms the one-shot heal: the app
 * updates overnight (3 → 4), WorkManager work survives the update, the worker fires before the user
 * ever opens the app, and the foreground `reconcileSchemaVersion` then reads 4 == 4 and does
 * nothing — leaving exactly the stale rows it exists to purge, with no second chance on that
 * install. Until this pass existed every headless path was read-only, which is why the foreground
 * callers ([SyncEngine.start], `DayfoldRuntimeFactory`) were the only ones that needed to heal.
 *
 * Runs on [databaseDispatcher] because the heal is a synchronous multi-statement DB transaction
 * (it enters the store's own write gate, so it cannot overlap a foreground writer).
 */
suspend fun headlessRefreshPass(
  contentStore: ContentStore,
  databaseDispatcher: CoroutineDispatcher,
  deps: RefreshDeps,
  budget: Duration,
): RefreshOutcome {
  withContext(databaseDispatcher) { contentStore.reconcileSchemaVersion() }
  return backgroundRefreshPass(deps, budget)
}

suspend fun backgroundRefreshPass(deps: RefreshDeps, budget: Duration): RefreshOutcome {
  // A live runtime owns the session and the cursor. Hand it the work and stop.
  deps.delegateToRuntime?.let { delegate ->
    // The delegate's OWN answer, not merely "it returned without throwing": it short-circuits when
    // no worker is bound yet (live-but-signed-out runtime) and its pass no-ops when no family is
    // bound. Reporting either as synced would make this pass's one log line lie.
    val synced = withTimeoutOrNull(budget) { runCatching { delegate() }.getOrDefault(false) }
    deps.reconcile()
    return RefreshOutcome(
      synced = synced == true, budgetExhausted = synced == null,
      reconciled = true, delegated = true,
    )
  }

  // Reuse the reducer's selection rule rather than restating it (Reducer.kt:23).
  // Known limitation: activeFamilyId is in-memory only, so a multi-family user's
  // explicit selection is not visible here — this picks the first active membership,
  // which is what the reducer does today.
  val familyId = activeFamilyIdFor(deps.memberships())
  val session = deps.session()
  if (familyId == null || session == null) {
    // Nothing to sync, but reconcile is local and still worth doing.
    deps.reconcile()
    return RefreshOutcome(reconciled = true, skippedReason = if (familyId == null) "no-family" else "no-session")
  }

  // Step 1 — sync. A timeout is NOT an error: the cursor makes the partial pass resumable,
  // so the next wake continues. Retrying here would spend the budget reconcile still needs.
  val completed = withTimeoutOrNull(budget) {
    runCatching { deps.syncOnce(familyId, session) }.isSuccess
  }

  // Step 2 (slice B1) — refreshForecasts() lands here.

  // Step 3 — reconcile ALWAYS runs, even when sync overran. Local, cheap, and stale
  // schedules are a worse failure than stale content.
  deps.reconcile()

  return RefreshOutcome(
    synced = completed == true,
    budgetExhausted = completed == null,
    reconciled = true,
  )
}

/**
 * Drain /sync with no Redux store. Uses the SAME [SyncDrainer] the foreground uses; the session
 * lambdas are pass-throughs because a background process has no epochs to fence against — it
 * holds one family and one credential for the life of the wake.
 *
 * A 401 refreshes once via [refreshAccess] and retries; a second failure gives up and lets the
 * next foreground open handle re-auth (a background wake must never drive a sign-out — see
 * the "NEVER agent-decided" reuse-detection note on AuthClient.refresh).
 *
 * Note: [SyncClient.fetchPage] throws [SyncHttpException] (not [AuthHttpException]) on a
 * non-200, including 401 — that's the type caught here.
 */
suspend fun headlessSync(
  contentStore: ContentStore,
  syncClient: SyncClient,
  databaseDispatcher: CoroutineDispatcher,
  familyId: String,
  session: Session,
  refreshAccess: suspend (refresh: String) -> Session?,
  nowIso: () -> String,
) {
  var current = session
  var refreshed = false
  SyncDrainer(
    contentStore = contentStore,
    databaseDispatcher = databaseDispatcher,
    nowIso = nowIso,
    fetch = { since ->
      try {
        syncClient.fetchPage(familyId, current.access, since)
      } catch (e: SyncHttpException) {
        // ONE refresh attempt. Only reachable when no live runtime exists (see the
        // delegation rule) so there is exactly one refresher and no rotation race.
        if (e.status != 401 || refreshed) throw e
        refreshed = true
        current = refreshAccess(current.refresh) ?: throw e
        syncClient.fetchPage(familyId, current.access, since)
      }
    },
    // No epochs to fence in a headless process: one family, one credential, one wake.
    commit = { block -> block(); true },
    onActivity = {},
  ).drain()
}
