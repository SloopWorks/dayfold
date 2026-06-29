package com.sloopworks.dayfold.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Slice 5b (ADR 0038 §W5) — hide is a LOCAL-ONLY, personal, reversible view filter.
// NEVER synced (not in applyDelta, not in the outbox); hidden content still syncs.
class HideModelTest {
  private fun store() = ContentStore.create(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))

  @Test fun `hide then unhide round-trips through hiddenIdsFlow`() = runBlocking {
    val s = store()
    s.hiddenIdsFlow().test {
      assertEquals(emptySet(), awaitItem())              // fresh DB: nothing hidden
      s.hide("b1", "2026-06-29T10:00:00Z")
      assertEquals(setOf("b1"), awaitItem())
      s.hide("b2", "2026-06-29T10:01:00Z")
      assertEquals(setOf("b1", "b2"), awaitItem())
      s.unhide("b1")
      assertEquals(setOf("b2"), awaitItem())
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun `hide is idempotent (re-hiding the same id stays a single entry)`() = runBlocking {
    val s = store()
    s.hide("b1", "2026-06-29T10:00:00Z")
    s.hide("b1", "2026-06-29T10:05:00Z")
    s.hiddenIdsFlow().test { assertEquals(setOf("b1"), awaitItem()); cancelAndIgnoreRemainingEvents() }
  }

  // Hide must NOT touch synced content: a hidden block still lives in the tree (hide ≠ ACL).
  // The partition is a pure VIEW split — the "Hidden for you" section + "Show hidden" toggle.
  @Test fun `partitionHidden splits a tree's blocks into visible and hidden-for-you, keeping all`() {
    val blocks = listOf(
      HubBlock(id = "b1", sectionId = "s1", type = "text", bodyMd = "Keep"),
      HubBlock(id = "b2", sectionId = "s1", type = "text", bodyMd = "Hidden"),
      HubBlock(id = "b3", sectionId = "s1", type = "text", bodyMd = "Keep too"),
    )
    val (visible, hidden) = partitionHidden(blocks, hiddenIds = setOf("b2"))
    assertEquals(listOf("b1", "b3"), visible.map { it.id })
    assertEquals(listOf("b2"), hidden.map { it.id })
  }

  @Test fun `partitionHidden with nothing hidden leaves every block visible`() {
    val blocks = listOf(HubBlock(id = "b1", sectionId = "s1", type = "text"))
    val (visible, hidden) = partitionHidden(blocks, hiddenIds = emptySet())
    assertEquals(1, visible.size)
    assertTrue(hidden.isEmpty())
  }

  // hide is local-only: it survives a sync of the same block (the block keeps syncing) and is
  // never enqueued to the outbox.
  @Test fun `hiding a block does not enqueue anything to the outbox`() {
    val s = store()
    s.applyDelta(
      emptyList(), emptyList(), emptyList(),
      listOf(HubBlock(id = "b1", sectionId = "s1", type = "text")),
      emptyList(), "c1", "2026-06-29T10:00:00Z",
    )
    s.hide("b1", "2026-06-29T10:00:00Z")
    assertEquals(0, s.pendingOpCount())
    assertEquals(0L, s.outboxSize())
  }

  // wipe() (tenancy revocation) drops the hidden table too — a removed member retains nothing.
  @Test fun `wipe clears hidden`() = runBlocking {
    val s = store()
    s.hide("b1", "2026-06-29T10:00:00Z")
    s.wipe()
    s.hiddenIdsFlow().test { assertEquals(emptySet(), awaitItem()); cancelAndIgnoreRemainingEvents() }
  }
}
