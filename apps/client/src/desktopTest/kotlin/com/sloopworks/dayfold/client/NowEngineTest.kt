package com.sloopworks.dayfold.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR 0043 §2b — the render-driven surfacing EFFECT (the Phase-A carryover). NowEngine is the only
 * writer of surfacing state: the render reports visible subjects → NowEngine writes the DB →
 * surfacingFlow re-emits. These tests pin the two behaviors the dormant anti-nag logic needs:
 * the decay clock STARTS once (and never resets on re-render), and a dismiss omits the subject.
 */
class NowEngineTest {
  private val zone = TimeZone.UTC
  private val now = "2026-06-30T12:00:00Z"

  private fun freshContentStore() = ContentStore.create(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
  private fun store() = createTestAppStore(debug = false)

  // Await the first surfacing emission satisfying [pred] (the engine writes asynchronously).
  private suspend fun surfacingWhen(cs: ContentStore, pred: (Map<String, SurfacingRecord>) -> Boolean) =
    withTimeout(3000) { cs.surfacingFlow().first(pred) }

  @Test fun `noteShown starts the anti-nag decay clock once`() = runBlocking {
    val cs = freshContentStore()
    val e = NowEngine(store(), cs, nowProvider = { now }, debounceMs = 20)
    e.noteShown(setOf("hub:h1"))
    e.flushPending()
    val recs = surfacingWhen(cs) { it["hub:h1"]?.lastShownAtIso != null }
    assertEquals(now, recs["hub:h1"]?.lastShownAtIso)   // clock started
    assertNull(recs["hub:h1"]?.dismissedAtIso)
    e.stop()
  }

  @Test fun `re-rendering the same subject does NOT reset the clock (decay accrues)`() = runBlocking {
    val cs = freshContentStore()
    var clock = now
    val e = NowEngine(store(), cs, nowProvider = { clock }, debounceMs = 20)
    e.noteShown(setOf("hub:h1"))
    e.flushPending()
    surfacingWhen(cs) { it["hub:h1"]?.lastShownAtIso == now }
    // a later render of the SAME visible subject must leave last_shown untouched — else it never softens.
    clock = "2026-06-30T18:00:00Z"
    e.noteShown(setOf("hub:h1"))
    e.flushPending()
    val recs = cs.surfacingFlow().first()
    assertEquals(now, recs["hub:h1"]?.lastShownAtIso)   // unchanged → the 6h gap accrues toward softening
    e.stop()
  }

  @Test fun `a fresh session preserves the clock (write-if-new SQL backstop)`() = runBlocking {
    val cs = freshContentStore()
    val e1 = NowEngine(store(), cs, nowProvider = { now }, debounceMs = 20)
    e1.noteShown(setOf("hub:h1"))
    e1.flushPending()
    surfacingWhen(cs) { it["hub:h1"]?.lastShownAtIso == now }
    e1.stop()
    // a NEW engine has a cold in-memory `started` set + an unbridged store, so it WILL attempt a
    // write — recordShownIfNew (ON CONFLICT DO NOTHING) must preserve the original timestamp.
    val e2 = NowEngine(store(), cs, nowProvider = { "2026-07-01T12:00:00Z" }, debounceMs = 20)
    e2.noteShown(setOf("hub:h1"))
    e2.flushPending()
    val recs = cs.surfacingFlow().first()
    assertEquals(now, recs["hub:h1"]?.lastShownAtIso)   // not reset across sessions
    e2.stop()
  }

  @Test fun `noteShown coalesces a burst into one debounced flush`() = runBlocking {
    val cs = freshContentStore()
    val e = NowEngine(store(), cs, nowProvider = { now }, debounceMs = 60)
    e.noteShown(setOf("hub:a"))
    e.noteShown(setOf("hub:b"))                         // within the window → both pending, one flush
    e.flushPending()
    val recs = surfacingWhen(cs) { it.keys.containsAll(setOf("hub:a", "hub:b")) }
    assertEquals(now, recs["hub:a"]?.lastShownAtIso)
    assertEquals(now, recs["hub:b"]?.lastShownAtIso)
    e.stop()
  }

  @Test fun `dismiss omits the subject from the ranked feed`() = runBlocking {
    val cs = freshContentStore()
    val e = NowEngine(store(), cs, nowProvider = { now }, debounceMs = 20)
    val card = Card(id = "c1", title = "Bake sale", provenance = Provenance("claude"))
    val base = AppState(cards = listOf(card))
    // before: the authored card surfaces (a target-less card keys on card:<id>).
    val before = nowFeed(base, now, null, zone)
    assertTrue((before.now + before.soon + before.later + before.overflow).any { it.item.id == "authored:c1" })
    // dismiss → recordDismissed → rank() omits it on the next recompute.
    e.dismiss("card:c1")
    e.flushPending()
    val recs = surfacingWhen(cs) { it["card:c1"]?.dismissedAtIso != null }
    val after = nowFeed(base.copy(surfacing = recs), now, null, zone)
    assertTrue((after.now + after.soon + after.later + after.overflow).none { it.item.id == "authored:c1" })
    e.stop()
  }

  @Test fun `shown and dismiss writes run on the injected DB dispatcher in command order`() {
    val dispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "now-db") }
      .asCoroutineDispatcher()
    val blockerStarted = CountDownLatch(1)
    val releaseBlocker = CountDownLatch(1)

