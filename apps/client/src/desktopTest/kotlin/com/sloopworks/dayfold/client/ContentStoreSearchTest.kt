package com.sloopworks.dayfold.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ContentStoreSearchTest {
  private fun store() = ContentStore.create(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))

  @Test fun `readiness distinguishes unsynced empty and synced empty caches`() {
    val store = store()
    assertEquals(SearchReadiness.WAITING_FOR_SYNC, store.searchSnapshot().readiness)

    store.applyDelta(emptyList(), emptyList(), tombstones = emptyList(), nextCursor = "cursor", nowIso = NOW)

    assertEquals(SearchReadiness.EMPTY, store.searchSnapshot().readiness)
  }

  @Test fun `search revision advances for content visibility completion and wipe mutations`() {
    val store = store()
    val initial = store.searchRevision()
    store.applyDelta(
      changedCards = listOf(Card("card", "info", "Soccer", provenance = Provenance("member"))),
      changedHubs = listOf(Hub("hub", title = "School")),
      changedSections = listOf(HubSection("section", "hub", "Forms")),
      changedBlocks = listOf(HubBlock("block", "section", "text", bodyMd = "Permission slip")),
      tombstones = emptyList(),
      nextCursor = "cursor",
      nowIso = NOW,
    )
    val contentRevision = store.searchRevision()
    assertTrue(contentRevision > initial)

    store.hide("block", NOW)
    val hiddenRevision = store.searchRevision()
    assertTrue(hiddenRevision > contentRevision)

    store.unhide("block")
    val unhiddenRevision = store.searchRevision()
    assertTrue(unhiddenRevision > hiddenRevision)

    val subjectRef = SubjectRef.node("hub", "section", "block")
    store.upsertResponseLocal(
      ContentResponse(
        id = "done", kind = ResponseKind.DONE, subjectRef = subjectRef,
        matchScope = MatchScope.SUBJECT, audienceScope = AudienceScope.PERSONAL,
        userId = "member", createdBy = "member", label = "Done",
      ),
    )
    val completed = store.searchSnapshot()
    assertTrue(completed.revision > unhiddenRevision)
    assertEquals(setOf(subjectRef), completed.completedSubjectRefs)

    store.wipe()
    val wiped = store.searchSnapshot()
    assertTrue(wiped.revision > completed.revision)
    assertEquals(SearchReadiness.WAITING_FOR_SYNC, wiped.readiness)
    assertTrue(wiped.cards.isEmpty() && wiped.hubs.isEmpty() && wiped.blocks.isEmpty())
  }

  @Test fun `reactive search snapshot emits one complete post-transaction view`() = runBlocking {
    val store = store()
    store.searchContentFlow().test {
      assertEquals(SearchReadiness.WAITING_FOR_SYNC, awaitItem().readiness)

      store.applyDelta(
        changedCards = listOf(Card("card", "info", "Soccer", provenance = Provenance("member"))),
        changedHubs = listOf(Hub("hub", title = "School")),
        changedSections = listOf(HubSection("section", "hub", "Forms")),
        changedBlocks = listOf(HubBlock("block", "section", "text", bodyMd = "Permission slip")),
        tombstones = emptyList(), nextCursor = "cursor", nowIso = NOW,
      )

      val snapshot = awaitItem()
      assertEquals(listOf("card"), snapshot.cards.map { it.id })
      assertEquals(listOf("hub"), snapshot.hubs.map { it.id })
      assertEquals(listOf("section"), snapshot.sections.map { it.id })
      assertEquals(listOf("block"), snapshot.blocks.map { it.id })
      assertEquals(SearchReadiness.READY, snapshot.readiness)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun `writer waits until multi-query search snapshot is complete`() {
    val store = store()
    store.applyDelta(
      changedCards = listOf(Card("old-card", "info", "Old", provenance = Provenance("member"))),
      changedHubs = listOf(Hub("old-hub", title = "Old hub")),
      tombstones = emptyList(), nextCursor = "old", nowIso = NOW,
    )
    val snapshotReachedMiddle = CountDownLatch(1)
    val finishSnapshot = CountDownLatch(1)
    val writerFinished = CountDownLatch(1)
    var snapshot: SearchContentSnapshot? = null
    store.searchSnapshotStageHook = { stage ->
      if (stage == 1) {
        snapshotReachedMiddle.countDown()
        check(finishSnapshot.await(5, TimeUnit.SECONDS))
      }
    }
    val reader = thread { snapshot = store.searchSnapshot() }
    assertTrue(snapshotReachedMiddle.await(5, TimeUnit.SECONDS))
    val writer = thread {
      store.applyDelta(
        changedCards = listOf(Card("new-card", "info", "New", provenance = Provenance("member"))),
        changedHubs = listOf(Hub("new-hub", title = "New hub")),
        tombstones = emptyList(), nextCursor = "new", nowIso = NOW,
      )
      writerFinished.countDown()
    }
    assertFalse(writerFinished.await(150, TimeUnit.MILLISECONDS))
    finishSnapshot.countDown()
    reader.join(5_000)
    writer.join(5_000)
    store.searchSnapshotStageHook = null

    assertEquals(listOf("old-card"), snapshot?.cards?.map { it.id })
    assertEquals(listOf("old-hub"), snapshot?.hubs?.map { it.id })
    assertTrue(writerFinished.count == 0L)
  }

  private companion object {
    const val NOW = "2026-08-19T12:00:00Z"
  }
}
