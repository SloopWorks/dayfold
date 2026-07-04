package com.sloopworks.dayfold.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Issue #283 — reconcileSchemaVersion forces a full resync when the cache was written under an
// older client content-schema (a since-added behavior-affecting field an older model dropped is
// never healed by the incremental cursor). It must WIPE synced content + cursor, PRESERVE the
// outbox + local hidden set (wipeForResync semantics), and stamp the current version — once.
// applyDelta (the write path) stamps CLIENT_SCHEMA_VERSION, so cached content is tagged with the
// schema it was written under; reconcile heals when that tag is behind the running build.
class SchemaVersionResyncTest {
  private fun store() = ContentStore.create(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
  private fun card(id: String) = Card(id = id, kind = "info", title = id, provenance = Provenance("claude"))
  private fun seedSynced(s: ContentStore) =
    s.applyDelta(
      listOf(card("c1")), emptyList(), emptyList(),
      listOf(HubBlock(id = "blk-x", type = "markdown")),        // a cached block so enqueueBlockDelete has a target
      emptyList(), "cur1", "2026-07-03T10:00:00Z",
    )

  @Test fun `applyDelta tags the cache with the current schema version`() {
    val s = store()
    seedSynced(s)
    assertEquals(CLIENT_SCHEMA_VERSION, s.schemaVersion(), "write path stamps current schema")
  }

  @Test fun `an older cache is wiped + cursor reset, outbox + hidden preserved, version re-stamped`() {
    val s = store()
    seedSynced(s)                                              // written under CLIENT_SCHEMA_VERSION
    s.hide("c1", "2026-07-03T10:00:00Z")                       // local-only hidden — must survive
    s.enqueueBlockDelete("blk-x", "2026-07-03T10:00:00Z", "op1") // queued member write — must survive
    val bumped = CLIENT_SCHEMA_VERSION + 1                     // a future behavior-affecting model bump

    s.reconcileSchemaVersion(bumped)

    assertEquals(0, s.activeCards().size, "synced cards wiped")
    assertNull(s.cursor(), "cursor reset → next sync rebuilds from -∞")
    assertEquals(bumped, s.schemaVersion(), "new version stamped")
    assertEquals(1L, s.outboxSize(), "queued member write preserved")
    runBlocking { assertTrue(s.hiddenIdsFlow().first().contains("c1"), "local hidden preserved") }
  }

  @Test fun `a current cache is left untouched (idempotent, no wipe)`() {
    val s = store()
    seedSynced(s)

    s.reconcileSchemaVersion(CLIENT_SCHEMA_VERSION)            // same version the cache was written under

    assertEquals(1, s.activeCards().size, "not wiped — version already current")
    assertEquals("cur1", s.cursor(), "cursor preserved")
  }

  @Test fun `a fresh or pre-#283 cache (unstamped, reads as 0) heals to the current version`() {
    val s = store()
    assertEquals(0L, s.schemaVersion(), "absent row reads as 0 (pre-#283)")

    s.reconcileSchemaVersion(CLIENT_SCHEMA_VERSION)            // the real v0→v1 heal path

    assertEquals(CLIENT_SCHEMA_VERSION, s.schemaVersion(), "stamped current after heal")
  }
}
