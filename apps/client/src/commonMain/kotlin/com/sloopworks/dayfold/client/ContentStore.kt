@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.sloopworks.dayfold.client

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.db.SqlDriver
import com.sloopworks.dayfold.client.db.ContentDb
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val RELATED_SER = ListSerializer(RelatedRef.serializer())
private val TRIGGERS_SER = ListSerializer(BlockTrigger.serializer())   // ADR 0043 — block triggers JSON list
private val CALENDAR_IDS_SER = ListSerializer(String.serializer())     // ADR 0063 §1 — selected device calendar ids

// Issue #283 — the client CONTENT-schema version. BUMP THIS BY HAND whenever a synced model
// gains (or changes) a BEHAVIOR-affecting field, so devices upgrading over an older cache force a
// full resync ([ContentStore.reconcileSchemaVersion]) instead of silently rendering rows the old
// model wrote without the field. NOT ContentDb.Schema.version (that tracks the SQLite schema and
// bumps for additive cache tables too — too aggressive to resync on).
//   v1: ChecklistItem.id became render-behavior (checklist interactivity + LWW merge, ADR 0038).
//   v2: one-shot purge of debug SampleData seed rows (ids s_*) that older debug builds upserted
//       into the shared prod-synced DB — the server never emits those ids so it can never tombstone
//       them, and incremental sync never prunes undelivered rows. reconcileSchemaVersion wipes
//       synced content+cursor once so the next sync full-rehydrates from the server (truth). The
//       seed source was removed (MainActivity), so this is a durable heal, not a recurring one.
const val CLIENT_SCHEMA_VERSION: Long = 3L   // 2→3 (#299): cards now carry decoded triggers (when/geo)

