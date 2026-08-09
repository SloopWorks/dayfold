package com.sloopworks.dayfold.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

// ADR 0020 R3 — the ONE /sync paging loop. Extracted from SyncEngine so the foreground pass
// and the headless background pass execute the same code (the "NO ENGINE FORK" invariant that
// BackgroundNotify.kt states for the notify path). Everything session-shaped is injected:
// the foreground supplies epoch-fenced authorize/commit (ADR 0058), the background supplies
// pass-throughs, and neither knows about the other.
class SyncDrainer(
  private val contentStore: ContentStore,
  private val databaseDispatcher: CoroutineDispatcher,
  private val nowIso: () -> String,
  /** Fetch one page. Foreground wraps this in the coordinator's authorizedCall. */
  private val fetch: suspend (since: String?) -> SyncResponse,
  /** Applies [block] iff the session is still current; false = replaced mid-pass. */
  private val commit: suspend (block: () -> Unit) -> Boolean,
  private val onActivity: () -> Unit,
) {
  /**
   * Drain pages until the server reports no more. Each page is its own atomic apply, and the
   * cursor only advances on commit — so cancelling mid-drain (a background wake running out of
   * budget) leaves a consistent cache that the next pass resumes from with no gap and no
   * double-pull. That property is what makes bounding the background pass safe.
   */
  suspend fun drain() {
    var hasMore = true
    while (hasMore) {
      val since = withContext(databaseDispatcher) { contentStore.cursor() }
      val resp = fetch(since)
      if (resp.hasMaterialChanges()) onActivity()
      // page -> DB is IDENTICAL in both paths, so it lives here concretely. Only the
      // session concerns (fetch/commit) are injected; duplicating the applyDelta
      // mapping at each call site is what this extraction exists to prevent.
      val committed = withContext(databaseDispatcher) {
        commit {
          // ADR 0040 stale-cursor directive: rebuild clean when the server reset the scan.
          if (resp.fullResync) contentStore.wipeForResync()
          contentStore.applyDelta(
            changedCards = resp.changes.cards,
            changedHubs = resp.changes.hubs,
            changedSections = resp.changes.sections,
            changedBlocks = resp.changes.blocks,
            tombstones = resp.tombstones,
            nextCursor = resp.nextCursor,
            nowIso = nowIso(),
            changedPlaces = resp.changes.places,
            changedResponses = resp.changes.responses.map { it.toDomain() },
          )
        }
      }
      if (!committed) throw CancellationException("Family session replaced")
      hasMore = resp.hasMore
    }
  }

  private fun SyncResponse.hasMaterialChanges(): Boolean =
    fullResync ||
      changes.cards.isNotEmpty() ||
      changes.hubs.isNotEmpty() ||
      changes.sections.isNotEmpty() ||
      changes.blocks.isNotEmpty() ||
      changes.places.isNotEmpty() ||
      // ADR 0064 — a rule arriving alone is material: it changes what the derived lane
      // surfaces, so the store must be reloaded even though no card or block moved.
      changes.responses.isNotEmpty() ||
      tombstones.isNotEmpty()
}
