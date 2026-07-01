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
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val RELATED_SER = ListSerializer(RelatedRef.serializer())
private val TRIGGERS_SER = ListSerializer(BlockTrigger.serializer())   // ADR 0043 — block triggers JSON list

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
    changedPlaces: List<Place> = emptyList(),   // ADR 0043 Phase A — named places (geo-proximity source)
  ) {
    q.transaction {
      changedCards.forEach { c ->
        q.upsertCard(
          c.id, c.kind, c.title, c.bodyMd, c.provenance?.source, c.notBefore, c.expiresAt,
          c.importance,
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
          h.timeline?.let { json.encodeToString(Timeline.serializer(), it) },  // ADR 0045
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
          b.triggers?.let { json.encodeToString(TRIGGERS_SER, it) },   // ADR 0043 — on-device trigger metadata
        )
        // Echo-suppress + reconcile (§5.5): drop the member's own acked op once the
        // server delivers its result version, then clear the pending flag if nothing is
        // still in flight for this block.
        q.dropAckedAtOrBelow(b.id, b.version)
        if (q.openOpsForTarget(b.id).executeAsOne() == 0L) q.clearBlockLocalState(b.id)
      }
      changedPlaces.forEach { p ->
        q.upsertPlace(p.id, p.kind, p.label, p.lat, p.lng, p.radiusM, nowIso)
      }
      tombstones.forEach { t ->
        when (t.type) {
          "card"    -> q.markDeleted(nowIso, t.id)
          "hub"     -> q.markHubDeleted(nowIso, t.id)
          "section" -> q.markSectionDeleted(nowIso, t.id)
          "block"   -> q.markBlockDeleted(nowIso, t.id)
          "place"   -> q.markPlaceDeleted(nowIso, t.id)
        }
      }
      if (nextCursor != null) q.setCursor(nextCursor, nowIso)
    }
  }

  private fun rowToCard(row: com.sloopworks.dayfold.client.db.ActiveCards): Card = Card(
    id = row.id, kind = row.kind, title = row.title, bodyMd = row.body_md,
    provenance = row.source?.let { Provenance(it) },
    notBefore = row.not_before, expiresAt = row.expires_at, importance = row.importance,
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
    media = decode(r.media, HubMedia.serializer()),       // ADR 0036
    timeline = decode(r.timeline, Timeline.serializer()), // ADR 0045
  )

  private fun rowToSection(r: com.sloopworks.dayfold.client.db.SectionsForHub): HubSection =
    HubSection(id = r.id, hubId = r.hub_id, title = r.title, ord = r.ord)

  private fun rowToBlock(r: com.sloopworks.dayfold.client.db.BlocksForSections): HubBlock =
    HubBlock(
      id = r.id, sectionId = r.section_id, type = r.type, bodyMd = r.body_md,
      payload = decode(r.payload, BlockPayload.serializer()),
      provenance = decode(r.provenance, Provenance.serializer()),
      ord = r.ord, version = r.version, localState = r.local_state, createdBy = r.created_by,
      triggers = decode(r.triggers, TRIGGERS_SER),   // ADR 0043 — on-device trigger metadata
    )

  private fun rowToPlace(r: com.sloopworks.dayfold.client.db.ActivePlaces): Place =
    Place(id = r.id, kind = r.kind, label = r.label, lat = r.lat, lng = r.lng, radiusM = r.radius_m)

  // Guarded decode: corrupt cached JSON must not crash the feed — skip → null,
  // the card still renders title/kind (ADR 0020 the DB cache is disposable).
  private fun <T> decode(text: String?, serializer: kotlinx.serialization.KSerializer<T>): T? =
    text?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }

  /** ADR 0030 (round-1 P0-2): hard-wipe the local cache on tenancy revocation — a
   *  removed/non-member must not retain family content. Drops cards + hubs + sections +
   *  blocks + cursor so a later sign-in re-syncs clean. */
  fun wipe() {
    q.transaction { q.wipeCards(); q.wipeHubs(); q.wipeSections(); q.wipeBlocks(); q.wipeCursor(); q.wipeOutbox(); q.wipeHidden(); q.wipePlaces(); q.wipeSurfacing(); q.wipeNotifConfig(); q.wipeNotificationLog() }
  }

  /** ADR 0040 §3 — stale-cursor full-resync wipe. Clears the SYNCED content + the cursor so the
   *  server's from-∞ rebuild replaces it, but PRESERVES the outbox (a staleness reset must not
   *  drop queued member writes — unlike the tenancy-revocation [wipe]) and the local-only hidden
   *  set (the re-synced entities keep their personal hide). */
  fun wipeForResync() {
    // Places are synced content → drop them (the rebuild page re-delivers them). surfacing_state is
    // LOCAL-ONLY personal anti-nag history → PRESERVED (parity with `hidden`; not wiped here).
    q.transaction { q.wipeCards(); q.wipeHubs(); q.wipeSections(); q.wipeBlocks(); q.wipeCursor(); q.wipePlaces() }
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

  /** ADR 0043 — reactive named-places projection (geo-proximity source for the deriver). */
  fun activePlacesFlow(): Flow<List<Place>> =
    q.activePlaces().asFlow().mapToList(Dispatchers.Default).map { rows -> rows.map(::rowToPlace) }

  private fun rowToNowSection(r: com.sloopworks.dayfold.client.db.AllSections): HubSection =
    HubSection(id = r.id, hubId = r.hub_id, title = r.title, ord = r.ord)

  private fun rowToNowBlock(r: com.sloopworks.dayfold.client.db.AllBlocks): HubBlock =
    HubBlock(
      id = r.id, sectionId = r.section_id, type = r.type, bodyMd = r.body_md,
      payload = decode(r.payload, BlockPayload.serializer()),
      provenance = decode(r.provenance, Provenance.serializer()),
      ord = r.ord, version = r.version, localState = r.local_state, createdBy = r.created_by,
      triggers = decode(r.triggers, TRIGGERS_SER),
    )

  /**
   * ADR 0043 Phase A — the deriveNow candidate bundle (all live sections + blocks + places across
   * hubs), as ONE reactive projection (one bridge, one writer). Re-emits on any content change.
   * Combined so the derived feed sees a consistent snapshot rather than three racing emissions.
   */
  fun nowContentFlow(): Flow<NowContent> {
    val sections = q.allSections().asFlow().mapToList(Dispatchers.Default).map { it.map(::rowToNowSection) }
    val blocks = q.allBlocks().asFlow().mapToList(Dispatchers.Default).map { it.map(::rowToNowBlock) }
    val places = activePlacesFlow()
    return kotlinx.coroutines.flow.combine(sections, blocks, places) { s, b, p -> NowContent(s, b, p) }
  }

  /** ADR 0043 §2b — reactive LOCAL-ONLY surfacing state (last-shown/dismissed), keyed by subjectKey. */
  fun surfacingFlow(): Flow<Map<String, SurfacingRecord>> =
    q.allSurfacing().asFlow().mapToList(Dispatchers.Default).map { rows ->
      rows.associate { it.subject_key to SurfacingRecord(it.subject_key, it.last_shown_at, it.dismissed_at) }
    }

  /** Record that a subject was surfaced (anti-nag decay clock). LOCAL-ONLY — never synced. */
  fun recordShown(subjectKey: String, nowIso: String) = q.recordShown(subjectKey, nowIso)

  /**
   * START the anti-nag clock ONCE for a subject (the render-driven record-shown effect, ADR 0043
   * §2b). Write-if-new: preserves any existing last_shown so continuous visibility doesn't keep
   * resetting the decay/soften clock. LOCAL-ONLY — never synced.
   */
  fun recordShownIfNew(subjectKey: String, nowIso: String) = q.recordShownIfNew(subjectKey, nowIso)

  /** Record that a subject was dismissed (omit it from future ranking). LOCAL-ONLY — never synced. */
  fun recordDismissed(subjectKey: String, nowIso: String) = q.recordDismissed(subjectKey, nowIso)

  // ── Phase B notification state (ADR 0044) — DEVICE-LOCAL, NEVER synced (ADR 0024). ──

  /** Reactive background-proximity config (DB→store bridge feeds state.notifConfig). Absent → default-off. */
  fun notifConfigFlow(): Flow<NotifConfig> =
    q.notifConfigRow().asFlow().mapToList(Dispatchers.Default).map { rows -> rows.firstOrNull()?.toNotifConfig() ?: NotifConfig() }

  /** Synchronous config snapshot — for the headless background worker (no store/Compose). Absent → default-off. */
  fun notifConfig(): NotifConfig = q.notifConfigRow().executeAsOneOrNull()?.toNotifConfig() ?: NotifConfig()

  /** Persist the (device-local) config. UI→DB→flow→NotifConfigLoaded (no optimistic UI→store path). */
  fun setNotifConfig(c: NotifConfig) =
    q.setNotifConfig(if (c.enabled) 1L else 0L, c.quietStartMinuteOfDay.toLong(), c.quietEndMinuteOfDay.toLong(), c.dailyCap.toLong())

  /** Append one posted-notification row (drives the daily cap + same-day dedup). LOCAL-ONLY. */
  fun logNotification(subjectKey: String, nowIso: String) = q.logNotification(subjectKey, nowIso)

  /** The cap/dedup view for [nowIso]'s local date: posts-today count + the set of subjects already
   *  notified today. Pure rollover (postedTodayCount) — no midnight reset job, survives process death. */
  fun notifLedger(nowIso: String, zone: TimeZone): NotifLedger {
    val rows = q.recentNotifications().executeAsList()
    val today = parseInstantFlexible(nowIso, zone)?.toLocalDateTime(zone)?.date
    val todaysSubjects = rows
      .filter { today != null && parseInstantFlexible(it.notified_at, zone)?.toLocalDateTime(zone)?.date == today }
      .map { it.subject_key }
    return NotifLedger(
      postedToday = postedTodayCount(rows.map { it.notified_at }, nowIso, zone),
      notifiedSubjects = todaysSubjects.toSet(),
    )
  }

  private fun com.sloopworks.dayfold.client.db.NotifConfigRow.toNotifConfig() =
    NotifConfig(enabled = enabled == 1L, quietStartMinuteOfDay = quiet_start.toInt(), quietEndMinuteOfDay = quiet_end.toInt(), dailyCap = daily_cap.toInt())

  // ── Synchronous snapshot getters (ADR 0044 §S3) — the headless background pass reads these from the
  //    SAME process-shared connection (no Store, no 2nd connection; WAL lets it read under a fg write).
  //    Sync variants of the reactive projections above (activeHubsFlow/nowContentFlow/surfacingFlow).

  /** Live hubs (sync). */
  fun activeHubs(): List<Hub> = q.activeHubs().executeAsList().map(::rowToHub)
  /** All live sections across hubs (sync). */
  fun allSections(): List<HubSection> = q.allSections().executeAsList().map(::rowToNowSection)
  /** All live blocks across hubs (sync). */
  fun allBlocks(): List<HubBlock> = q.allBlocks().executeAsList().map(::rowToNowBlock)
  /** Live named places (sync; geo-proximity source). */
  fun activePlaces(): List<Place> = q.activePlaces().executeAsList().map(::rowToPlace)
  /** LOCAL-ONLY surfacing state (sync). */
  fun surfacing(): Map<String, SurfacingRecord> =
    q.allSurfacing().executeAsList().associate { it.subject_key to SurfacingRecord(it.subject_key, it.last_shown_at, it.dismissed_at) }
  /** The device-local notification_log as rows (sync; drives cap rollover + dedup). */
  fun notificationLog(): List<NotifLogRow> =
    q.recentNotifications().executeAsList().map { NotifLogRow(it.subject_key, it.notified_at) }

  /**
   * The full synchronous bundle the background notification pass needs (ADR 0044 §S3) — gathered in one
   * read from the single shared connection, never a 2nd. The worker hands this to
   * planBackgroundNotifications, which builds a minimal AppState and reuses nowFeed + selectNotifications.
   */
  fun notifSnapshot(): NotifSnapshot = NotifSnapshot(
    cards = activeCards(),
    hubs = activeHubs(),
    sections = allSections(),
    blocks = allBlocks(),
    places = activePlaces(),
    surfacing = surfacing(),
    config = notifConfig(),
    log = notificationLog(),
  )

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
