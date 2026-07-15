package com.sloopworks.dayfold.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.reduxkotlin.StoreEnhancer

class ContentBridgeTest {
  @Test fun `family start and replacement cannot invert publication and session locks`() = runBlocking {
    val publicationEntered = CountDownLatch(1)
    val releasePublication = CountDownLatch(1)
    // Keep the deliberately blocked collector off Dispatchers.Default. On a small CI runner, the
    // five family collectors can otherwise occupy every scheduler worker while waiting for the
    // same publication monitor and starve the replacement coroutine this test is meant to observe.
    val bridgeDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val fixture = fixture(
      scopeDispatcher = bridgeDispatcher,
      ownedDispatcher = bridgeDispatcher,
      beforeFamilyPublicationCommit = {
        publicationEntered.countDown()
        check(releasePublication.await(3, TimeUnit.SECONDS)) {
          "test did not release the blocked family publication"
        }
      },
    )
    try {
      val first = fixture.bridge.startFamily(fixture.family)
      assertTrue(publicationEntered.await(3, TimeUnit.SECONDS))

      // The collector owns PublicationBoundary while paused immediately before entering the
      // SessionCoordinator. Repeating start must read handle openness without taking that boundary
      // while it holds the session gate; the old opposite lock order deadlocked here.
      val repeated = async(Dispatchers.Default) { fixture.bridge.startFamily(fixture.family) }
      assertSame(first, withTimeout(1_000) { repeated.await() })

      // Replacement still honors close -> join before a new generation is admitted.
      val closed = async(Dispatchers.Default) { first.cancelAndJoin() }
      withTimeout(1_000) {
        while (first.isOpen) yield()
      }
      assertFailsWith<IllegalStateException> {
        fixture.bridge.startFamily(fixture.family)
      }
      releasePublication.countDown()
      withTimeout(1_000) { closed.await() }
      val familyB = fixture.coordinator.selectFamily(fixture.auth, "family-b")!!
      fixture.bridge.startFamily(familyB).cancelAndJoin()
    } finally {
      releasePublication.countDown()
      fixture.close()
    }
  }

  @Test fun `family start is idempotent and suppresses identical projections`() = runBlocking {
    val fixture = fixture()
    try {
      val card = Card("card-1", title = "Cached")
      fixture.content.applyDelta(
        changedCards = listOf(card),
        changedHubs = emptyList(),
        changedSections = emptyList(),
        changedBlocks = emptyList(),
        tombstones = emptyList(),
        nextCursor = "cursor-1",
        nowIso = "2026-07-14T10:00:00Z",
      )

      val first = fixture.bridge.startFamily(fixture.family)
      val second = fixture.bridge.startFamily(fixture.family)
      assertSame(first, second)
      awaitState(fixture) { it.cards == listOf(card) }
      assertEquals(1, fixture.counter.cards.get())

      fixture.content.applyDelta(
        changedCards = listOf(card),
        changedHubs = emptyList(),
        changedSections = emptyList(),
        changedBlocks = emptyList(),
        tombstones = emptyList(),
        nextCursor = "cursor-2",
        nowIso = "2026-07-14T10:01:00Z",
      )
      delay(200)
      assertEquals(1, fixture.counter.cards.get())

      first.cancelAndJoin()
      assertTrue(fixture.parentJob.isActive, "bridge cancellation must not cancel its injected scope")
    } finally {
      fixture.close()
    }
  }

  @Test fun `device projection survives explicit family replacement`() = runBlocking {
    val fixture = fixture()
    try {
      val device = fixture.bridge.startDevice()
      awaitState(fixture) { fixture.counter.notifConfig.get() == 1 }
      val firstFamily = fixture.bridge.startFamily(fixture.family)
      firstFamily.cancelAndJoin()

      val familyB = fixture.coordinator.selectFamily(fixture.auth, "family-b")!!
      val secondFamily = fixture.bridge.startFamily(familyB)
      assertSame(device, fixture.bridge.startDevice())

      val enabled = NotifConfig(enabled = true, dailyCap = 2)
      fixture.content.setNotifConfig(enabled)
      awaitState(fixture) { it.notifConfig == enabled }
      assertTrue(fixture.counter.notifConfig.get() >= 2)

      secondFamily.cancelAndJoin()
      device.cancelAndJoin()
      assertTrue(fixture.parentJob.isActive)
    } finally {
      fixture.close()
    }
  }