    try {
      runBlocking {
        val cs = freshContentStore()
        val e = NowEngine(
          store = store(),
          contentStore = cs,
          nowProvider = { now },
          debounceMs = 10_000,
          databaseDispatcher = dispatcher,
        )
        dispatcher.dispatch(coroutineContext) {
          blockerStarted.countDown()
          check(releaseBlocker.await(5, TimeUnit.SECONDS))
        }
        assertTrue(blockerStarted.await(5, TimeUnit.SECONDS))

        e.noteShown(setOf("hub:h1"))
        e.dismiss("card:c1")
        val flushed = launch { e.flushPending() }
        assertTrue(flushed.isActive, "ordered DB commands must wait behind the held dispatcher")

        releaseBlocker.countDown()
        flushed.join()
        assertEquals(now, cs.surfacing()["hub:h1"]?.lastShownAtIso)
        assertEquals(now, cs.surfacing()["card:c1"]?.dismissedAtIso)
        e.stop()
      }
    } finally {
      releaseBlocker.countDown()
      dispatcher.close()
    }
  }

  @Test fun `queued shown work is fenced by family generation and A B A can record again`() =
    runBlocking {
      val cs = freshContentStore()
      val session = Session("access", "refresh", "user")
      val appStore = createTestAppStore(
        initial = AppState(
          session = session,
          families = listOf(FamilyMembership("family-a", "A", "owner", "active")),
          activeFamilyId = "family-a",
        ),
        debug = false,
      )
      val coordinator = SessionCoordinator(
        refreshScope = this,
        refreshSession = { error("refresh not expected") },
        commitRotation = {},
      )
      val auth = coordinator.install(session)
      coordinator.selectFamily(auth, "family-a")
      var clock = now
      val engine = NowEngine(
        store = appStore,
        contentStore = cs,
        nowProvider = { clock },
        debounceMs = 10_000,
        sessionCoordinator = coordinator,
      )

      engine.noteShown(setOf("hub:shared")) // captured in the first A generation
      coordinator.selectFamily(auth, "family-b")
      appStore.dispatch(MembershipsLoaded(listOf(
        FamilyMembership("family-b", "B", "owner", "active"),
      )))
      cs.wipe()
      engine.flushPending()
      assertNull(cs.surfacing()["hub:shared"], "old-family batch must not land after wipe")

      engine.noteShown(setOf("hub:shared"))
      engine.flushPending()
      assertEquals(now, cs.surfacing()["hub:shared"]?.lastShownAtIso)

      cs.wipe()
      clock = "2026-07-01T12:00:00Z"
      coordinator.selectFamily(auth, "family-a") // new A revision, not the original generation
      appStore.dispatch(MembershipsLoaded(listOf(
        FamilyMembership("family-a", "A", "owner", "active"),
      )))
      engine.noteShown(setOf("hub:shared"))
      engine.flushPending()
      assertEquals(clock, cs.surfacing()["hub:shared"]?.lastShownAtIso)
      engine.stop()
    }
}
