package com.sloopworks.dayfold.client

import app.cash.sqldelight.driver.native.inMemoryDriver
import com.sloopworks.dayfold.client.db.ContentDb
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContentStoreNativeConcurrencyTest {
  @Test fun concurrent_mutations_and_notification_snapshots_remain_readable() = runBlocking<Unit> {
    val driver = inMemoryDriver(ContentDb.Schema)
    val store = ContentStore(driver)
    try {
      val ready = Channel<Unit>(capacity = WORKER_COUNT)
      val start = CompletableDeferred<Unit>()
      val workers = listOf(
        async(Dispatchers.Default) {
          ready.send(Unit)
          start.await()
          repeat(ITERATIONS) { index ->
            store.applyDelta(
              changedCards = listOf(card("card-$index")),
              changedHubs = listOf(Hub(id = "hub-$index", title = "Hub $index")),
              tombstones = emptyList(),
              nextCursor = "cursor-$index",
              nowIso = NOW,
            )
          }
        },
        async(Dispatchers.Default) {
          ready.send(Unit)
          start.await()
          repeat(ITERATIONS) { index ->
            store.setNotifConfig(NotifConfig(enabled = index % 2 == 0, dailyCap = 4))
            store.logNotification("subject-$index", NOW)
            store.hide("hidden-$index", NOW)
            store.unhide("hidden-$index")
          }
        },
        async(Dispatchers.Default) {
          ready.send(Unit)
          start.await()
          repeat(ITERATIONS * 2) {
            val snapshot = store.notifSnapshot()
            snapshot.content.cards.forEach { card -> assertTrue(card.id.isNotEmpty()) }
            snapshot.log.forEach { row -> assertTrue(row.subjectKey.isNotEmpty()) }
          }
        },
      )

      repeat(WORKER_COUNT) { ready.receive() }
      start.complete(Unit)
      workers.awaitAll()

      // Establish deterministic state after the race and exercise every snapshot family so this
      // checks connection usability and committed-row readability, not merely absence of throws.
      store.wipe()
      store.applyDelta(
        changedCards = listOf(card("final-card")),
        changedHubs = listOf(Hub(id = "final-hub", title = "Final Hub")),
        changedSections = listOf(HubSection(id = "final-section", hubId = "final-hub", title = "Final Section")),
        changedBlocks = listOf(HubBlock(id = "final-block", sectionId = "final-section", type = "text")),
        changedPlaces = listOf(Place(id = "final-place", kind = "home", label = "Home", lat = 1.0, lng = 2.0)),
        tombstones = emptyList(),
        nextCursor = "final-cursor",
        nowIso = NOW,
      )
      store.setNotifConfig(NotifConfig(enabled = true, dailyCap = 4))
      store.logNotification("final-subject", NOW)

      val snapshot = store.notifSnapshot()
      assertEquals(listOf("final-card"), snapshot.content.cards.map { it.id })
      assertEquals(listOf("final-hub"), snapshot.hubs.hubs.map { it.id })
      assertEquals(listOf("final-section"), snapshot.sections.map { it.id })
      assertEquals(listOf("final-block"), snapshot.blocks.map { it.id })
      assertEquals(listOf("final-place"), snapshot.places.map { it.id })
      assertEquals(listOf("final-subject"), snapshot.log.map { it.subjectKey })
      assertTrue(snapshot.config.enabled)
      assertEquals("final-cursor", store.cursor())
    } finally {
      driver.close()
    }
  }

  private fun card(id: String) = Card(id = id, title = id, provenance = Provenance("native-test"))

  private companion object {
    const val ITERATIONS = 30
    const val WORKER_COUNT = 3
    const val NOW = "2026-07-14T12:00:00Z"
  }
}
