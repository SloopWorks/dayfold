package com.sloopworks.dayfold.android

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.sloopworks.dayfold.client.Card
import com.sloopworks.dayfold.client.ContentStore
import com.sloopworks.dayfold.client.Hub
import com.sloopworks.dayfold.client.HubBlock
import com.sloopworks.dayfold.client.HubSection
import com.sloopworks.dayfold.client.NotifConfig
import com.sloopworks.dayfold.client.Place
import com.sloopworks.dayfold.client.Provenance
import com.sloopworks.dayfold.client.db.ContentDb
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentStoreAndroidConcurrencyTest {
  private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

  @Test fun concurrent_mutations_and_notification_snapshots_remain_readable() {
    val databaseName = "content-concurrency-${UUID.randomUUID()}.db"
    val driver = AndroidSqliteDriver(ContentDb.Schema, context, databaseName)
    val store = ContentStore(driver)
    try {
      val ready = CountDownLatch(WORKER_COUNT)
      val start = CountDownLatch(1)
      val errors = ConcurrentLinkedQueue<Throwable>()

      fun worker(name: String, block: () -> Unit): Thread = thread(name = name) {
        try {
          ready.countDown()
          check(start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "Timed out waiting for race start" }
          block()
        } catch (error: Throwable) {
          errors += error
        }
      }

      val workers = listOf(
        worker("android-delta-writer") {
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
        worker("android-local-writer") {
          repeat(ITERATIONS) { index ->
            store.setNotifConfig(NotifConfig(enabled = index % 2 == 0, dailyCap = 4))
            store.logNotification("subject-$index", NOW)
            store.hide("hidden-$index", NOW)
            store.unhide("hidden-$index")
          }
        },
        worker("android-snapshot-reader") {
          repeat(ITERATIONS * 2) {
            val snapshot = store.notifSnapshot()
            snapshot.content.cards.forEach { card -> check(card.id.isNotEmpty()) }
            snapshot.log.forEach { row -> check(row.subjectKey.isNotEmpty()) }
          }
        },
      )

      assertTrue("workers did not become ready", ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      start.countDown()
      workers.forEach { it.join(TIMEOUT_MILLIS) }

      assertTrue("workers did not finish", workers.none(Thread::isAlive))
      assertTrue(errors.joinToString(separator = "\n"), errors.isEmpty())

      // Establish deterministic post-race state and read the complete snapshot from the same
      // shipping driver. The unique DB name plus finally cleanup keeps production content.db safe.
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
      assertTrue("isolated test database was not deleted", context.deleteDatabase(databaseName))
    }
  }

  private fun card(id: String) = Card(id = id, title = id, provenance = Provenance("android-test"))

  private companion object {
    const val ITERATIONS = 30
    const val WORKER_COUNT = 3
    const val TIMEOUT_MILLIS = 10_000L
    const val TIMEOUT_SECONDS = 10L
    const val NOW = "2026-07-14T12:00:00Z"
  }
}
