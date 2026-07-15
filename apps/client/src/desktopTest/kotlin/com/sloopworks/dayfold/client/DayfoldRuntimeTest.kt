package com.sloopworks.dayfold.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.reduxkotlin.Store
import org.reduxkotlin.StoreEnhancer

class DayfoldRuntimeTest {
  @Test fun `concurrent lifecycle calls are idempotent and close is repeatable`() =
    runBlocking<Unit> {
      val prepareEntered = CompletableDeferred<Unit>()
      val releasePrepare = CompletableDeferred<Unit>()
      val prepares = AtomicInteger()
      val resumes = AtomicInteger()
      val pauses = AtomicInteger()
      val closes = AtomicInteger()
      val fixture = fixture(
        prepareSchema = {
          prepares.incrementAndGet()
          prepareEntered.complete(Unit)
          releasePrepare.await()
        },
        resumeSync = { _, _, _ -> resumes.incrementAndGet() },
        pauseSync = { pauses.incrementAndGet() },
        closeResources = { closes.incrementAndGet() },
      )

      val starts = List(12) { launch(Dispatchers.Default) { fixture.runtime.start() } }
      prepareEntered.await()
      assertEquals(DayfoldRuntimeState.STARTING, fixture.runtime.lifecycleState)
      releasePrepare.complete(Unit)
      starts.joinAll()
      assertEquals(1, prepares.get())
      assertEquals(DayfoldRuntimeState.PAUSED, fixture.runtime.lifecycleState)

      List(12) { launch(Dispatchers.Default) { fixture.runtime.resume() } }.joinAll()
      assertEquals(1, resumes.get())
      assertEquals(DayfoldRuntimeState.ACTIVE, fixture.runtime.lifecycleState)

      List(12) { launch(Dispatchers.Default) { fixture.runtime.pause() } }.joinAll()
      assertEquals(1, pauses.get())
      assertEquals(DayfoldRuntimeState.PAUSED, fixture.runtime.lifecycleState)

      fixture.runtime.cancel()
      fixture.runtime.cancel()
      List(8) { launch { fixture.runtime.awaitClosed() } }.joinAll()
      fixture.runtime.awaitClosed()
      assertEquals(1, closes.get())
      assertEquals(DayfoldRuntimeState.CLOSED, fixture.runtime.lifecycleState)
    }

  @Test fun `schema preparation finishes before the first device projection`() =
    runBlocking<Unit> {
      val events = Collections.synchronizedList(mutableListOf<String>())
      val fixture = fixture(
        prepareSchema = {
          events += "schema"
          fixtureSchemaWork(events)
        },
        onNotifConfig = { events += "device" },
        closeResources = {},
      )
      try {
        fixture.runtime.start()
        awaitState(fixture.store) { events.contains("device") }
        assertTrue(events.indexOf("schema-done") < events.indexOf("device"))
      } finally {
        fixture.close()
      }
    }

  @Test fun `cancel during schema preparation starts no projection or auth work`() =
    runBlocking<Unit> {
      val entered = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      val authStarted = AtomicInteger()
      val fixture = fixture(
        prepareSchema = {
          entered.complete(Unit)
          release.await()
        },
        restoreAuth = { _, _ -> authStarted.incrementAndGet() },
      )

      val start = launch { fixture.runtime.start() }
      entered.await()
      fixture.runtime.cancel()
      release.complete(Unit)
      fixture.runtime.awaitClosed()
      start.cancelAndJoin()

      assertEquals(0, authStarted.get())
      assertEquals(0, fixture.actionCounter.notifConfig.get())
      assertEquals(DayfoldRuntimeState.CLOSED, fixture.runtime.lifecycleState)
    }

  @Test fun `cancel returns before a blocked resource close`() = runBlocking<Unit> {
    val closeEntered = CompletableDeferred<Unit>()
    val releaseClose = CompletableDeferred<Unit>()
    val fixture = fixture(
      closeResources = {
        closeEntered.complete(Unit)
        runBlocking { releaseClose.await() }
      },
    )

    fixture.runtime.start()
    fixture.runtime.cancel()
    closeEntered.await()
    assertEquals(DayfoldRuntimeState.CLOSING, fixture.runtime.lifecycleState)

    releaseClose.complete(Unit)
    fixture.runtime.awaitClosed()
    assertEquals(DayfoldRuntimeState.CLOSED, fixture.runtime.lifecycleState)
  }

