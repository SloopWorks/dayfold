package com.sloopworks.dayfold.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class SyncDrainerTest {

  // Two pages, then done. Proves the loop follows has_more and feeds each page's
  // cursor back into the next fetch.
  @Test fun `drains every page in order`() = runBlocking {
    val cursors = mutableListOf<String?>()
    var page = 0
    val drainer = drainerFor(inMemoryContentStore(), fetch = { since ->
      cursors += since
      page++
      if (page == 1) syncResponse(nextCursor = "c1", hasMore = true)
      else syncResponse(nextCursor = "c2", hasMore = false)
    })

    drainer.drain()

    assertEquals(listOf(null, "c1"), cursors)
    assertEquals(2, page)
  }

  // A rejected commit means the family session was replaced mid-pass. The drain must
  // abort rather than apply a page into the wrong tenant's cache.
  @Test fun `aborts when a commit is rejected`() = runBlocking {
    var fetches = 0
    val drainer = drainerFor(
      inMemoryContentStore(),
      fetch = { fetches++; syncResponse(nextCursor = "c1", hasMore = true) },
      commit = { false },
    )

    val error = runCatching { drainer.drain() }.exceptionOrNull()

    assertTrue(error is kotlinx.coroutines.CancellationException)
    assertEquals(1, fetches)
  }

  // A page carrying full_resync wipes the synced cache before applying, so the rebuild
  // starts clean (ADR 0040 stale-cursor directive). Uses a real in-memory ContentStore
  // because the drainer now owns page->DB directly.
  @Test fun `full resync page wipes before applying`() = runBlocking {
    val store = inMemoryContentStore()
    store.applyDelta(
      changedCards = listOf(card(id = "stale")), changedHubs = emptyList(),
      changedSections = emptyList(), changedBlocks = emptyList(), tombstones = emptyList(),
      nextCursor = "c0", nowIso = "2026-07-31T00:00:00Z", changedPlaces = emptyList(),
    )

    drainerFor(store, fetch = { syncResponse(nextCursor = "c1", hasMore = false, fullResync = true) }).drain()

    // The pre-existing card is gone: the wipe ran before the (empty) page was applied.
    assertTrue(store.activeCards().none { it.id == "stale" })
    assertEquals("c1", store.cursor())
  }

  private fun inMemoryContentStore(): ContentStore =
    ContentStore.create(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))

  private fun card(id: String) =
    Card(id = id, kind = "info", title = id, provenance = Provenance("claude"))

  private fun drainerFor(
    store: ContentStore,
    fetch: suspend (String?) -> SyncResponse,
    commit: suspend (block: () -> Unit) -> Boolean = { block -> block(); true },
  ) = SyncDrainer(
    contentStore = store,
    databaseDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
    nowIso = { "2026-07-31T12:00:00Z" },
    fetch = fetch,
    commit = commit,
    onActivity = {},
  )

  private fun syncResponse(
    nextCursor: String,
    hasMore: Boolean,
    fullResync: Boolean = false,
  ) = SyncResponse(
    changes = Changes(),
    tombstones = emptyList(),
    nextCursor = nextCursor,
    hasMore = hasMore,
    fullResync = fullResync,
  )
}
