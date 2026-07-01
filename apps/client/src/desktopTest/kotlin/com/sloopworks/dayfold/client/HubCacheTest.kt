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

        q.upsertHub("h1", "party-event", "Birthday", "active", null, null, null, "family", null, null, null, "2026-06-24T00:00:00Z")
        q.upsertHub("h2", "medical", "Doctor", "active", null, null, null, "family", null, null, null, "2026-06-24T00:00:00Z")
        assertEquals(2, q.activeHubs().executeAsList().size)

        q.markHubDeleted("2026-06-24T01:00:00Z", "h1")
        val active = q.activeHubs().executeAsList()
        assertEquals(1, active.size)
        assertEquals("h2", active.first().id)
    }
}
