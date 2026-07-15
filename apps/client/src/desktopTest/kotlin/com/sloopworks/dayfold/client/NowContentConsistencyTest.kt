package com.sloopworks.dayfold.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NowContentConsistencyTest {
  @Test fun `Now content emissions never mix multi-table revisions`() = runBlocking<Unit> {
    val contentStore = ContentStore.create(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
    fun writeRevision(revision: String) {
      contentStore.applyDelta(
        changedCards = emptyList(),
        changedHubs = listOf(Hub(id = "h1", title = "Hub $revision")),
        changedSections = listOf(HubSection(id = "s1", hubId = "h1", title = revision)),
        changedBlocks = listOf(HubBlock(id = "b1", sectionId = "s1", type = "text", bodyMd = revision)),
        changedPlaces = listOf(Place(id = "p1", label = revision, lat = 1.0, lng = 2.0)),
        tombstones = emptyList(),
        nextCursor = revision,
        nowIso = "2026-07-14T00:00:00Z",
      )
    }

    writeRevision("old")
    val emissions = Channel<NowContent>(Channel.UNLIMITED)
    val collector = launch { contentStore.nowContentFlow(Dispatchers.Default).collect(emissions::send) }
    val old = withTimeout(3_000) { emissions.receive() }
    assertRevision(old, "old")

    val snapshotBlocked = CountDownLatch(1)
    val releaseSnapshot = CountDownLatch(1)
    val blockOnce = AtomicBoolean(true)
    contentStore.nowSnapshotStageHook = { stage ->
      if (stage == 1 && blockOnce.compareAndSet(true, false)) {
        snapshotBlocked.countDown()
        check(releaseSnapshot.await(5, TimeUnit.SECONDS)) { "snapshot was not released" }
      }
    }

    writeRevision("first")
    assertTrue(
      withContext(Dispatchers.IO) { snapshotBlocked.await(5, TimeUnit.SECONDS) },
      "observer did not enter the gated snapshot",
    )
    val secondWriterStarted = CountDownLatch(1)
    val secondWriter = launch(Dispatchers.Default) {
      secondWriterStarted.countDown()
      writeRevision("second")
    }
    assertTrue(withContext(Dispatchers.IO) { secondWriterStarted.await(5, TimeUnit.SECONDS) })
    releaseSnapshot.countDown()

    secondWriter.join()
    withTimeout(3_000) {
      var sawSecond = false
      while (!sawSecond) {
        val emission = emissions.receive()
        val revision = requireNotNull(emission.sections.single().title)
        assertTrue(revision == "first" || revision == "second")
        assertRevision(emission, revision)
        sawSecond = revision == "second"
      }
    }

    contentStore.nowSnapshotStageHook = null
    collector.cancelAndJoin()
  }

  private fun assertRevision(content: NowContent, expected: String) {
    assertEquals(expected, content.sections.single().title)
    assertEquals(expected, content.blocks.single().bodyMd)
    assertEquals(expected, content.places.single().label)
  }
}
