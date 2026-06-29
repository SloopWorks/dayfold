@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.sloopworks.dayfold.client

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.db.SqlDriver
import com.sloopworks.dayfold.client.db.ContentDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val RELATED_SER = ListSerializer(RelatedRef.serializer())

// The local SQLDelight DB = the single source of truth (ADR 0020). The sync
// engine writes here; the UI projects from here. Driver is injected per platform
// (JdbcSqliteDriver desktop/test · AndroidSqliteDriver · NativeSqliteDriver iOS).
class ContentStore(driver: SqlDriver) {
  private val q = ContentDb(driver).contentQueries
  // Single JSON instance. payload/privacy are (de)serialized at the DB↔store
  // PROJECTION boundary (background dispatcher) — NOT during Compose
  // recomposition (the perf finding). Re-decoded per sync emission is fine
  // (≤200 rows; the store holds the decoded objects, the feed never sees JSON).
  private val json = Json { ignoreUnknownKeys = true }

  /**
   * Apply one /sync page atomically: upsert changes, tombstone deletes, advance cursor.
   *
   * INVARIANT — writes must stay serialized. The store wraps a single SQLite connection,
   * which cannot run two transactions at once: concurrent `applyDelta`/`wipe` calls from
   * different threads throw `SQLITE_ERROR: cannot start a transaction within a transaction`
   * (verified by a concurrency probe — see specs/web-async-db-migration-plan.md). `SyncEngine`
   * upholds this today by draining /sync pages one `applyDelta` at a time; do NOT parallelize
   * the write path without first giving the store a single-writer dispatcher.
   */
  fun applyDelta(
    changedCards: List<Card>,
    changedHubs: List<Hub>,
    changedSections: List<HubSection> = emptyList(),
    changedBlocks: List<HubBlock> = emptyList(),
    tombstones: List<Tombstone>,
    nextCursor: String?,
    nowIso: String,
  ) {
    q.transaction {
      changedCards.forEach { c ->
        q.upsertCard(
          c.id, c.kind, c.title, c.bodyMd, c.provenance?.source, c.notBefore, c.expiresAt,
          c.type,
          c.payload?.let { json.encodeToString(Payload.serializer(), it) },
          c.privacy?.let { json.encodeToString(CardPrivacy.serializer(), it) },
          c.hubRef,
          c.targetHubId, c.targetSectionId, c.targetBlockId,   // deep-link target (was dropped)
          c.related?.let { json.encodeToString(RELATED_SER, it) },
          c.relatedKicker,
          c.media?.let { json.encodeToString(CardMedia.serializer(), it) },   // ADR 0036
          nowIso,
        )
      }
      changedHubs.forEach { h ->
        q.upsertHub(
          h.id, h.type, h.title, h.status, h.startAt, h.endAt, h.countdownTo, h.visibility, h.createdBy,
          h.media?.let { json.encodeToString(HubMedia.serializer(), it) },     // ADR 0036
          nowIso,
        )
      }
      changedSections.forEach { s ->
        q.upsertSection(s.id, s.hubId ?: "", s.title, s.ord, nowIso)
      }
      changedBlocks.forEach { b ->
        // Per-block-type dispatch (ADR 0038 §5.4): a checklist block reconciles the
        // member-mutable done-triple against any pending LOCAL edit (merge); every other
        // block type is one-way → take remote. merge() is idempotent, so a /sync echo of
        // our own write can't flicker the value.
        val payloadToStore: BlockPayload? = if (b.type == "checklist" && b.payload != null) {
          val localPayload = q.blockById(b.id).executeAsOneOrNull()?.payload
            ?.let { decode(it, BlockPayload.serializer()) }
          if (localPayload != null)
            ChecklistMerge.mergeBlock(HubBlock(id = b.id, type = "checklist", payload = localPayload), b).payload
          else b.payload
        } else b.payload
        q.upsertBlock(
          b.id, b.sectionId ?: "", b.type, b.bodyMd,
          payloadToStore?.let { json.encodeToString(BlockPayload.serializer(), it) },
          b.provenance?.let { json.encodeToString(Provenance.serializer(), it) },
          b.ord, nowIso, b.version, b.createdBy,    // ADR 0038 §W4 — mirror the set-once author id
        )
        // Echo-suppress + reconcile (§5.5): drop the member's own acked op once the
        // server delivers its result version, then clear the pending flag if nothing is
        // still in flight for this block.
        q.dropAckedAtOrBelow(b.id, b.version)
        if (q.openOpsForTarget(b.id).executeAsOne() == 0L) q.clearBlockLocalState(b.id)
      }
      tombstones.forEach { t ->
        when (t.type) {
          "card"    -> q.markDeleted(nowIso, t.id)
          "hub"     -> q.markHubDeleted(nowIso, t.id)
          "section" -> q.markSectionDeleted(nowIso, t.id)
          "block"   -> q.markBlockDeleted(nowIso, t.id)
        }
      }
      if (nextCursor != null) q.setCursor(nextCursor, nowIso)
    }
  }

