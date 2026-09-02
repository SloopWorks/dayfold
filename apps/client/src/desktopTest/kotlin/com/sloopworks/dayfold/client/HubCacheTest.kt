package com.sloopworks.dayfold.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.sloopworks.dayfold.client.db.ContentDb
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * TDD: hub table + v1→v2 SQLDelight migration.
 *
 * SQLDelight 2.3.2 finding: JdbcSqliteDriver accepts `schema = ContentDb.Schema`
 * which calls Schema.create() on version-0 DB and Schema.migrate(old, new) when
 * the stored user_version is behind the current schema version. The schema version
 * is set via `schemaVersion.set(2)` in build.gradle.kts; the migration file
 * `1.sqm` handles v1→v2 (adds the `hub` table).
 *
 * Migration test pattern: create a fresh file DB at v1 by calling Schema.create()
 * with a bare driver (no schema arg → does NOT set PRAGMA user_version), then
 * manually set PRAGMA user_version=1, close, and reopen with schema = ContentDb.Schema.
 * The driver detects version mismatch (1 < 2) and runs 1.sqm before handing the
 * connection back — so ContentDb(driver) can safely call hub queries.
 */
class HubCacheTest {

    @Test
    fun `v1 DB without hub table migrates to v2 and accepts hub upsert`() {
        val f = File.createTempFile("content_hub_migration_test", ".db").apply { deleteOnExit() }
        val path = "jdbc:sqlite:${f.absolutePath}"

        // ── Step 1: bootstrap a v1 DB (card + sync_meta only, user_version=1) ──
        // We simulate a real v1 device DB: create schema via a one-shot driver that
        // only knows about the v1 tables. In practice v1 = what Schema.create() built
        // before `hub` was added. Here we re-create it inline so the test is self-
        // contained regardless of what the current Schema.create() produces.
        val d1 = JdbcSqliteDriver(path)
        d1.execute(null, """
            CREATE TABLE card (
              id          TEXT NOT NULL PRIMARY KEY,
              kind        TEXT NOT NULL DEFAULT 'info',
              title       TEXT NOT NULL,
              body_md     TEXT,
              source      TEXT,
              not_before  TEXT,
              expires_at  TEXT,
              type        TEXT,
              payload     TEXT,
              privacy     TEXT,
              hub_ref     TEXT,
              related     TEXT,
              related_kicker TEXT,
              updated_at  TEXT NOT NULL,
              deleted     INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent(), 0)
        d1.execute(null, """
            CREATE TABLE sync_meta (
              id             INTEGER NOT NULL PRIMARY KEY DEFAULT 0,
              cursor         TEXT,
              last_synced_at TEXT
            )
        """.trimIndent(), 0)
        // Stamp user_version=1 so the migration driver knows where to start
        d1.execute(null, "PRAGMA user_version=1", 0)
        d1.close()

        // ── Step 2: reopen with Schema (auto-migrates 1→2 via 1.sqm) ──
        val d2 = JdbcSqliteDriver(path, schema = ContentDb.Schema)

        // ── Step 3: verify the hub table is present and writable ──
        val db = ContentDb(d2)
        val q = db.contentQueries

        // No crash here = hub table exists
        q.upsertHub(
            id = "hub-1",
            type = "vacation",
            title = "Summer Trip",
            status = "planning",
            start_at = "2026-07-01",
            end_at = "2026-07-14",
            countdown_to = null,
            visibility = "family",
            created_by = null,
            version = 1,
            media = null,
            timeline = null,
            updated_at = "2026-06-24T00:00:00Z",
        )

        val hubs = q.activeHubs().executeAsList()
        assertEquals(1, hubs.size)
        assertEquals("hub-1", hubs.first().id)
        assertEquals("Summer Trip", hubs.first().title)

        // wipe clears the table
        q.wipeHubs()
        assertEquals(0, q.activeHubs().executeAsList().size)

        d2.close()
    }

    @Test
    fun `v17 canonical DB adds Hub version without disturbing local Calendar state`() {
        val f = File.createTempFile("content_calendar_activation_migration_test", ".db").apply { deleteOnExit() }
        val path = "jdbc:sqlite:${f.absolutePath}"
        val d17 = JdbcSqliteDriver(path)

        // Schema v17 already has the migration-16 repair objects. Calendar activation only adds
        // Hub.version, so existing synced projections and device-local import state must survive.
        d17.execute(null, """
            CREATE TABLE card (
              id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL DEFAULT 'info',
              title TEXT NOT NULL, body_md TEXT, source TEXT, not_before TEXT,
              expires_at TEXT, type TEXT, payload TEXT, privacy TEXT, hub_ref TEXT,
              target_hub_id TEXT, target_section_id TEXT, target_block_id TEXT,
              related TEXT, related_kicker TEXT, media TEXT, updated_at TEXT NOT NULL,
              deleted INTEGER NOT NULL DEFAULT 0, importance REAL, triggers TEXT
            )
        """.trimIndent(), 0)
        d17.execute(null, """
            CREATE TABLE hub (
              id TEXT NOT NULL PRIMARY KEY, type TEXT, title TEXT NOT NULL, status TEXT,
              start_at TEXT, end_at TEXT, countdown_to TEXT, visibility TEXT,
              created_by TEXT, media TEXT, timeline TEXT, updated_at TEXT NOT NULL,
              deleted INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent(), 0)
        d17.execute(null, """
            CREATE TABLE sync_meta (
              id INTEGER NOT NULL PRIMARY KEY DEFAULT 0, cursor TEXT,
              last_synced_at TEXT, client_schema_version INTEGER
            )
        """.trimIndent(), 0)
        d17.execute(null, """
            CREATE TABLE membership (
              family_id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL DEFAULT '',
              role TEXT NOT NULL DEFAULT 'adult', status TEXT NOT NULL DEFAULT 'active'
            )
        """.trimIndent(), 0)
        d17.execute(null, """
            CREATE TABLE calendar_import (
              proposal_id TEXT NOT NULL PRIMARY KEY, proposal_json TEXT NOT NULL,
              destination_json TEXT NOT NULL, hub_id TEXT, section_id TEXT NOT NULL,
              block_ids_json TEXT NOT NULL, status TEXT NOT NULL,
              created_at TEXT NOT NULL, updated_at TEXT NOT NULL
            )
        """.trimIndent(), 0)
        d17.execute(null, """
            CREATE TABLE hub_block (
              id TEXT NOT NULL PRIMARY KEY, section_id TEXT NOT NULL, type TEXT NOT NULL,
              body_md TEXT, payload TEXT, provenance TEXT, ord INTEGER NOT NULL DEFAULT 0,
              updated_at TEXT NOT NULL, deleted INTEGER NOT NULL DEFAULT 0,
              version INTEGER NOT NULL DEFAULT 1, local_state TEXT, created_by TEXT, triggers TEXT
            )
        """.trimIndent(), 0)
        d17.execute(null, """
            CREATE TABLE outbox (
              op_id TEXT NOT NULL PRIMARY KEY, target_kind TEXT NOT NULL, target_id TEXT NOT NULL,
              type TEXT NOT NULL, payload TEXT NOT NULL, base_version INTEGER, depends_on TEXT,
              state TEXT NOT NULL DEFAULT 'pending', attempts INTEGER NOT NULL DEFAULT 0,
              result_version INTEGER, created_at TEXT NOT NULL
            )
        """.trimIndent(), 0)
        d17.execute(null, """
            CREATE TABLE calendar_binding (
              subject_key TEXT NOT NULL PRIMARY KEY, source_version TEXT NOT NULL,
              platform_event_id TEXT, calendar_id TEXT, fingerprint TEXT, last_seen_at TEXT,
              relation TEXT NOT NULL DEFAULT 'needs_review',
              notification_owner TEXT NOT NULL DEFAULT 'calendar', review_state TEXT,
              created_at TEXT NOT NULL, updated_at TEXT NOT NULL
            )
        """.trimIndent(), 0)
        d17.execute(null, """
            INSERT INTO card(
              id, title, target_hub_id, target_section_id, target_block_id, updated_at
            ) VALUES ('card-1', 'Cached card', 'hub-target', 'section-target', 'block-target', '2026-08-28T00:00:00Z')
        """.trimIndent(), 0)
        d17.execute(null, """
            INSERT INTO hub(id, title, media, timeline, updated_at)
            VALUES ('hub-1', 'Cached hub', '{}', '{}', '2026-08-28T00:00:00Z')
        """.trimIndent(), 0)
        d17.execute(null, "INSERT INTO sync_meta(id, cursor, last_synced_at, client_schema_version) VALUES (0, 'cursor-1', '2026-08-28T00:00:00Z', 3)", 0)
        d17.execute(null, "INSERT INTO membership(family_id, name, role, status) VALUES ('family-1', 'Family', 'owner', 'active')", 0)
        d17.execute(null, """
            INSERT INTO calendar_import(
              proposal_id, proposal_json, destination_json, hub_id, section_id,
              block_ids_json, status, created_at, updated_at
            ) VALUES ('proposal-1', '{}', '{}', 'hub-1', 'section-1', '[]', 'queued',
                      '2026-08-28T00:00:00Z', '2026-08-28T00:00:00Z')
        """.trimIndent(), 0)
        d17.execute(null, "PRAGMA user_version=17", 0)
        d17.close()

        val migrated = JdbcSqliteDriver(path, schema = ContentDb.Schema)
        val q = ContentDb(migrated).contentQueries

        // The cache rows and local import/membership state survive unchanged.
        val card = q.activeCards().executeAsOne()
        assertEquals("card-1", card.id)
        assertEquals("hub-target", card.target_hub_id)
        assertEquals("section-target", card.target_section_id)
        assertEquals("block-target", card.target_block_id)
        val hub = q.activeHubs().executeAsOne()
        assertEquals("hub-1", hub.id)
        assertNull(hub.version)
        assertEquals("family-1", q.allMemberships().executeAsOne().family_id)
        assertEquals("proposal-1", q.unresolvedCalendarImports().executeAsOne().proposal_id)
        assertEquals("cursor-1", q.getCursor().executeAsOne().cursor)

        migrated.close()
    }

    @Test fun `applyDelta upserts a hub then tombstones it, flow reflects both`() = runBlocking {
        val store = ContentStore.create(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        store.applyDelta(emptyList(), listOf(Hub("h1", "event", "Party", status = "active")), emptyList(), emptyList(), emptyList(), "cur1", "t1")
        assertEquals("h1", store.activeHubsFlow().first().single().id)
        store.applyDelta(emptyList(), emptyList(), emptyList(), emptyList(), listOf(Tombstone("hub", "h1")), "cur2", "t2")
        assertTrue(store.activeHubsFlow().first().isEmpty())
    }

    @Test fun `applyDelta upserts sections+blocks then tombstones them, flow reflects`() = runBlocking<Unit> {
        val store = ContentStore.create(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        store.applyDelta(
            changedCards = emptyList(),
            changedHubs = listOf(Hub("h1", title = "Party")),
            changedSections = listOf(HubSection("s1", hubId = "h1", title = "Info")),
            changedBlocks = listOf(HubBlock("b1", sectionId = "s1", type = "text", bodyMd = "hello")),
            tombstones = emptyList(), nextCursor = "c1", nowIso = "2026-06-24T00:00:00Z",
        )
        val tree = store.hubTreeFlow("h1").first()
        assertNotNull(tree)
        assertEquals("s1", tree!!.sections.single().id)
        assertEquals("b1", tree.blocks.single().id)
        // tombstone section + block
        store.applyDelta(
            changedCards = emptyList(), changedHubs = emptyList(),
            changedSections = emptyList(), changedBlocks = emptyList(),
            tombstones = listOf(Tombstone("section", "s1"), Tombstone("block", "b1")),
            nextCursor = "c2", nowIso = "2026-06-24T01:00:00Z",
        )
        val tree2 = store.hubTreeFlow("h1").first()
        assertNotNull(tree2)
        assertTrue(tree2!!.sections.isEmpty())
        assertTrue(tree2.blocks.isEmpty())
    }

    @Test fun `hubTreeFlow assembles hub+sections+blocks, orphan block (hub absent) not shown`() = runBlocking<Unit> {
        val store = ContentStore.create(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        // Insert hub h1 with section s1, block b1
        store.applyDelta(
            changedCards = emptyList(),
            changedHubs = listOf(Hub("h1", title = "Trip")),
            changedSections = listOf(HubSection("s1", hubId = "h1", title = "Plan")),
            changedBlocks = listOf(HubBlock("b1", sectionId = "s1", type = "text")),
            tombstones = emptyList(), nextCursor = "c1", nowIso = "t1",
        )
        // hubTreeFlow for absent hub h2 → null
        assertNull(store.hubTreeFlow("h2").first())
        // hubTreeFlow for h1 → full tree
        val tree = store.hubTreeFlow("h1").first()
        assertNotNull(tree)
        assertEquals("h1", tree!!.hub.id)
        assertEquals(listOf("s1"), tree.sections.map { it.id })
        assertEquals(listOf("b1"), tree.blocks.map { it.id })
        // Tombstone h1 → hubTreeFlow("h1") emits null (hub absent)
        store.applyDelta(
            changedCards = emptyList(), changedHubs = emptyList(),
            changedSections = emptyList(), changedBlocks = emptyList(),
            tombstones = listOf(Tombstone("hub", "h1")),
            nextCursor = "c2", nowIso = "t2",
        )
        assertNull(store.hubTreeFlow("h1").first())
    }

    @Test
    fun `markHubDeleted removes hub from activeHubs`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ContentDb.Schema.create(driver)
        val q = ContentDb(driver).contentQueries

        q.upsertHub("h1", "party-event", "Birthday", "active", null, null, null, "family", null, 1, null, null, "2026-06-24T00:00:00Z")
        q.upsertHub("h2", "medical", "Doctor", "active", null, null, null, "family", null, 1, null, null, "2026-06-24T00:00:00Z")
        assertEquals(2, q.activeHubs().executeAsList().size)

        q.markHubDeleted("2026-06-24T01:00:00Z", "h1")
        val active = q.activeHubs().executeAsList()
        assertEquals(1, active.size)
        assertEquals("h2", active.first().id)
    }
}
