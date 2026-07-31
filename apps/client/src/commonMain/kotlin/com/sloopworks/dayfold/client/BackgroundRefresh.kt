package com.sloopworks.dayfold.client

import kotlin.time.Duration
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
   * The live runtime's sync entry point when this process HAS one, else null.
   * Delegating is mandatory, not an optimization: two independent refreshers race
   * refresh-token rotation, and the server's reuse detection revokes the lineage —
   * signing the user out. See Global Constraints.
   */
  val delegateToRuntime: (suspend () -> Unit)?,
  val syncOnce: suspend (familyId: String, session: Session) -> Unit,
  val reconcile: () -> Unit,
)

/** What actually happened — for the Log line. Never rendered as a freshness promise. */
data class RefreshOutcome(
  val synced: Boolean = false,
  val budgetExhausted: Boolean = false,
  val reconciled: Boolean = false,
  val delegated: Boolean = false,
  val skippedReason: String? = null,
)

suspend fun backgroundRefreshPass(deps: RefreshDeps, budget: Duration): RefreshOutcome {
  // A live runtime owns the session and the cursor. Hand it the work and stop.
  deps.delegateToRuntime?.let { delegate ->
    val done = withTimeoutOrNull(budget) { runCatching { delegate() }.isSuccess }
    deps.reconcile()
    return RefreshOutcome(
      synced = done == true, budgetExhausted = done == null,
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