  @Test fun `cancel closes network and collector publication before cancellation completes`() =
    runBlocking<Unit> {
      val networkEntered = CompletableDeferred<Unit>()
      val releaseNetwork = CompletableDeferred<Unit>()
      val networkFinished = CompletableDeferred<Unit>()
      val closeSawFinished = CompletableDeferred<Boolean>()
      val latePublishes = AtomicInteger()
      val fixture = fixture(
        resumeSync = { scope, _, publication ->
          scope.launch {
            try {
              networkEntered.complete(Unit)
              withContext(NonCancellable) { releaseNetwork.await() }
              publication.publish { latePublishes.incrementAndGet() }
            } finally {
              networkFinished.complete(Unit)
            }
          }
        },
        closeResources = { closeSawFinished.complete(networkFinished.isCompleted) },
      )

      fixture.runtime.start()
      val auth = fixture.installAuth()
      fixture.runtime.replaceFamily(auth, "family-a")
      fixture.runtime.resume()
      networkEntered.await()

      fixture.runtime.cancel()
      assertFalse(fixture.runtime.lifecycleState == DayfoldRuntimeState.ACTIVE)
      releaseNetwork.complete(Unit)
      fixture.content.applyDelta(
        changedCards = listOf(Card("late-card", title = "Late")),
        changedHubs = emptyList(),
        changedSections = emptyList(),
        changedBlocks = emptyList(),
        tombstones = emptyList(),
        nextCursor = "late",
        nowIso = "2026-07-14T10:00:00Z",
      )
      fixture.runtime.awaitClosed()

      assertTrue(closeSawFinished.await(), "resources must close after runtime children terminate")
      assertEquals(0, latePublishes.get())
      assertNull(fixture.store.state.error)
      assertTrue(fixture.store.state.cards.isEmpty())
    }

  @Test fun `family replacement is ordered and rejects an ABA context`() = runBlocking<Unit> {
    val events = Collections.synchronizedList(mutableListOf<String>())
    val fixture = fixture(
      wipeFamily = {
        events += "wipe"
      },
      resumeSync = { _, family, _ -> events += "resume:${family?.familyId}" },
    )
    try {
      fixture.runtime.start()
      val auth = fixture.installAuth()
      val firstA = fixture.runtime.replaceFamily(auth, "family-a")!!
      fixture.runtime.resume()
      val familyB = fixture.runtime.replaceFamily(auth, "family-b")!!
      val secondA = fixture.runtime.replaceFamily(auth, "family-a")!!

      assertFalse(fixture.coordinator.isCurrent(firstA))
      assertFalse(fixture.coordinator.isCurrent(familyB))
      assertTrue(fixture.coordinator.isCurrent(secondA))
      assertNotEquals(firstA.familyRevision, secondA.familyRevision)
      assertEquals(
        listOf("wipe", "resume:family-a", "wipe", "resume:family-b", "wipe", "resume:family-a"),
        events,
      )
    } finally {
      fixture.close()
    }
  }

  @Test fun `first bind reuses the coordinator selected family without wiping offline cache`() =
    runBlocking<Unit> {
      val wipes = AtomicInteger()
      val fixture = fixture(wipeFamily = { wipes.incrementAndGet() })
      try {
        fixture.runtime.start()
        val auth = fixture.installAuth()
        val selected = fixture.coordinator.selectFamily(auth, "family-a")!!

        val bound = fixture.runtime.replaceFamily(auth, "family-a")

        assertEquals(selected.familyRevision, bound?.familyRevision)
        assertEquals(0, wipes.get())
      } finally {
        fixture.close()
      }
    }

  @Test fun `device projection survives family replacement`() = runBlocking<Unit> {
    val fixture = fixture()
    try {
      fixture.runtime.start()
      val auth = fixture.installAuth()
      fixture.runtime.replaceFamily(auth, "family-a")
      fixture.runtime.replaceFamily(auth, "family-b")

      val config = NotifConfig(enabled = true, dailyCap = 3)
      fixture.content.setNotifConfig(config)
      awaitState(fixture.store) { it.notifConfig == config }

      assertTrue(fixture.actionCounter.notifConfig.get() >= 2)
      assertEquals(DayfoldRuntimeState.PAUSED, fixture.runtime.lifecycleState)
    } finally {
      fixture.close()
    }
  }

  @Test fun `terminal family fence invalidates before join and preserves device bridge`() =
    runBlocking<Unit> {
      val workerStarted = CompletableDeferred<Unit>()
      val cancellationObserved = CompletableDeferred<Unit>()
      val releaseWorker = CompletableDeferred<Unit>()
      val terminalReturned = CompletableDeferred<Unit>()
      val latePublishes = AtomicInteger()
      val fixture = fixture(
        resumeSync = { scope, _, publication ->
          scope.launch {
            workerStarted.complete(Unit)
            try {
              awaitCancellation()
            } finally {
              cancellationObserved.complete(Unit)
              withContext(NonCancellable) {
                releaseWorker.await()
                publication.publish { latePublishes.incrementAndGet() }
              }
            }
          }
        },
      )
      try {
        fixture.runtime.start()
        val auth = fixture.installAuth()
        fixture.runtime.replaceFamily(auth, "family-a")
        fixture.runtime.resume()
        workerStarted.await()

        val terminal = launch {
          fixture.runtime.closeFamilyForTerminal()
          terminalReturned.complete(Unit)
        }
        cancellationObserved.await()
        assertNull(fixture.coordinator.familySnapshot("family-a"))
        assertFalse(terminalReturned.isCompleted, "terminal hook must join family workers")

        releaseWorker.complete(Unit)
        terminal.join()
        assertEquals(0, latePublishes.get())

        val config = NotifConfig(enabled = true, dailyCap = 4)
        fixture.content.setNotifConfig(config)
        awaitState(fixture.store) { it.notifConfig == config }
      } finally {
        fixture.close()
      }
    }