  private fun rowToCard(row: com.sloopworks.dayfold.client.db.ActiveCards): Card = Card(
    id = row.id, kind = row.kind, title = row.title, bodyMd = row.body_md,
    provenance = row.source?.let { Provenance(it) },
    notBefore = row.not_before, expiresAt = row.expires_at,
    type = row.type, hubRef = row.hub_ref,
    targetHubId = row.target_hub_id, targetSectionId = row.target_section_id, targetBlockId = row.target_block_id,
    payload = decode(row.payload, Payload.serializer()),
    privacy = decode(row.privacy, CardPrivacy.serializer()),
    related = decode(row.related, RELATED_SER), relatedKicker = row.related_kicker,
    media = decode(row.media, CardMedia.serializer()),   // ADR 0036
  )

  private fun rowToHub(r: com.sloopworks.dayfold.client.db.ActiveHubs): Hub = Hub(
    id = r.id, type = r.type, title = r.title, status = r.status ?: "active",
    startAt = r.start_at, endAt = r.end_at, countdownTo = r.countdown_to,
    visibility = r.visibility ?: "family", createdBy = r.created_by,
    media = decode(r.media, HubMedia.serializer()),   // ADR 0036
  )

  private fun rowToSection(r: com.sloopworks.dayfold.client.db.SectionsForHub): HubSection =
    HubSection(id = r.id, hubId = r.hub_id, title = r.title, ord = r.ord)

  private fun rowToBlock(r: com.sloopworks.dayfold.client.db.BlocksForSections): HubBlock =
    HubBlock(
      id = r.id, sectionId = r.section_id, type = r.type, bodyMd = r.body_md,
      payload = decode(r.payload, BlockPayload.serializer()),
      provenance = decode(r.provenance, Provenance.serializer()),
      ord = r.ord, version = r.version, localState = r.local_state, createdBy = r.created_by,
    )

