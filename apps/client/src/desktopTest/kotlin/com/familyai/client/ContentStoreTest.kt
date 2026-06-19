package com.familyai.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// DB-as-source-of-truth round-trip (ADR 0020), against an in-memory SQLDelight DB.
class ContentStoreTest {
  private fun store() = ContentStore.create(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
  private fun card(id: String, title: String, nb: String? = null) =
    Card(id = id, kind = "info", title = title, provenance = Provenance("claude"), notBefore = nb)

  @Test fun `upsert + activeCards round-trip, ordered not_before-nulls-last then id`() {
    val s = store()
    s.applyDelta(
      changed = listOf(
        card("b", "Soccer", nb = "2026-06-18T16:00:00Z"),
        card("z", "No time"),                                  // null not_before → last
        card("a", "Leave by 3:30", nb = "2026-06-18T15:30:00Z"),
      ),
      tombstoneIds = emptyList(), nextCursor = "cur1", nowIso = "2026-06-18T10:00:00Z",
    )
    assertEquals(listOf("a", "b", "z"), s.activeCards().map { it.id })
    assertEquals("cur1", s.cursor())
    assertEquals("claude", s.activeCards().first().provenance?.source)
  }

  @Test fun `upsert updates in place and tombstone removes`() {
    val s = store()
    s.applyDelta(listOf(card("a", "v1")), emptyList(), "c1", "2026-06-18T10:00:00Z")
    s.applyDelta(listOf(card("a", "v2"), card("b", "B")), emptyList(), "c2", "2026-06-18T10:01:00Z")
    assertEquals("v2", s.activeCards().first { it.id == "a" }.title)
    assertEquals(2, s.activeCards().size)
    s.applyDelta(emptyList(), tombstoneIds = listOf("a"), nextCursor = "c3", nowIso = "2026-06-18T10:02:00Z")
    assertEquals(listOf("b"), s.activeCards().map { it.id })
    assertEquals("c3", s.cursor())
  }

  @Test fun `fresh db has no cursor`() {
    assertNull(store().cursor())
  }

  @Test fun `activeCardsFlow emits current rows then re-emits on write`() = runBlocking {
    val s = store()
    s.activeCardsFlow().test {
      assertEquals(emptyList(), awaitItem().map { it.id })           // initial: empty DB
      s.applyDelta(listOf(card("a", "A")), emptyList(), "c1", "2026-06-18T10:00:00Z")
      assertEquals(listOf("a"), awaitItem().map { it.id })           // re-emits after write
      cancelAndIgnoreRemainingEvents()
    }
  }
}