// The local SQLDelight DB = the single source of truth (ADR 0020). The sync
// engine writes here; the UI projects from here. Driver is injected per platform
// (JdbcSqliteDriver desktop/test · AndroidSqliteDriver · NativeSqliteDriver iOS).
class ContentStore(driver: SqlDriver) {
  private val q = ContentDb(driver).contentQueries
  // One process-shared ContentStore is used from foreground sync, UI callbacks, and headless
  // notification work. SQLDelight's single connection is not a concurrent writer, so every
  // public mutation and every multi-query notification snapshot shares this reentrant gate.
  // Ordinary single-query reads stay lock-free and retain SQLite's read concurrency.
  private val writeGate = SynchronizedObject()
  // Deterministic test seam for proving the multi-query Now snapshot stays under [writeGate].
  // Production leaves it null; keeping it internal avoids widening ContentStore's public API.
  internal var nowSnapshotStageHook: ((Int) -> Unit)? = null
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
   * (verified by a concurrency probe — see specs/web-async-db-migration-plan.md). Every public
   * mutation enters this instance's reentrant write gate, so foreground and headless writers
   * cannot overlap even if their callers run on different dispatchers. Keep new compound writes
   * behind `withWriteGate`; ordinary single-query reads remain outside the gate.
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
    changedResponses: List<ContentResponse> = emptyList(),  // ADR 0064 — Tier-1 mute/done rows
  ) = withWriteGate {
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
          c.triggers?.let { json.encodeToString(TRIGGERS_SER, it) },          // ADR 0043/0049 (#299)
          nowIso,
        )
      }
      changedHubs.forEach { h ->
        q.upsertHub(
          h.id, h.type, h.title, h.status, h.startAt, h.endAt, h.countdownTo, h.visibility, h.createdBy,
          h.version,
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
      changedResponses.forEach { r ->
        // The server row is the synced truth: land it with pending cleared. The echo is what
        // ends the optimistic state, exactly as the block path clears local_state.
        q.upsertResponse(
          r.id, r.kind.wire, r.subjectRef, r.matchScope.wire, r.audienceScope.wire,
          r.userId, r.createdBy, r.label, r.sublabel, r.note, r.version, 0L,
        )
        // Echo-suppress: the write came back, so its acked op is done.
        q.dropAckedForTarget(r.id)
      }
      tombstones.forEach { t ->
        when (t.type) {
          "card"    -> q.markDeleted(nowIso, t.id)
          "hub"     -> q.markHubDeleted(nowIso, t.id)
          "section" -> q.markSectionDeleted(nowIso, t.id)
          "block"   -> q.markBlockDeleted(nowIso, t.id)
          "place"   -> q.markPlaceDeleted(nowIso, t.id)
          // A rule tombstone is a hard delete, not a soft one: there is no "removed rule"
          // surface to render, and a personal rule reaches non-owners ONLY as a tombstone.
          "response" -> { q.deleteResponse(t.id); q.dropAckedForTarget(t.id) }
        }
      }
      if (nextCursor != null) q.setCursor(nextCursor, nowIso)
      // Issue #283 — tag the cache with the schema this build wrote it under, so a future build
      // that adds a behavior-affecting field can detect an older cache and force a resync
      // ([reconcileSchemaVersion]). Stamping at the write keeps the tag meaning exactly "the
      // schema of the content currently cached".
      q.setSchemaVersion(CLIENT_SCHEMA_VERSION)
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
    triggers = decode(row.triggers, TRIGGERS_SER),        // ADR 0043/0049 (#299)
  )

  private fun rowToHub(r: com.sloopworks.dayfold.client.db.ActiveHubs): Hub = Hub(
    id = r.id, type = r.type, title = r.title, status = r.status ?: "active",
    startAt = r.start_at, endAt = r.end_at, countdownTo = r.countdown_to,
    visibility = r.visibility ?: "family", createdBy = r.created_by, version = r.version,
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
    withWriteGate {
      q.transaction {
        q.wipeCards(); q.wipeHubs(); q.wipeSections(); q.wipeBlocks(); q.wipeCursor()
        q.wipeOutbox(); q.wipeHidden(); q.wipePlaces(); q.wipeSurfacing()
        q.wipeNotificationLog(); q.wipeMembership()
        // ADR 0064 — rules are family content, so tenancy revocation drops them with the rest.
        // response_offer is device-local Tier 0, but it keys on subjects of a family this
        // device may no longer read; keeping it would leak "this household had a card here".
        q.wipeResponses(); q.wipeResponseOffers()
        // ADR 0063 §3 — calendar_binding keys on subjects of a family this device may no longer
        // read (same reasoning as response_offer above), so a removed/non-member drops it too.
        q.wipeCalendarBindings()
        // CAL-10 (ADR 0063 §6) — an in-flight import's proposal record names a destination hub id
        // in this family; same reasoning as calendar_binding above.
        q.wipeCalendarImports()
        // notif_config and calendar_settings are device preferences, not tenant data. They
        // deliberately survive sign-out/family replacement; the device bridges remain active.
      }
    }
  }

  /** ADR 0040 §3 — stale-cursor full-resync wipe. Clears the SYNCED content + the cursor so the
   *  server's from-∞ rebuild replaces it, but PRESERVES the outbox (a staleness reset must not
   *  drop queued member writes — unlike the tenancy-revocation [wipe]) and the local-only hidden
   *  set (the re-synced entities keep their personal hide). */
  fun wipeForResync() {
    // Places are synced content → drop them (the rebuild page re-delivers them). surfacing_state is
    // LOCAL-ONLY personal anti-nag history → PRESERVED (parity with `hidden`; not wiped here).
    withWriteGate { q.transaction { wipeSyncedContent() } }
  }

  // The synced-content deletes shared by [wipeForResync] and [reconcileSchemaVersion]. NOT
  // transactional itself — callers wrap it (so a wipe + version-stamp commit atomically).
  // NOTE (ADR 0052): the `membership` cache is deliberately NOT wiped here — it is not synced
  // content a content-schema bump can malform, so it must survive reconcileSchemaVersion (else
  // every schema bump reverts cold start to network-gated). It IS cleared by the full [wipe]
  // (tenancy revocation) — the same boundary as cards.
  private fun wipeSyncedContent() {
    q.wipeCards(); q.wipeHubs(); q.wipeSections(); q.wipeBlocks(); q.wipeCursor(); q.wipePlaces()
    // ADR 0064 — synced family content, so a from-∞ rebuild replaces it too. response_offer is
    // NOT wiped here: it is device-local anti-nag history, like `hidden` and surfacing_state,
    // and a staleness reset must not re-offer an escalation the member already declined.
    q.wipeResponses()
  }

  // ── ADR 0064 — smart-content responses (Tier 1, SYNCED) + the Tier-0 offer flag.

  fun allResponses(): List<ContentResponse> =
    q.allResponses().executeAsList().map {
      ContentResponse(
        id = it.id,
        kind = ResponseKind.of(it.kind),
        subjectRef = it.subject_ref,
        matchScope = MatchScope.of(it.match_scope),
        audienceScope = AudienceScope.of(it.audience_scope),
        userId = it.user_id,
        createdBy = it.created_by,
        label = it.label,
        sublabel = it.sublabel,
        note = it.note,
        version = it.version,
        pending = it.pending != 0L,
      )
    }

  /** Optimistic local write — lands at once, `pending` until the /sync echo clears it. */
  fun upsertResponseLocal(r: ContentResponse) = withWriteGate {
    q.upsertResponse(
      r.id, r.kind.wire, r.subjectRef, r.matchScope.wire, r.audienceScope.wire,
      r.userId, r.createdBy, r.label, r.sublabel, r.note, r.version, if (r.pending) 1L else 0L,
    )
  }

  fun deleteResponseLocal(id: String) = withWriteGate { q.deleteResponse(id) }

  /**
   * Queue a response op. `target_kind = "response"` routes it to the response endpoints in the
   * drain loop; `type` is "upsert" or "delete". base_version is 0 — a response takes no
   * If-Match: there is no local merge to re-run, and the server's identity columns are
   * immutable after creation, so a stale base has nothing to clobber.
   */
  fun enqueueResponseOp(opId: String, id: String, type: String, payload: String, nowIso: String) =
    withWriteGate {
      q.enqueueOp(opId, "response", id, type, payload, 0L, null, nowIso)
    }

  /** Undo — drop the queued write entirely. Works offline: nothing has left the device yet. */
  fun dropQueuedOpsFor(targetId: String) = withWriteGate { q.deleteOpsForTarget(targetId) }

  /** Tier-0, never synced: has this subject already been offered the escalation, ever? */
  fun wasResponseOffered(subjectRef: String): Boolean =
    q.wasResponseOffered(subjectRef).executeAsOne() > 0L

  fun recordResponseOffer(subjectRef: String, nowIso: String) = withWriteGate {
    q.recordResponseOffer(subjectRef, nowIso)
  }

  // ── Membership cache (ADR 0052) — last-known family list for the DB-first cold-start route
  // gate. Read once at cold start (AuthEngine.restore); replaced on every whoami. AuthEngine
  // reaches these through seam lambdas so it keeps no ContentStore dependency.
  fun cachedMemberships(): List<FamilyMembership> =
    q.allMemberships().executeAsList().map { FamilyMembership(it.family_id, it.name, it.role, it.status) }

  /** Replace the whole cached family list (whoami returns the full set — replace, don't merge). */
  fun replaceMemberships(families: List<FamilyMembership>) {
    withWriteGate {
      q.transaction {
        q.wipeMembership()
        families.forEach { q.upsertMembership(it.familyId, it.name, it.role, it.status) }
      }
    }
  }

  /** Issue #283 — heal rows an older content-model wrote. If the cache was last synced under an
   *  OLDER [CLIENT_SCHEMA_VERSION], the incremental cursor can't backfill a since-added
   *  behavior-affecting field (an older model dropped it via ignoreUnknownKeys and advanced the
   *  cursor past those rows), so force a full resync: wipe synced content + cursor (keeping the
   *  outbox + local hidden, [wipeForResync] semantics) and stamp the current version — atomically,
   *  so a crash mid-way just re-heals next launch. Fresh/legacy DB → NULL → 0 → heal once (a no-op
   *  wipe on empty tables). Call BEFORE the first sync. */
  fun reconcileSchemaVersion(current: Long = CLIENT_SCHEMA_VERSION) {
    withWriteGate {
      val stored = q.getSchemaVersion().executeAsOneOrNull()?.client_schema_version ?: 0L
      if (stored >= current) return@withWriteGate
      q.transaction { wipeSyncedContent(); q.setSchemaVersion(current) }
    }
  }

  /** The client content-schema version the cache was last synced under (issue #283); 0 when the
   *  row is absent or predates #283. */
  fun schemaVersion(): Long = q.getSchemaVersion().executeAsOneOrNull()?.client_schema_version ?: 0L

  // ── Egress lane (ADR 0038/0039) — the outbox is WRITE-ONLY (the UI never reads it). ──

  /**
   * Optimistic apply for a member toggle (ADR 0038 §5.4 step 1): flip the item's
   * done-triple in the LOCAL block payload, mark the block pending, and enqueue ONE
   * coalesced outbox op carrying the whole-block PUT body + the If-Match base version.
   * One atomic transaction so the UI flip and the queued op can't diverge.
   */
  fun enqueueBlockToggle(blockId: String, itemId: String, done: Boolean, doneBy: String?, nowIso: String, opId: String) {
    withWriteGate {
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
    withWriteGate {
      q.transaction {
        q.blockById(blockId).executeAsOneOrNull() ?: return@transaction
        q.setBlockLocalState("pending", blockId)
        q.deletePendingForTarget(blockId, "delete")               // coalesce repeated delete taps
        q.enqueueOp(opId, "block", blockId, "delete", "", null, null, nowIso)  // no body, no base version
      }
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

  /** The next FIFO pending op whose dependency is satisfied, or null if none is sendable. */
  fun nextPendingOp(): OutboxOp? = q.pendingOps().executeAsList().firstOrNull()?.let {
    it.toOutboxOp()
  }

  /**
   * Atomically selects and marks the next FIFO, dependency-ready operation inflight.
   *
   * Enqueue/coalescing uses the same process-safe writer gate, while the SQL transaction prevents
   * a selected row from being observed as pending between selection and claim.
   */
  fun claimNextPendingOp(): OutboxOp? = withWriteGate {
    var claimed: OutboxOp? = null
    q.transaction {
      val row = q.pendingOps().executeAsList().firstOrNull() ?: return@transaction
      q.markInflight(row.op_id)
      claimed = row.toOutboxOp()
    }
    claimed
  }

  fun markOpInflight(opId: String) = withWriteGate { q.markInflight(opId) }
  fun recoverInflightOps() = withWriteGate { q.recoverInflight() }
  fun ackOp(opId: String, resultVersion: Long?) = withWriteGate { q.markAcked(resultVersion, opId) }
  fun bumpOpAttempt(opId: String) = withWriteGate { q.bumpAttempt(opId) }

  /**
   * Acks [opId] and advances a newer pending toggle for [targetId] to [resultVersion] atomically.
   *
   * Returns true when a same-target toggle exists but the server omitted its result version. The
   * caller must stop this pass so the next requested pass pulls a trustworthy base before sending.
   */
  fun ackOpAndAdvanceSuccessor(
    opId: String,
    targetId: String,
    resultVersion: Long?,
    nowIso: String,
  ): Boolean = withWriteGate {
    var needsRepull = false
    q.transaction {
      q.markAcked(resultVersion, opId)
      if (resultVersion != null) {
        q.advanceBlockVersion(resultVersion, targetId, resultVersion)
      }
      val successor = q.pendingOps().executeAsList().firstOrNull {
        it.target_id == targetId && it.type == "toggle"
      } ?: return@transaction
      if (resultVersion == null) {
        needsRepull = true
        return@transaction
      }
      val row = q.blockById(targetId).executeAsOneOrNull() ?: return@transaction
      val payloadJson = row.payload ?: "{}"
      val body = blockPutBody(row.section_id, row.type, payloadJson, row.provenance, nowIso)
      q.advancePendingToggle(resultVersion, body, targetId)
    }
    needsRepull
  }

  /** Give up after the attempt cap: park the op 'failed' + surface a calm 'failed' on the block. */
  fun failOp(opId: String, targetId: String) {
    withWriteGate { q.transaction { q.markFailed(opId); q.setBlockLocalState("failed", targetId) } }
  }

  /** Drop an op the server said is unrecoverable (410/404/4xx); clear the block flag if idle. */
  fun dropOp(opId: String, targetId: String) {
    withWriteGate {
      q.transaction {
        q.deleteOp(opId)
        if (q.openOpsForTarget(targetId).executeAsOne() == 0L) q.clearBlockLocalState(targetId)
      }
    }
  }

  /** Manual Retry (Slice 4): re-arm a block's failed op(s) + flip it back to 'pending' so
   *  the next drainOutbox re-sends it. One transaction so the flag and the queue agree. */
  fun retryBlock(blockId: String) {
    withWriteGate { q.transaction { q.retryFailedForTarget(blockId); q.setBlockLocalState("pending", blockId) } }
  }

  /** Diagnostic: count of still-pending ops (egress backlog). */
  fun pendingOpCount(): Int = q.pendingOpCount().executeAsOne().toInt()
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
    withWriteGate {
      q.transaction {
        val row = q.blockById(targetId).executeAsOneOrNull() ?: run { q.deleteOp(opId); return@transaction }
        val payloadJson = row.payload ?: "{}"
        val body = blockPutBody(row.section_id, row.type, payloadJson, row.provenance, nowIso)
        q.requeueOp(row.version, body, opId)
      }
    }
  }

  private fun com.sloopworks.dayfold.client.db.Outbox.toOutboxOp(): OutboxOp =
    OutboxOp(op_id, target_kind, target_id, type, payload, base_version, attempts)

  /** Reactive hub projection — emits current active hubs and re-emits on any hub-table write. */
  fun activeHubsFlow(dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default): Flow<List<Hub>> =
    q.activeHubs().asFlow().mapToList(dispatcher).map { rows -> rows.map(::rowToHub) }

  /**
   * Reactive hub tree flow — emits the full HubTree for [hubId] and re-emits on any
   * change to hub, section, or block tables. Emits null if the hub is absent/tombstoned.
   * Uses flatMapLatest so changing sections re-subscribes the block query correctly.
   */
  fun hubTreeFlow(
    hubId: String,
    dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
  ): Flow<HubTree?> =
    q.activeHubs().asFlow().mapToList(dispatcher).flatMapLatest { hubRows ->
      val hub = hubRows.firstOrNull { it.id == hubId }?.let(::rowToHub)
        ?: return@flatMapLatest flowOf(null)
      q.sectionsForHub(hubId).asFlow().mapToList(dispatcher).flatMapLatest { sectionRows ->
        val sections = sectionRows.map(::rowToSection)
        if (sections.isEmpty()) {
          flowOf(HubTree(hub = hub, sections = emptyList(), blocks = emptyList()))
        } else {
          val sectionIds = sections.map { it.id }
          q.blocksForSections(sectionIds).asFlow().mapToList(dispatcher).map { blockRows ->
            HubTree(hub = hub, sections = sections, blocks = blockRows.map(::rowToBlock))
          }
        }
      }
    }

  // ── W5 hide (ADR 0038 §W5) — LOCAL-ONLY personal view filter. NEVER synced (not in
  // applyDelta, not in the outbox); hide ≠ ACL, so hidden content keeps syncing normally.

  /** Hide an entity for this device only (idempotent; updates the stamp on re-hide). */
  fun hide(entityId: String, nowIso: String) = withWriteGate { q.hideEntity(entityId, nowIso) }

  /** Un-hide — bring it back into the visible view. */
  fun unhide(entityId: String) = withWriteGate { q.unhideEntity(entityId) }

  /** Reactive set of hidden entity ids — re-emits on any hide/unhide. The screen partitions
   *  the tree against this (the "Hidden for you" section + "Show hidden" toggle). */
  fun hiddenIdsFlow(dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default): Flow<Set<String>> =
    q.hiddenIds().asFlow().mapToList(dispatcher).map { it.toSet() }

  /** ADR 0043 — reactive named-places projection (geo-proximity source for the deriver). */
  fun activePlacesFlow(dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default): Flow<List<Place>> =
    q.activePlaces().asFlow().mapToList(dispatcher).map { rows -> rows.map(::rowToPlace) }

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
   * hubs), as one writer-consistent reactive projection. Query invalidations are only wake-ups;
   * every emission re-reads all three tables under [writeGate], so it is entirely before or after
   * a compound content write rather than a `combine` of independently-versioned table reads.
   */
  fun nowContentFlow(dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default): Flow<NowContent> {
    val invalidations = merge(
      q.allSections().asFlow().map { Unit },
      q.allBlocks().asFlow().map { Unit },
      q.activePlaces().asFlow().map { Unit },
    )
    return invalidations
      .conflate()
      .mapLatest { withContext(dispatcher) { nowContentSnapshot() } }
      .distinctUntilChanged()
  }

  private fun nowContentSnapshot(): NowContent = withWriteGate {
    val sections = q.allSections().executeAsList().map(::rowToNowSection)
    nowSnapshotStageHook?.invoke(1)
    val blocks = q.allBlocks().executeAsList().map(::rowToNowBlock)
    nowSnapshotStageHook?.invoke(2)
    val places = q.activePlaces().executeAsList().map(::rowToPlace)
    nowSnapshotStageHook?.invoke(3)
    NowContent(sections, blocks, places)
  }

  /** ADR 0043 §2b — reactive LOCAL-ONLY surfacing state (last-shown/dismissed), keyed by subjectKey. */
  fun surfacingFlow(dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default): Flow<Map<String, SurfacingRecord>> =
    q.allSurfacing().asFlow().mapToList(dispatcher).map { rows ->
      rows.associate { it.subject_key to SurfacingRecord(it.subject_key, it.last_shown_at, it.dismissed_at) }
    }

  /**
   * ADR 0064 — the reactive rules projection. Family-scoped SYNCED content, so it rides the
   * family bridge alongside cards/hubs, not the device bridge that carries notif config.
   */
  fun responsesFlow(dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default): Flow<List<ContentResponse>> =
    q.allResponses().asFlow().mapToList(dispatcher).map { rows ->
      rows.map {
        ContentResponse(
          id = it.id,
          kind = ResponseKind.of(it.kind),
          subjectRef = it.subject_ref,
          matchScope = MatchScope.of(it.match_scope),
          audienceScope = AudienceScope.of(it.audience_scope),
          userId = it.user_id,
          createdBy = it.created_by,
          label = it.label,
          sublabel = it.sublabel,
          note = it.note,
          version = it.version,
          pending = it.pending != 0L,
        )
      }
    }

  /** Record that a subject was surfaced (anti-nag decay clock). LOCAL-ONLY — never synced. */
  fun recordShown(subjectKey: String, nowIso: String) = withWriteGate { q.recordShown(subjectKey, nowIso) }

  /**
   * START the anti-nag clock ONCE for a subject (the render-driven record-shown effect, ADR 0043
   * §2b). Write-if-new: preserves any existing last_shown so continuous visibility doesn't keep
   * resetting the decay/soften clock. LOCAL-ONLY — never synced.
   */
  fun recordShownIfNew(subjectKey: String, nowIso: String) = withWriteGate { q.recordShownIfNew(subjectKey, nowIso) }

  /** Record that a subject was dismissed (omit it from future ranking). LOCAL-ONLY — never synced. */
  fun recordDismissed(subjectKey: String, nowIso: String) = withWriteGate { q.recordDismissed(subjectKey, nowIso) }

  // ── Phase B notification state (ADR 0044) — DEVICE-LOCAL, NEVER synced (ADR 0024). ──

  /** Reactive background-proximity config (DB→store bridge feeds state.notifConfig). Absent → default-off. */
  fun notifConfigFlow(dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default): Flow<NotifConfig> =
    q.notifConfigRow().asFlow().mapToList(dispatcher).map { rows -> rows.firstOrNull()?.toNotifConfig() ?: NotifConfig() }

  /** Synchronous config snapshot — for the headless background worker (no store/Compose). Absent → default-off. */
  fun notifConfig(): NotifConfig = q.notifConfigRow().executeAsOneOrNull()?.toNotifConfig() ?: NotifConfig()

  /** Persist the (device-local) config. UI→DB→flow→NotifConfigLoaded (no optimistic UI→store path). */
  fun setNotifConfig(c: NotifConfig) =
    withWriteGate {
      q.setNotifConfig(if (c.enabled) 1L else 0L, c.quietStartMinuteOfDay.toLong(), c.quietEndMinuteOfDay.toLong(), c.dailyCap.toLong())
    }

  /** Append one posted-notification row (drives the daily cap + same-day dedup). LOCAL-ONLY. */
  fun logNotification(subjectKey: String, nowIso: String) = withWriteGate { q.logNotification(subjectKey, nowIso) }

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

  // ── Calendar reconciliation (ADR 0063 §3) — DEVICE-LOCAL, NEVER synced (ADR 0024). calendar_binding
  // is wiped on wipe() (below, tenancy revocation) but deliberately NOT touched by wipeSyncedContent()
  // — it must survive a full-resync for the same signed-in family.

  fun calendarBindingBySubjectKey(subjectKey: String): CalendarBinding? =
    q.calendarBindingBySubjectKey(subjectKey).executeAsOneOrNull()?.toCalendarBinding()

  fun calendarBindingsByRelation(relation: CalendarRelation): List<CalendarBinding> =
    q.calendarBindingsByRelation(relation.wire).executeAsList().map { it.toCalendarBinding() }

  /** CAL-4 — every binding, for the reconciler's "still-valid explicit binding" ladder rung. */
  fun allCalendarBindings(): List<CalendarBinding> = q.allCalendarBindings().executeAsList().map { it.toCalendarBinding() }

  /** ADR 0063 §7 — just the subject keys, for notifSnapshot's background-wake hot path. */
  fun calendarBindingSubjectKeysByNotificationOwner(owner: CalendarNotificationOwner): Set<String> =
    q.calendarBindingSubjectKeysByNotificationOwner(owner.wire).executeAsList().toSet()

  fun upsertCalendarBinding(b: CalendarBinding) = withWriteGate {
    q.upsertCalendarBinding(
      b.subjectKey, b.sourceVersion, b.platformEventId, b.calendarId, b.fingerprint, b.lastSeenAt,
      b.relation.wire, b.notificationOwner.wire, b.reviewState, b.createdAt, b.updatedAt,
    )
  }

  /** A deselected calendar's matches return to review (ADR 0063 §1/§5). */
  fun deleteCalendarBindingsForCalendar(calendarId: String) = withWriteGate { q.deleteCalendarBindingsForCalendar(calendarId) }

  fun deleteCalendarBindingForSubject(subjectKey: String) = withWriteGate { q.deleteCalendarBindingForSubject(subjectKey) }

  /** ADR 0063 §5 ResetLocalMatches review action — clears local match/ignore history. Feature-
   *  scoped (unlike wipe()'s sign-out/family-removal boundary): the family stays signed in. */
  fun resetCalendarBindings() = withWriteGate { q.resetCalendarCheckBindings() }

  // SQLDelight reuses the table's own generated row type here (both queries select exactly the
  // table's columns), so one mapper covers bySubjectKey + byRelation.
  private fun com.sloopworks.dayfold.client.db.Calendar_binding.toCalendarBinding() = CalendarBinding(
    subjectKey = subject_key, sourceVersion = source_version, platformEventId = platform_event_id,
    calendarId = calendar_id, fingerprint = fingerprint, lastSeenAt = last_seen_at,
    relation = CalendarRelation.of(relation), notificationOwner = CalendarNotificationOwner.of(notification_owner),
    reviewState = review_state, createdAt = created_at, updatedAt = updated_at,
  )

  // ── Calendar→Dayfold import (ADR 0063 §6, calendar-import-contract-design.md §3) — DEVICE-LOCAL,
  // NEVER synced. Egress uses the SAME outbox as every other member write (ADR 0038/0039); the
  // methods below are import-specific bookkeeping on top of it: the durable proposal/ids record
  // (calendar_import), enqueueing the materialized op chain, and depends_on cascade-drop.

  /** spec §3.1 — enqueue [ops] atomically, coalescing any still-pending op for the same target+type
   *  first (a re-confirm after source-changed/version-conflict reuses the SAME ids, per §3.3). */
  fun enqueueImportOps(ops: List<MaterializedOp>, nowIso: String) = withWriteGate {
    q.transaction {
      ops.forEach { op ->
        q.deletePendingForTarget(op.targetId, op.type)
        q.enqueueOp(op.opId, op.targetKind, op.targetId, op.type, op.payload, null, op.dependsOn, nowIso)
      }
    }
  }

  /** spec §3.5 state 1 — atomically project the reviewed import into the ordinary content tables
   * and enqueue its existing typed-op chain. The UI continues to read content tables only; the
   * outbox remains an egress lane. */
  fun applyCalendarImportOptimistically(
    proposal: CalendarImportProposal,
    destination: ImportDestination,
    ids: ImportOpIds,
    ops: List<MaterializedOp>,
    existingSectionId: String?,
    importerId: String?,
    nowIso: String,
  ) = withWriteGate {
    q.transaction {
      val hubId = when (destination) {
        is ImportDestination.NewHub -> requireNotNull(ids.hubId)
        is ImportDestination.ExistingHub -> destination.hubId
      }
      if (destination is ImportDestination.NewHub) {
        q.upsertHub(
          hubId, CALENDAR_IMPORT_HUB_TYPE, proposal.title, "active", proposal.start.wire,
          proposal.end?.wire, null, destination.visibility.wire, importerId, null, null, null, nowIso,
        )
      }
      val sectionId = existingSectionId ?: ids.sectionId
      if (existingSectionId == null) {
        q.upsertSection(sectionId, hubId, CALENDAR_IMPORT_SECTION_TITLE, 0L, nowIso)
      }
      val provenance = """{"source":"calendar","at":"$nowIso"}"""
      var index = 0
      fun putBlock(type: String, body: String?, payload: BlockPayload?, triggers: List<BlockTrigger>?) {
        val blockId = ids.blockIds[index]
        q.upsertBlock(
          blockId, sectionId, type, body,
          payload?.let { json.encodeToString(BlockPayload.serializer(), it) },
          provenance, index.toLong(), nowIso, 1L, importerId,
          triggers?.let { json.encodeToString(TRIGGERS_SER, it) },
        )
        q.setBlockLocalState("pending", blockId)
        index++
      }
      putBlock(
        type = "milestone",
        body = null,
        payload = BlockPayload(
          label = proposal.title, date = proposal.start.wire,
          end = proposal.end?.wire, tz = proposal.timezone,
        ),
        triggers = if (proposal.start is EventInstant.Timed) {
          listOf(BlockTrigger(whenTrigger = TriggerWhen(at = proposal.start.wire)))
        } else null,
      )
      proposal.location?.let { location ->
        putBlock("location", null, BlockPayload(label = location.label, address = location.address), null)
      }
      proposal.description?.let { description -> putBlock("markdown", description, null, null) }

      ops.forEach { op ->
        q.deletePendingForTarget(op.targetId, op.type)
        q.enqueueOp(op.opId, op.targetKind, op.targetId, op.type, op.payload, null, op.dependsOn, nowIso)
      }
    }
  }

  /** The current outbox state for each of [opIds] — null when the row is absent (only Drop removes
   *  a row outright; Acked/Failed leave it in place for exactly this read, see Content.sq). */
  fun outboxOpStates(opIds: List<String>): Map<String, String?> =
    opIds.associateWith { q.outboxOpState(it).executeAsOneOrNull() }

  fun calendarImportStatus(proposalId: String): String? =
    q.calendarImportByProposalId(proposalId).executeAsOneOrNull()?.status

  /** Acks one import op. When it is the terminal block, the final matched binding and durable
   * saved status land in the SAME SQLite transaction as the ack (spec §3.6). */
  fun ackCalendarImportOp(op: OutboxOp, resultVersion: Long?, nowIso: String) = withWriteGate {
    val pending = q.unresolvedCalendarImports().executeAsList().firstOrNull { row ->
      decode(row.block_ids_json, CALENDAR_IDS_SER)?.lastOrNull() == op.targetId
    }
    q.transaction {
      q.markAcked(resultVersion, op.opId)
      if (pending == null) return@transaction

      val persisted = decode(pending.destination_json, PersistedImportDestination.serializer())
      val destination = persisted?.destination
      val hubId = pending.hub_id ?: (destination as? ImportDestination.ExistingHub)?.hubId
      val blockIds = decode(pending.block_ids_json, CALENDAR_IDS_SER).orEmpty()
      val milestoneId = blockIds.firstOrNull()
      val provisionalKey = "calendarImport:${pending.proposal_id}"
      val source = q.calendarBindingBySubjectKey(provisionalKey).executeAsOneOrNull()
      val sectionId = runCatching {
        json.parseToJsonElement(op.payload).jsonObject["sectionId"]?.jsonPrimitive?.content
      }.getOrNull() ?: pending.section_id

      if (source != null && hubId != null && milestoneId != null) {
        q.deleteCalendarBindingForSubject(provisionalKey)
        q.upsertCalendarBinding(
          SubjectRef.node(hubId, sectionId, milestoneId), pending.proposal_id,
          source.platform_event_id, source.calendar_id, source.fingerprint, nowIso,
          CalendarRelation.MATCHED.wire, CalendarNotificationOwner.CALENDAR.wire, null,
          source.created_at, nowIso,
        )
      }
      q.upsertCalendarImport(
        pending.proposal_id, pending.proposal_json, pending.destination_json, pending.hub_id,
        pending.section_id, pending.block_ids_json, "saved", pending.created_at, nowIso,
      )
    }
  }

  /** Removes proposal-owned optimistic rows after a terminal role/destination rejection. */
  fun rollbackCalendarImport(pending: PersistedCalendarImport) = withWriteGate {
    q.transaction {
      pending.ids.blockIds.forEach(q::deleteImportedBlockLocal)
      q.deleteImportedSectionLocal(pending.ids.sectionId)
      pending.ids.hubId?.let(q::deleteImportedHubLocal)
      q.deleteCalendarBindingForSubject("calendarImport:${pending.proposal.proposalId}")
      q.deleteCalendarImport(pending.proposal.proposalId)
    }
  }

  /** spec §3.1 cascade-drop: an op that reached Drop/Failed takes every op depending on it (directly
   *  or transitively) with it — nothing has left the device for a not-yet-sent dependent, so this is
   *  a pure local delete, never a compensating request. Bounded depth (hub→section→block). */
  fun cascadeDropDependents(opId: String) = withWriteGate {
    q.transaction {
      val queue = ArrayDeque<String>().apply { add(opId) }
      while (queue.isNotEmpty()) {
        val parent = queue.removeFirst()
        q.opsDependingOn(parent).executeAsList().forEach { child ->
          q.deleteOp(child)
          queue.add(child)
        }
      }
    }
  }

  /** spec §3.5 — persisted ONLY at confirm (never during the earlier wizard steps). [proposalJson]/
   *  [destinationJson] are opaque strings the caller builds — ContentStore does not know the
   *  proposal's shape, matching every other content-blind local cache in this file. */
  fun upsertCalendarImport(
    proposalId: String,
    proposalJson: String,
    destinationJson: String,
    hubId: String?,
    sectionId: String,
    blockIds: List<String>,
    status: String,
    nowIso: String,
  ) = withWriteGate {
    val existing = q.calendarImportByProposalId(proposalId).executeAsOneOrNull()
    q.upsertCalendarImport(
      proposalId, proposalJson, destinationJson, hubId, sectionId,
      json.encodeToString(CALENDAR_IDS_SER, blockIds), status, existing?.created_at ?: nowIso, nowIso,
    )
  }

  fun upsertCalendarImport(
    proposal: CalendarImportProposal,
    destination: ImportDestination,
    ids: ImportOpIds,
    audienceIds: Set<String>,
    hubVersion: Long? = null,
    status: String,
    nowIso: String,
  ) = upsertCalendarImport(
    proposalId = proposal.proposalId,
    proposalJson = json.encodeToString(CalendarImportProposal.serializer(), proposal),
    destinationJson = json.encodeToString(
      PersistedImportDestination.serializer(),
      PersistedImportDestination(destination, audienceIds, hubVersion),
    ),
    hubId = ids.hubId,
    sectionId = ids.sectionId,
    blockIds = ids.blockIds,
    status = status,
    nowIso = nowIso,
  )

  fun setCalendarImportStatus(proposalId: String, status: String, nowIso: String) = withWriteGate {
    val row = q.calendarImportByProposalId(proposalId).executeAsOneOrNull() ?: return@withWriteGate
    q.upsertCalendarImport(
      row.proposal_id, row.proposal_json, row.destination_json, row.hub_id, row.section_id,
      row.block_ids_json, status, row.created_at, nowIso,
    )
  }

  fun deleteCalendarImport(proposalId: String) = withWriteGate { q.deleteCalendarImport(proposalId) }

  /** The ids already minted for [proposalId] (a retry/re-confirm), or null on first confirm. */
  fun calendarImportIds(proposalId: String): ImportOpIds? =
    q.calendarImportByProposalId(proposalId).executeAsOneOrNull()?.let { row ->
      ImportOpIds(row.hub_id, row.section_id, decode(row.block_ids_json, CALENDAR_IDS_SER) ?: emptyList())
    }

  fun unresolvedCalendarImports(): List<PersistedCalendarImport> =
    q.unresolvedCalendarImports().executeAsList().mapNotNull { row ->
      val proposal = decode(row.proposal_json, CalendarImportProposal.serializer()) ?: return@mapNotNull null
      val persistedDestination = decode(row.destination_json, PersistedImportDestination.serializer()) ?: return@mapNotNull null
      val ids = ImportOpIds(
        row.hub_id,
        row.section_id,
        decode(row.block_ids_json, CALENDAR_IDS_SER) ?: return@mapNotNull null,
      )
      PersistedCalendarImport(
        proposal, persistedDestination.destination, ids, row.status,
        persistedDestination.audienceIds, persistedDestination.hubVersion,
      )
    }

  /** spec §3.1 OD-6 — the existing-Hub import path's section-reuse lookup. */
  fun liveSectionIdForHub(hubId: String): String? = q.lastLiveSectionForHub(hubId).executeAsOneOrNull()

  // ── Calendar Check settings (ADR 0063 §1) — DEVICE-LOCAL, NEVER synced. Same shape/posture as
  // notif config: a device preference, not touched by wipe() (survives sign-out/family removal).

  /** Reactive settings (DB→store bridge feeds state.calendar.settings). Absent → feature-off default. */
  fun calendarSettingsFlow(dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default): Flow<CalendarSettings> =
    q.calendarSettingsRow().asFlow().mapToList(dispatcher).map { rows -> rows.firstOrNull()?.toCalendarSettings() ?: CalendarSettings() }

  /** Synchronous settings snapshot. Absent → feature-off default. */
  fun calendarSettings(): CalendarSettings = q.calendarSettingsRow().executeAsOneOrNull()?.toCalendarSettings() ?: CalendarSettings()

  fun setCalendarSettings(s: CalendarSettings) = withWriteGate {
    q.setCalendarSettings(if (s.featureEnabled) 1L else 0L, json.encodeToString(CALENDAR_IDS_SER, s.selectedCalendarIds.toList()), s.lastCheckAt)
  }

  /** Applies one member-reviewed Calendar value to its typed Dayfold source and enqueues the
   * ordinary authenticated PUT atomically. Unsupported representations return false and leave
   * both content and outbox untouched. */
  fun applyCalendarFieldValue(
    candidate: DayfoldEventCandidate,
    observation: CalendarEventObservation,
    field: String,
    nowIso: String,
    opId: String,
  ): Boolean = withWriteGate {
    val cardId = (candidate.source as? CalendarCandidateSource.Card)?.id
      ?: if (candidate.source == null) SubjectRef.cardIdOf(candidate.subjectKey) else null
    val blockId = (candidate.source as? CalendarCandidateSource.Block)?.id
      ?: if (candidate.source == null) SubjectRef.blockIdOf(candidate.subjectKey) else null
    val hubId = (candidate.source as? CalendarCandidateSource.Hub)?.id
      ?: if (candidate.source == null) SubjectRef.hubIdOf(candidate.subjectKey) else null

    when {
      cardId != null -> {
        val card = activeCards().firstOrNull { it.id == cardId } ?: return@withWriteGate false
        val updated = when (field) {
          "title" -> card.copy(title = observation.title)
          "start" -> card.copy(triggers = card.triggers.withCalendarStart(observation.startAt))
          "location" -> {
            val location = observation.location ?: return@withWriteGate false
            val geo = card.payload?.geo ?: return@withWriteGate false
            card.copy(payload = card.payload.copy(geo = geo.copy(
              label = location.label, address = location.address, lat = null, lng = null,
            )))
          }
          else -> return@withWriteGate false
        }
        val body = calendarCardPutBody(updated, nowIso)
        q.transaction {
          q.upsertCard(
            updated.id, updated.kind, updated.title, updated.bodyMd, updated.provenance?.source,
            updated.notBefore, updated.expiresAt, updated.importance, updated.type,
            updated.payload?.let { json.encodeToString(Payload.serializer(), it) },
            updated.privacy?.let { json.encodeToString(CardPrivacy.serializer(), it) },
            updated.hubRef, updated.targetHubId, updated.targetSectionId, updated.targetBlockId,
            updated.related?.let { json.encodeToString(RELATED_SER, it) }, updated.relatedKicker,
            updated.media?.let { json.encodeToString(CardMedia.serializer(), it) },
            updated.triggers?.let { json.encodeToString(TRIGGERS_SER, it) }, nowIso,
          )
          q.deletePendingForTarget(cardId, "calendarField")
          q.enqueueOp(opId, "card", cardId, "calendarField", body, null, null, nowIso)
        }
        true
      }

      blockId != null -> {
        val block = allBlocks().firstOrNull { it.id == blockId } ?: return@withWriteGate false
        val sectionId = block.sectionId ?: return@withWriteGate false
        val updated = when (field) {
          "title" -> {
            if (block.type !in setOf("milestone", "location", "link", "document")) return@withWriteGate false
            block.copy(payload = (block.payload ?: BlockPayload()).copy(label = observation.title))
          }
          "start" -> if (block.type == "milestone") {
            block.copy(
              payload = (block.payload ?: BlockPayload()).copy(
                date = observation.startAt,
                tz = observation.timezone,
              ),
              triggers = if (observation.allDay) block.triggers else block.triggers.withCalendarStart(observation.startAt),
            )
          } else {
            block.copy(triggers = block.triggers.withCalendarStart(observation.startAt))
          }
          "end" -> {
            if (block.type != "milestone") return@withWriteGate false
            block.copy(payload = (block.payload ?: BlockPayload()).copy(end = observation.endAt))
          }
          "location" -> {
            if (block.type != "location") return@withWriteGate false
            val location = observation.location ?: return@withWriteGate false
            block.copy(payload = (block.payload ?: BlockPayload()).copy(
              label = location.label ?: block.payload?.label,
              address = location.address,
              lat = null,
              lng = null,
            ))
          }
          else -> return@withWriteGate false
        }
        val payloadJson = updated.payload?.let { json.encodeToString(BlockPayload.serializer(), it) }
        val triggerJson = updated.triggers?.let { json.encodeToString(TRIGGERS_SER, it) }
        val body = calendarBlockPutBody(updated, nowIso)
        q.transaction {
          q.upsertBlock(
            updated.id, sectionId, updated.type, updated.bodyMd,
            payloadJson, updated.provenance?.let { json.encodeToString(Provenance.serializer(), it) },
            updated.ord, nowIso, updated.version, updated.createdBy, triggerJson,
          )
          q.setBlockLocalState("pending", blockId)
          q.deletePendingForTarget(blockId, "calendarField")
          q.enqueueOp(opId, "block", blockId, "calendarField", body, updated.version, null, nowIso)
        }
        true
      }

      hubId != null -> {
        // A section-only subject is not emitted by the calendar candidate projection.
        if (SubjectRef.sectionIdOf(candidate.subjectKey) != null) return@withWriteGate false
        val hub = activeHubs().firstOrNull { it.id == hubId } ?: return@withWriteGate false
        if (hub.type == null) return@withWriteGate false
        val updated = when (field) {
          "title" -> hub.copy(title = observation.title)
          "start" -> hub.copy(startAt = observation.startAt)
          "end" -> hub.copy(endAt = observation.endAt)
          else -> return@withWriteGate false
        }
        val body = calendarHubPutBody(updated)
        q.transaction {
          q.upsertHub(
            updated.id, updated.type, updated.title, updated.status, updated.startAt, updated.endAt,
            updated.countdownTo, updated.visibility, updated.createdBy, updated.version,
            updated.media?.let { json.encodeToString(HubMedia.serializer(), it) },
            updated.timeline?.let { json.encodeToString(Timeline.serializer(), it) }, nowIso,
          )
          q.deletePendingForTarget(hubId, "calendarField")
          q.enqueueOp(opId, "hub", hubId, "calendarField", body, null, null, nowIso)
        }
        true
      }

      else -> false
    }
  }

  private fun List<BlockTrigger>?.withCalendarStart(startAt: String): List<BlockTrigger> {
    val current = orEmpty()
    var replaced = false
    val next = current.map { trigger ->
      if (!replaced && trigger.whenTrigger?.at != null) {
        replaced = true
        trigger.copy(whenTrigger = trigger.whenTrigger.copy(at = startAt))
      } else trigger
    }
    return if (replaced) next else next + BlockTrigger(whenTrigger = TriggerWhen(at = startAt))
  }

  private fun calendarHubPutBody(hub: Hub): String = kotlinx.serialization.json.buildJsonObject {
    put("type", kotlinx.serialization.json.JsonPrimitive(requireNotNull(hub.type)))
    put("title", kotlinx.serialization.json.JsonPrimitive(hub.title))
    put("status", kotlinx.serialization.json.JsonPrimitive(hub.status))
    hub.startAt?.let { put("start_at", kotlinx.serialization.json.JsonPrimitive(it)) }
    hub.endAt?.let { put("end_at", kotlinx.serialization.json.JsonPrimitive(it)) }
    hub.countdownTo?.let { put("countdown_to", kotlinx.serialization.json.JsonPrimitive(it)) }
    hub.media?.let { put("media", json.encodeToJsonElement(HubMedia.serializer(), it)) }
    hub.timeline?.let { put("timeline", json.encodeToJsonElement(Timeline.serializer(), it)) }
  }.toString()

  private fun calendarBlockPutBody(block: HubBlock, nowIso: String): String =
    kotlinx.serialization.json.buildJsonObject {
      put("sectionId", kotlinx.serialization.json.JsonPrimitive(requireNotNull(block.sectionId)))
      put("type", kotlinx.serialization.json.JsonPrimitive(block.type))
      put("ord", kotlinx.serialization.json.JsonPrimitive(block.ord))
      block.bodyMd?.let { put("body_md", kotlinx.serialization.json.JsonPrimitive(it)) }
      block.payload?.let { put("payload", json.encodeToJsonElement(BlockPayload.serializer(), it)) }
      block.triggers?.let { put("triggers", json.encodeToJsonElement(TRIGGERS_SER, it)) }
      put("provenance", calendarMemberProvenance(nowIso))
    }.toString()

  private fun calendarCardPutBody(card: Card, nowIso: String): String =
    kotlinx.serialization.json.buildJsonObject {
      put("kind", kotlinx.serialization.json.JsonPrimitive(card.kind))
      put("title", kotlinx.serialization.json.JsonPrimitive(card.title))
      card.bodyMd?.let { put("body_md", kotlinx.serialization.json.JsonPrimitive(it)) }
      card.notBefore?.let { put("not_before", kotlinx.serialization.json.JsonPrimitive(it)) }
      card.expiresAt?.let { put("expires_at", kotlinx.serialization.json.JsonPrimitive(it)) }
      card.importance?.let { put("importance", kotlinx.serialization.json.JsonPrimitive(it)) }
      if (card.targetHubId != null || card.targetSectionId != null || card.targetBlockId != null) {
        put("target", kotlinx.serialization.json.buildJsonObject {
          card.targetHubId?.let { put("hubId", kotlinx.serialization.json.JsonPrimitive(it)) }
          card.targetSectionId?.let { put("sectionId", kotlinx.serialization.json.JsonPrimitive(it)) }
          card.targetBlockId?.let { put("blockId", kotlinx.serialization.json.JsonPrimitive(it)) }
        })
      }
      card.triggers?.let { put("triggers", json.encodeToJsonElement(TRIGGERS_SER, it)) }
      card.type?.let { put("type", kotlinx.serialization.json.JsonPrimitive(it)) }
      card.payload?.let { put("payload", json.encodeToJsonElement(Payload.serializer(), it)) }
      card.media?.let { put("media", json.encodeToJsonElement(CardMedia.serializer(), it)) }
      card.hubRef?.let { put("hubRef", kotlinx.serialization.json.JsonPrimitive(it)) }
      card.relatedKicker?.let { put("relatedKicker", kotlinx.serialization.json.JsonPrimitive(it)) }
      card.related?.let { put("related", json.encodeToJsonElement(RELATED_SER, it)) }
      card.privacy?.let { put("privacy", json.encodeToJsonElement(CardPrivacy.serializer(), it)) }
      put("provenance", calendarMemberProvenance(nowIso))
    }.toString()

  private fun calendarMemberProvenance(nowIso: String) = kotlinx.serialization.json.buildJsonObject {
    put("source", kotlinx.serialization.json.JsonPrimitive("member"))
    put("at", kotlinx.serialization.json.JsonPrimitive(nowIso))
  }

  private fun com.sloopworks.dayfold.client.db.CalendarSettingsRow.toCalendarSettings() = CalendarSettings(
    featureEnabled = feature_enabled == 1L,
    selectedCalendarIds = decode(selected_calendar_ids, CALENDAR_IDS_SER)?.toSet() ?: emptySet(),
    lastCheckAt = last_check_at,
  )

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
  fun notifSnapshot(): NotifSnapshot = withWriteGate {
    NotifSnapshot(
      cards = activeCards(),
      hubs = activeHubs(),
      sections = allSections(),
      blocks = allBlocks(),
      places = activePlaces(),
      surfacing = surfacing(),
      config = notifConfig(),
      log = notificationLog(),
      calendarOwnedSubjects = calendarBindingSubjectKeysByNotificationOwner(CalendarNotificationOwner.CALENDAR),
    )
  }

  /** Feed projection: live cards, not_before NULLS LAST then id (the API contract). */
  fun activeCards(): List<Card> = q.activeCards().executeAsList().map(::rowToCard)

  /** Reactive feed projection — emits current active cards and re-emits on any card-table write. */
  fun activeCardsFlow(dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default): Flow<List<Card>> =
    q.activeCards().asFlow().mapToList(dispatcher).map { rows -> rows.map(::rowToCard) }

  fun cursor(): String? = q.getCursor().executeAsOneOrNull()?.cursor

  // MUTATION INVARIANT: this is the only entry point for public methods that change `q`.
  // New public writes must use it; multi-query reads use it only when they require one
  // writer-consistent view (currently notifSnapshot). Keeping the boundary named and local makes
  // an unguarded generated-query mutation visible in review and searchable as `q.` without this call.
  private inline fun <T> withWriteGate(block: () -> T): T = synchronized(writeGate, block)

  companion object {
    fun create(driver: SqlDriver): ContentStore {
      ContentDb.Schema.create(driver)
      return ContentStore(driver)
    }
  }
}
