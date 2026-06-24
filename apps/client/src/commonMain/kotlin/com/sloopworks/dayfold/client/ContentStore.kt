package com.sloopworks.dayfold.client

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.db.SqlDriver
import com.sloopworks.dayfold.client.db.ContentDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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

  /** Apply one /sync page atomically: upsert changes, tombstone deletes, advance cursor. */
  fun applyDelta(
    changedCards: List<Card>,
    changedHubs: List<Hub>,
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
          c.related?.let { json.encodeToString(RELATED_SER, it) },
          c.relatedKicker,
          nowIso,
        )
      }
      changedHubs.forEach { h ->
        q.upsertHub(h.id, h.type, h.title, h.status, h.startAt, h.endAt, h.countdownTo, h.visibility, h.createdBy, nowIso)
      }
      tombstones.forEach { t ->
        when (t.type) {
          "card" -> q.markDeleted(nowIso, t.id)
          "hub"  -> q.markHubDeleted(nowIso, t.id)
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
    payload = decode(row.payload, Payload.serializer()),
    privacy = decode(row.privacy, CardPrivacy.serializer()),
    related = decode(row.related, RELATED_SER), relatedKicker = row.related_kicker,
  )

  // Guarded decode: corrupt cached JSON must not crash the feed — skip → null,
  // the card still renders title/kind (ADR 0020 the DB cache is disposable).
  private fun <T> decode(text: String?, serializer: kotlinx.serialization.KSerializer<T>): T? =
    text?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }

  /** ADR 0030 (round-1 P0-2): hard-wipe the local cache on tenancy revocation — a
   *  removed/non-member must not retain family content. Drops cards + hubs + cursor so a
   *  later sign-in re-syncs clean. The activeCardsFlow re-emits [] → the feed empties. */
  fun wipe() {
    q.transaction { q.wipeCards(); q.wipeHubs(); q.wipeCursor() }
  }

  /** Reactive hub projection — emits current active hubs and re-emits on any hub-table write. */
  fun activeHubsFlow(): Flow<List<Hub>> =
    q.activeHubs().asFlow().mapToList(Dispatchers.Default).map { rows -> rows.map(::rowToHub) }

  private fun rowToHub(r: com.sloopworks.dayfold.client.db.ActiveHubs): Hub = Hub(
    id = r.id, type = r.type, title = r.title, status = r.status ?: "active",
    startAt = r.start_at, endAt = r.end_at, countdownTo = r.countdown_to,
    visibility = r.visibility ?: "family", createdBy = r.created_by,
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