  // Guarded decode: corrupt cached JSON must not crash the feed — skip → null,
  // the card still renders title/kind (ADR 0020 the DB cache is disposable).
  private fun <T> decode(text: String?, serializer: kotlinx.serialization.KSerializer<T>): T? =
    text?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }

  /** ADR 0030 (round-1 P0-2): hard-wipe the local cache on tenancy revocation — a
   *  removed/non-member must not retain family content. Drops cards + hubs + sections +
   *  blocks + cursor so a later sign-in re-syncs clean. */
  fun wipe() {
    q.transaction { q.wipeCards(); q.wipeHubs(); q.wipeSections(); q.wipeBlocks(); q.wipeCursor(); q.wipeOutbox(); q.wipeHidden() }
  }

  // ── Egress lane (ADR 0038/0039) — the outbox is WRITE-ONLY (the UI never reads it). ──

  /**
   * Optimistic apply for a member toggle (ADR 0038 §5.4 step 1): flip the item's
   * done-triple in the LOCAL block payload, mark the block pending, and enqueue ONE
   * coalesced outbox op carrying the whole-block PUT body + the If-Match base version.
   * One atomic transaction so the UI flip and the queued op can't diverge.
   */
  fun enqueueBlockToggle(blockId: String, itemId: String, done: Boolean, doneBy: String?, nowIso: String, opId: String) {
    q.transaction {
      val row = q.blockById(blockId).executeAsOneOrNull() ?: return@transaction
      val payload = row.payload?.let { decode(it, BlockPayload.serializer()) } ?: return@transaction
      val items = payload.items ?: return@transaction
      val merged = payload.copy(items = items.map {
        if (it.id == itemId) it.copy(done = done, doneBy = doneBy, doneAt = nowIso) else it
      })
      val payloadJson = json.encodeToString(BlockPayload.serializer(), merged)
      q.optimisticBlockUpdate(payloadJson, nowIso, "pending", blockId)
      val body = blockPutBody(row.section_id, row.type, payloadJson, row.provenance, nowIso)
      q.deletePendingForTarget(blockId, "toggle")               // coalesce N taps → one op
      q.enqueueOp(opId, "block", blockId, "toggle", body, row.version, null, nowIso)
    }
  }

  /**
   * Optimistic delete (ADR 0038 §W4): mark the block 'pending' ("Removing…") + keep the row
   * VISIBLE, and enqueue ONE coalesced "delete" outbox op. The row is removed only when the
   * inbound /sync tombstone confirms — honest + offline-correct (vs. the mockup's optimistic-
   * remove + undo; this reuses the five-rung vocabulary and survives an offline delete). On a
   * terminal failure the op parks 'failed' → FailedRetry, same as a toggle. The DELETE itself
   * carries no body + no If-Match (idempotent; the server is the author-gate, 5a).
   */
  fun enqueueBlockDelete(blockId: String, nowIso: String, opId: String) {
    q.transaction {
      q.blockById(blockId).executeAsOneOrNull() ?: return@transaction
      q.setBlockLocalState("pending", blockId)
      q.deletePendingForTarget(blockId, "delete")               // coalesce repeated delete taps
      q.enqueueOp(opId, "block", blockId, "delete", "", null, null, nowIso)  // no body, no base version
    }
  }

  /** The whole-block PUT body the server expects: { sectionId, type, payload, provenance }. */
  private fun blockPutBody(sectionId: String, type: String, payloadJson: String, provenanceJson: String?, nowIso: String): String {
    val payloadElem = runCatching { json.parseToJsonElement(payloadJson) }.getOrNull()
    val provElem = provenanceJson?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
      ?: json.parseToJsonElement("""{"source":"member","at":"$nowIso"}""")
    return kotlinx.serialization.json.buildJsonObject {
      put("sectionId", kotlinx.serialization.json.JsonPrimitive(sectionId))
      put("type", kotlinx.serialization.json.JsonPrimitive(type))
      if (payloadElem != null) put("payload", payloadElem)
      put("provenance", provElem)
    }.toString()
  }

  /** The next FIFO pending op, or null if the outbox is drained. */
  fun nextPendingOp(): OutboxOp? = q.pendingOps().executeAsList().firstOrNull()?.let {
    OutboxOp(it.op_id, it.target_kind, it.target_id, it.type, it.payload, it.base_version, it.attempts)
  }

  fun markOpInflight(opId: String) = q.markInflight(opId)
  fun ackOp(opId: String, resultVersion: Long?) = q.markAcked(resultVersion, opId)
  fun bumpOpAttempt(opId: String) = q.bumpAttempt(opId)

  /** Give up after the attempt cap: park the op 'failed' + surface a calm 'failed' on the block. */
  fun failOp(opId: String, targetId: String) { q.transaction { q.markFailed(opId); q.setBlockLocalState("failed", targetId) } }

  /** Drop an op the server said is unrecoverable (410/404/4xx); clear the block flag if idle. */
  fun dropOp(opId: String, targetId: String) {
    q.transaction {
      q.deleteOp(opId)
      if (q.openOpsForTarget(targetId).executeAsOne() == 0L) q.clearBlockLocalState(targetId)
    }
  }

  /** Manual Retry (Slice 4): re-arm a block's failed op(s) + flip it back to 'pending' so
   *  the next drainOutbox re-sends it. One transaction so the flag and the queue agree. */
  fun retryBlock(blockId: String) {
    q.transaction { q.retryFailedForTarget(blockId); q.setBlockLocalState("pending", blockId) }
  }

  /** Diagnostic: count of still-pending ops (egress backlog). */
  fun pendingOpCount(): Int = q.pendingOps().executeAsList().size
  /** Diagnostic: total outbox rows (pending + inflight + acked + failed). */
  fun outboxSize(): Long = q.outboxSize().executeAsOne()
  /** The optimistic-write flag on a block ('pending' | 'failed' | null = synced). */
  fun blockLocalState(blockId: String): String? = q.blockById(blockId).executeAsOneOrNull()?.local_state

  /**
   * 412 re-merge (§5.4 step 4): re-base the op from the CURRENT local block — after the
   * inbound /sync already merged the fresh remote into it, so the payload carries the
   * member's surviving toggle on top of the loop's latest base + version. Bumps the attempt.
   */
  fun rebaseOpFromLocal(opId: String, targetId: String, nowIso: String) {
    q.transaction {
      val row = q.blockById(targetId).executeAsOneOrNull() ?: run { q.deleteOp(opId); return@transaction }
      val payloadJson = row.payload ?: "{}"
      val body = blockPutBody(row.section_id, row.type, payloadJson, row.provenance, nowIso)
      q.requeueOp(row.version, body, opId)
    }
  }

  /** Reactive hub projection — emits current active hubs and re-emits on any hub-table write. */
  fun activeHubsFlow(): Flow<List<Hub>> =
    q.activeHubs().asFlow().mapToList(Dispatchers.Default).map { rows -> rows.map(::rowToHub) }

  /**
   * Reactive hub tree flow — emits the full HubTree for [hubId] and re-emits on any
   * change to hub, section, or block tables. Emits null if the hub is absent/tombstoned.
   * Uses flatMapLatest so changing sections re-subscribes the block query correctly.
   */
  fun hubTreeFlow(hubId: String): Flow<HubTree?> =
    q.activeHubs().asFlow().mapToList(Dispatchers.Default).flatMapLatest { hubRows ->
      val hub = hubRows.firstOrNull { it.id == hubId }?.let(::rowToHub)
        ?: return@flatMapLatest flowOf(null)
      q.sectionsForHub(hubId).asFlow().mapToList(Dispatchers.Default).flatMapLatest { sectionRows ->
        val sections = sectionRows.map(::rowToSection)
        if (sections.isEmpty()) {
          flowOf(HubTree(hub = hub, sections = emptyList(), blocks = emptyList()))
        } else {
          val sectionIds = sections.map { it.id }
          q.blocksForSections(sectionIds).asFlow().mapToList(Dispatchers.Default).map { blockRows ->
            HubTree(hub = hub, sections = sections, blocks = blockRows.map(::rowToBlock))
          }
        }
      }
    }

  // ── W5 hide (ADR 0038 §W5) — LOCAL-ONLY personal view filter. NEVER synced (not in
  // applyDelta, not in the outbox); hide ≠ ACL, so hidden content keeps syncing normally.

  /** Hide an entity for this device only (idempotent; updates the stamp on re-hide). */
  fun hide(entityId: String, nowIso: String) = q.hideEntity(entityId, nowIso)

  /** Un-hide — bring it back into the visible view. */
  fun unhide(entityId: String) = q.unhideEntity(entityId)

  /** Reactive set of hidden entity ids — re-emits on any hide/unhide. The screen partitions
   *  the tree against this (the "Hidden for you" section + "Show hidden" toggle). */
  fun hiddenIdsFlow(): Flow<Set<String>> =
    q.hiddenIds().asFlow().mapToList(Dispatchers.Default).map { it.toSet() }

  /** Feed projection: live cards, not_before NULLS LAST then id (the API contract). */
  fun activeCards(): List<Card> = q.activeCards().executeAsList().map(::rowToCard)

  /** Reactive feed projection — emits current active cards and re-emits on any card-table write. */
  fun activeCardsFlow(): Flow<List<Card>> =
    q.activeCards().asFlow().mapToList(Dispatchers.Default).map { rows -> rows.map(::rowToCard) }

  fun cursor(): String? = q.getCursor().executeAsOneOrNull()?.cursor

  companion object {
    fun create(driver: SqlDriver): ContentStore {
      ContentDb.Schema.create(driver)
      return ContentStore(driver)
    }
  }
}