  @Test fun `an A to B to A cycle rejects stale family emissions`() = runBlocking {
    val fixture = fixture()
    try {
      val initial = Card("initial", title = "Initial")
      fixture.content.applyDelta(
        changedCards = listOf(initial),
        changedHubs = emptyList(),
        changedSections = emptyList(),
        changedBlocks = emptyList(),
        tombstones = emptyList(),
        nextCursor = "cursor-1",
        nowIso = "2026-07-14T10:00:00Z",
      )
      val staleA = fixture.bridge.startFamily(fixture.family)
      awaitState(fixture) { it.cards == listOf(initial) }

      fixture.coordinator.selectFamily(fixture.auth, "family-b")
      fixture.coordinator.selectFamily(fixture.auth, "family-a")
      val late = Card("late", title = "Must not publish")
      fixture.content.applyDelta(
        changedCards = listOf(late),
        changedHubs = emptyList(),
        changedSections = emptyList(),
        changedBlocks = emptyList(),
        tombstones = emptyList(),
        nextCursor = "cursor-2",
        nowIso = "2026-07-14T10:01:00Z",
      )
      delay(200)

      assertEquals(listOf(initial), fixture.store.state.cards)
      assertEquals(1, fixture.counter.cards.get())
      staleA.cancelAndJoin()
    } finally {
      fixture.close()
    }
  }

  @Test fun `closed publication boundary rejects later database emissions`() = runBlocking {
    val fixture = fixture()
    try {
      val initial = Card("initial", title = "Initial")
      fixture.content.applyDelta(
        changedCards = listOf(initial),
        changedHubs = emptyList(),
        changedSections = emptyList(),
        changedBlocks = emptyList(),
        tombstones = emptyList(),
        nextCursor = "cursor-1",
        nowIso = "2026-07-14T10:00:00Z",
      )
      val handle = fixture.bridge.startFamily(fixture.family)
      awaitState(fixture) { it.cards == listOf(initial) }
      handle.cancel()

      fixture.content.applyDelta(
        changedCards = listOf(Card("late", title = "Late")),
        changedHubs = emptyList(),
        changedSections = emptyList(),
        changedBlocks = emptyList(),
        tombstones = emptyList(),
        nextCursor = "cursor-2",
        nowIso = "2026-07-14T10:01:00Z",
      )
      handle.awaitClosed()
      delay(100)

      assertEquals(listOf(initial), fixture.store.state.cards)
      assertEquals(1, fixture.counter.cards.get())
      assertTrue(fixture.parentJob.isActive)
    } finally {
      fixture.close()
    }
  }

  private suspend fun awaitState(
    fixture: Fixture,
    predicate: (AppState) -> Boolean,
  ) {
    withTimeout(3_000) {
      while (!predicate(fixture.store.state)) delay(10)
    }
  }

  private fun fixture(
    scopeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ownedDispatcher: ExecutorCoroutineDispatcher? = null,
    beforeFamilyPublicationCommit: () -> Unit = {},
  ): Fixture {
    val content = ContentStore.create(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
    val parentJob = SupervisorJob()
    val scope = CoroutineScope(parentJob + scopeDispatcher)
    val coordinator = SessionCoordinator(
      refreshScope = scope,
      refreshSession = { error("refresh not expected") },
      commitRotation = {},
    )
    val auth = coordinator.install(Session("access", "refresh"))
    val family = coordinator.selectFamily(auth, "family-a")!!
    val counter = ActionCounter()
    val store = createTestAppStore(
      debug = false,
      extraEnhancer = counter.enhancer,
    )
    val bridge = ContentBridge(
      store = store,
      contentStore = content,
      sessionCoordinator = coordinator,
      scope = scope,
      databaseDispatcher = Dispatchers.Default,
      beforeFamilyPublicationCommit = beforeFamilyPublicationCommit,
    )
    return Fixture(
      content,
      coordinator,
      auth,
      family,
      store,
      bridge,
      counter,
      parentJob,
      scope,
      ownedDispatcher,
    )
  }

  private class ActionCounter {
    val cards = AtomicInteger()
    val notifConfig = AtomicInteger()

    val enhancer: StoreEnhancer<AppState> = { creator ->
      { reducer, initial, enhancer ->
        val store = creator(reducer, initial, enhancer)
        val dispatch = store.dispatch
        store.dispatch = { action ->
          when (action) {
            is CardsLoaded -> cards.incrementAndGet()
            is NotifConfigLoaded -> notifConfig.incrementAndGet()
          }
          dispatch(action)
        }
        store
      }
    }
  }

  private class Fixture(
    val content: ContentStore,
    val coordinator: SessionCoordinator,
    val auth: AuthSessionContext,
    val family: FamilySessionContext,
    val store: org.reduxkotlin.Store<AppState>,
    val bridge: ContentBridge,
    val counter: ActionCounter,
    val parentJob: Job,
    private val scope: CoroutineScope,
    private val ownedDispatcher: ExecutorCoroutineDispatcher?,
  ) {
    fun close() {
      scope.cancel()
      ownedDispatcher?.close()
    }
  }
}