  @Test fun `family replacement closes engine admission then joins work before wipe`() =
    runBlocking<Unit> {
      val events = Collections.synchronizedList(mutableListOf<String>())
      val workerStarted = CompletableDeferred<Unit>()
      val cancellationObserved = CompletableDeferred<Unit>()
      val releaseWorker = CompletableDeferred<Unit>()
      val fixture = fixture(
        bindFamilyWork = { _, scope, _ ->
          scope.launch {
            workerStarted.complete(Unit)
            try {
              awaitCancellation()
            } finally {
              events += "cancelled"
              cancellationObserved.complete(Unit)
              withContext(NonCancellable) { releaseWorker.await() }
              events += "finished"
            }
          }
        },
        closeFamilyWorkAdmission = { events += "admission-closed" },
        wipeFamily = { events += "wipe" },
      )
      try {
        fixture.runtime.start()
        val auth = fixture.installAuth()
        fixture.runtime.replaceFamily(auth, "family-a")
        workerStarted.await()

        val replacement = launch { fixture.runtime.replaceFamily(auth, "family-b") }
        cancellationObserved.await()
        assertEquals(listOf("wipe", "admission-closed", "cancelled"), events)
        assertFalse(replacement.isCompleted, "replacement must join engine work before wipe")

        releaseWorker.complete(Unit)
        replacement.join()
        assertEquals(
          listOf("wipe", "admission-closed", "cancelled", "finished", "wipe"),
          events,
        )
      } finally {
        fixture.close()
      }
    }

  private suspend fun fixtureSchemaWork(events: MutableList<String>) {
    events += "schema-done"
  }

  private fun fixture(
    prepareSchema: suspend () -> Unit = {},
    restoreAuth: suspend (CoroutineScope, PublicationBoundary) -> Unit = { _, _ -> },
    resumeSync: suspend (CoroutineScope, FamilySessionContext?, PublicationBoundary) -> Unit =
      { _, _, _ -> },
    pauseSync: suspend () -> Unit = {},
    wipeFamily: suspend () -> Unit = {},
    closeResources: () -> Unit = {},
    onNotifConfig: () -> Unit = {},
    bindFamilyWork: (FamilySessionContext, CoroutineScope, PublicationBoundary) -> Unit =
      { _, _, _ -> },
    closeFamilyWorkAdmission: () -> Unit = {},
  ): Fixture {
    val content = ContentStore.create(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
    val counter = ActionCounter()
    val store = createTestAppStore(debug = false, extraEnhancer = counter.enhancer)
    lateinit var coordinator: SessionCoordinator
    val runtime = DayfoldRuntime(
      backgroundDispatcher = Dispatchers.Default,
      componentsFactory = { scope ->
        coordinator = SessionCoordinator(
          refreshScope = scope,
          refreshSession = { error("refresh not expected") },
          commitRotation = {},
        )
        DayfoldRuntimeComponents(
          sessionCoordinator = coordinator,
          contentBridge = ContentBridge(
            store = store,
            contentStore = content,
            sessionCoordinator = coordinator,
            scope = scope,
            databaseDispatcher = Dispatchers.Default,
          ),
          bindFamilyWork = bindFamilyWork,
          closeFamilyWorkAdmission = closeFamilyWorkAdmission,
        )
      },
      prepareSchema = prepareSchema,
      restoreAuth = restoreAuth,
      resumeSync = resumeSync,
      pauseSync = pauseSync,
      wipeFamily = wipeFamily,
      closeResources = closeResources,
    )
    counter.onNotifConfig = onNotifConfig
    return Fixture(content, store, coordinator, runtime, counter)
  }

  private suspend fun awaitState(store: Store<AppState>, predicate: (AppState) -> Boolean) {
    if (predicate(store.state)) return
    val reached = CompletableDeferred<Unit>()
    val unsubscribe = store.subscribe { if (predicate(store.state)) reached.complete(Unit) }
    try {
      if (predicate(store.state)) reached.complete(Unit)
      withTimeout(3_000) { reached.await() }
    } finally {
      unsubscribe()
    }
  }

  private class ActionCounter {
    val notifConfig = AtomicInteger()
    var onNotifConfig: () -> Unit = {}

    val enhancer: StoreEnhancer<AppState> = { creator ->
      { reducer, initial, enhancer ->
        val store = creator(reducer, initial, enhancer)
        val dispatch = store.dispatch
        store.dispatch = { action ->
          if (action is NotifConfigLoaded) {
            notifConfig.incrementAndGet()
            onNotifConfig()
          }
          dispatch(action)
        }
        store
      }
    }
  }

  private class Fixture(
    val content: ContentStore,
    val store: Store<AppState>,
    val coordinator: SessionCoordinator,
    val runtime: DayfoldRuntime,
    val actionCounter: ActionCounter,
  ) {
    fun installAuth(): AuthSessionContext = coordinator.install(Session("access", "refresh"))

    suspend fun close() {
      runtime.cancel()
      runtime.awaitClosed()
    }
  }
}
