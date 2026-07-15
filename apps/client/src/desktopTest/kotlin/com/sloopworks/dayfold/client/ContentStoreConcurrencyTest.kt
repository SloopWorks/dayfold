package com.sloopworks.dayfold.client

import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.sloopworks.dayfold.client.db.ContentDb
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.RepeatedTest

class ContentStoreConcurrencyTest {
  @Test fun `concurrent store transactions are serialized before reaching the driver`() {
    val fixture = fixture()
    val errors = ConcurrentLinkedQueue<Throwable>()

    val first = writer("first-writer", errors) {
      fixture.store.applyDelta(
        changedCards = listOf(card("first")),
        changedHubs = emptyList(),
        tombstones = emptyList(),
        nextCursor = "first",
        nowIso = NOW,
      )
    }
    assertTrue(fixture.driver.firstTransactionEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

    val secondStarted = CountDownLatch(1)
    val second = writer("second-writer", errors) {
      secondStarted.countDown()
      fixture.store.applyDelta(
        changedCards = listOf(card("second")),
        changedHubs = emptyList(),
        tombstones = emptyList(),
        nextCursor = "second",
        nowIso = NOW,
      )
    }
    assertTrue(secondStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

    // The first writer is deliberately parked at the driver boundary. The second writer must
    // remain at ContentStore's gate rather than starting a nested transaction on the connection.
    assertFalse(fixture.driver.secondTransactionEntered.await(BLOCKED_CHECK_MILLIS, TimeUnit.MILLISECONDS))

    fixture.driver.releaseFirstTransaction.countDown()
    first.join(TIMEOUT_MILLIS)
    second.join(TIMEOUT_MILLIS)

    assertFalse(first.isAlive)
    assertFalse(second.isAlive)
    assertTrue(errors.isEmpty(), errors.joinToString(separator = "\n"))
    assertEquals(1, fixture.driver.maxConcurrentTransactionEntries.get())
    assertEquals(listOf("first", "second"), fixture.store.activeCards().map { it.id })
    assertEquals("second", fixture.store.cursor())
  }

  @Test fun `notification snapshot waits for an in-flight writer and sees its complete commit`() {
    val fixture = fixture()
    val errors = ConcurrentLinkedQueue<Throwable>()
    val snapshotStarted = CountDownLatch(1)
    val snapshotCompleted = CountDownLatch(1)
    var snapshot: NotifSnapshot? = null

    val writer = writer("snapshot-writer", errors) {
      fixture.store.applyDelta(
        changedCards = listOf(card("card")),
        changedHubs = listOf(Hub(id = "hub", title = "Hub")),
        changedSections = listOf(HubSection(id = "section", hubId = "hub", title = "Section")),
        changedBlocks = listOf(HubBlock(id = "block", sectionId = "section", type = "text")),
        changedPlaces = listOf(Place(id = "place", kind = "home", label = "Home", lat = 1.0, lng = 2.0, radiusM = 100)),
        tombstones = emptyList(),
        nextCursor = "cursor",
        nowIso = NOW,
      )
    }
    assertTrue(fixture.driver.firstTransactionEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

    val reader = writer("snapshot-reader", errors) {
      snapshotStarted.countDown()
      snapshot = fixture.store.notifSnapshot()
      snapshotCompleted.countDown()
    }
    assertTrue(snapshotStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    assertFalse(snapshotCompleted.await(BLOCKED_CHECK_MILLIS, TimeUnit.MILLISECONDS))

    fixture.driver.releaseFirstTransaction.countDown()
    writer.join(TIMEOUT_MILLIS)
    reader.join(TIMEOUT_MILLIS)

    assertFalse(writer.isAlive)
    assertFalse(reader.isAlive)
    assertTrue(errors.isEmpty(), errors.joinToString(separator = "\n"))
    val result = requireNotNull(snapshot)
    assertEquals(listOf("card"), result.cards.map { it.id })
    assertEquals(listOf("hub"), result.hubs.map { it.id })
    assertEquals(listOf("section"), result.sections.map { it.id })
    assertEquals(listOf("block"), result.blocks.map { it.id })
    assertEquals(listOf("place"), result.places.map { it.id })
  }

  @RepeatedTest(20)
  fun `mixed public mutations remain serialized and leave readable invariants`() {
    val fixture = fixture(blockFirstTransaction = false)
    val errors = ConcurrentLinkedQueue<Throwable>()
    val ready = CountDownLatch(6)
    val start = CountDownLatch(1)

    fun mixedWriter(name: String, block: (Int) -> Unit): Thread = writer(name, errors) {
      ready.countDown()
      check(start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "Timed out waiting for mixed-write start" }
      repeat(MIXED_WRITE_ITERATIONS, block)
    }

    val writers = listOf(
      mixedWriter("delta-writer") { index ->
        fixture.store.applyDelta(
          changedCards = listOf(card("card-$index")),
          changedHubs = listOf(Hub(id = "hub-$index", title = "Hub $index")),
          changedSections = listOf(HubSection(id = "section-$index", hubId = "hub-$index", title = "Section")),
          changedBlocks = listOf(checklistBlock("block-$index", "section-$index")),
          tombstones = emptyList(),
          nextCursor = "cursor-$index",
          nowIso = NOW,
        )
      },
      mixedWriter("membership-writer") { index ->
        fixture.store.replaceMemberships(
          listOf(FamilyMembership(familyId = "family-$index", name = "Family $index", role = "adult", status = "active")),
        )
      },
      mixedWriter("surfacing-writer") { index ->
        fixture.store.recordShown("subject-$index", NOW)
        fixture.store.recordDismissed("subject-$index", NOW)
      },
      mixedWriter("notification-writer") { index ->
        fixture.store.setNotifConfig(NotifConfig(enabled = index % 2 == 0, dailyCap = 3))
        fixture.store.logNotification("subject-$index", NOW)
      },
      mixedWriter("hidden-writer") { index ->
        fixture.store.hide("hidden-$index", NOW)
        fixture.store.unhide("hidden-$index")
      },
      mixedWriter("wipe-outbox-writer") { index ->
        fixture.store.enqueueBlockDelete("block-$index", NOW, "delete-$index")
        fixture.store.nextPendingOp()?.let { op ->
          fixture.store.markOpInflight(op.opId)
          fixture.store.bumpOpAttempt(op.opId)
          fixture.store.ackOp(op.opId, resultVersion = index.toLong())
        }
        if (index % 5 == 0) fixture.store.wipe() else fixture.store.wipeForResync()
      },
    )

    assertTrue(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    start.countDown()
    writers.forEach { it.join(TIMEOUT_MILLIS) }

    assertTrue(writers.none(Thread::isAlive))
    assertTrue(errors.isEmpty(), errors.joinToString(separator = "\n"))

    // Establish one deterministic post-race state and read every family of data touched above.
    // This proves the connection remains usable and no partial transaction damaged an invariant.
    fixture.store.wipe()
    fixture.store.applyDelta(
      changedCards = listOf(card("final-card")),
      changedHubs = listOf(Hub(id = "final-hub", title = "Final Hub")),
      changedSections = listOf(HubSection(id = "final-section", hubId = "final-hub", title = "Final Section")),
      changedBlocks = listOf(checklistBlock("final-block", "final-section")),
      tombstones = emptyList(),
      nextCursor = "final-cursor",
      nowIso = NOW,
    )
    fixture.store.replaceMemberships(
      listOf(FamilyMembership(familyId = "final-family", name = "Final Family", role = "owner", status = "active")),
    )
    fixture.store.recordShown("final-subject", NOW)
    fixture.store.setNotifConfig(NotifConfig(enabled = true, dailyCap = 4))
    fixture.store.logNotification("final-subject", NOW)
    fixture.store.hide("final-block", NOW)
    fixture.store.unhide("final-block")
    fixture.store.enqueueBlockToggle("final-block", "item", true, "member", NOW, "final-op")

    val snapshot = fixture.store.notifSnapshot()
    assertEquals(listOf("final-card"), snapshot.cards.map { it.id })
    assertEquals(listOf("final-hub"), snapshot.hubs.map { it.id })
    assertEquals(setOf("final-subject"), snapshot.surfacing.keys)
    assertTrue(snapshot.config.enabled)
    assertEquals(listOf("final-subject"), snapshot.log.map { it.subjectKey })
    assertEquals(listOf("final-family"), fixture.store.cachedMemberships().map { it.familyId })
    assertEquals("final-cursor", fixture.store.cursor())
    assertEquals(1, fixture.store.pendingOpCount())
    assertEquals("pending", fixture.store.blockLocalState("final-block"))

    fixture.store.wipe()
    assertTrue(fixture.store.notifSnapshot().cards.isEmpty())
    assertTrue(fixture.store.cachedMemberships().isEmpty())
    assertEquals(0, fixture.store.pendingOpCount())
  }

  private fun fixture(blockFirstTransaction: Boolean = true): Fixture {
    val delegate = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    ContentDb.Schema.create(delegate)
    val driver = BlockingTransactionDriver(delegate, blockFirstTransaction)
    return Fixture(ContentStore(driver), driver)
  }

  private fun writer(
    name: String,
    errors: ConcurrentLinkedQueue<Throwable>,
    block: () -> Unit,
  ): Thread = thread(name = name) {
    try {
      block()
    } catch (error: Throwable) {
      errors += error
    }
  }

  private fun card(id: String) = Card(id = id, title = id, provenance = Provenance("test"))

  private fun checklistBlock(id: String, sectionId: String) = HubBlock(
    id = id,
    sectionId = sectionId,
    type = "checklist",
    payload = BlockPayload(items = listOf(ChecklistItem(id = "item", text = "Pack"))),
  )

  private data class Fixture(
    val store: ContentStore,
    val driver: BlockingTransactionDriver,
  )

  private class BlockingTransactionDriver(
    private val delegate: SqlDriver,
    private val blockFirstTransaction: Boolean,
  ) : SqlDriver by delegate {
    val firstTransactionEntered = CountDownLatch(1)
    val secondTransactionEntered = CountDownLatch(1)
    val releaseFirstTransaction = CountDownLatch(1)
    val maxConcurrentTransactionEntries = AtomicInteger(0)

    private val transactionEntries = AtomicInteger(0)
    private val transactionCalls = AtomicInteger(0)

    override fun newTransaction(): QueryResult<Transacter.Transaction> {
      val active = transactionEntries.incrementAndGet()
      maxConcurrentTransactionEntries.updateAndGet { previous -> maxOf(previous, active) }
      val call = transactionCalls.incrementAndGet()
      try {
        if (call == 1) {
          firstTransactionEntered.countDown()
          if (blockFirstTransaction) {
            check(releaseFirstTransaction.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
              "Timed out waiting to release the first ContentStore transaction"
            }
          }
        } else if (call == 2) {
          secondTransactionEntered.countDown()
        }
        return delegate.newTransaction()
      } finally {
        transactionEntries.decrementAndGet()
      }
    }
  }

  private companion object {
    const val NOW = "2026-07-14T12:00:00Z"
    const val BLOCKED_CHECK_MILLIS = 250L
    const val MIXED_WRITE_ITERATIONS = 30
    const val TIMEOUT_MILLIS = 5_000L
    const val TIMEOUT_SECONDS = 5L
  }
}
