package com.sloopworks.dayfold.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
  private fun store() = createAppStore(debug = false)

  // Await the first surfacing emission satisfying [pred] (the engine writes asynchronously).
  private suspend fun surfacingWhen(cs: ContentStore, pred: (Map<String, SurfacingRecord>) -> Boolean) =
    withTimeout(3000) { cs.surfacingFlow().first(pred) }

  @Test fun `noteShown starts the anti-nag decay clock once`() = runBlocking {
    val cs = freshContentStore()
    val e = NowEngine(store(), cs, nowProvider = { now }, debounceMs = 20)
    e.noteShown(setOf("hub:h1"))
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
    surfacingWhen(cs) { it["hub:h1"]?.lastShownAtIso == now }
    // a later render of the SAME visible subject must leave last_shown untouched — else it never softens.
    clock = "2026-06-30T18:00:00Z"
    e.noteShown(setOf("hub:h1"))
    Thread.sleep(120)                                   // let any (incorrect) write land
    val recs = cs.surfacingFlow().first()
    assertEquals(now, recs["hub:h1"]?.lastShownAtIso)   // unchanged → the 6h gap accrues toward softening
    e.stop()
  }

  @Test fun `a fresh session preserves the clock (write-if-new SQL backstop)`() = runBlocking {
    val cs = freshContentStore()
    val e1 = NowEngine(store(), cs, nowProvider = { now }, debounceMs = 20)
    e1.noteShown(setOf("hub:h1"))
    surfacingWhen(cs) { it["hub:h1"]?.lastShownAtIso == now }
    e1.stop()
    // a NEW engine has a cold in-memory `started` set + an unbridged store, so it WILL attempt a
    // write — recordShownIfNew (ON CONFLICT DO NOTHING) must preserve the original timestamp.
    val e2 = NowEngine(store(), cs, nowProvider = { "2026-07-01T12:00:00Z" }, debounceMs = 20)
    e2.noteShown(setOf("hub:h1"))
    Thread.sleep(120)
    val recs = cs.surfacingFlow().first()
    assertEquals(now, recs["hub:h1"]?.lastShownAtIso)   // not reset across sessions
    e2.stop()
  }

  @Test fun `noteShown coalesces a burst into one debounced flush`() = runBlocking {
    val cs = freshContentStore()
    val e = NowEngine(store(), cs, nowProvider = { now }, debounceMs = 60)
    e.noteShown(setOf("hub:a"))
    e.noteShown(setOf("hub:b"))                         // within the window → both pending, one flush
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
    val recs = surfacingWhen(cs) { it["card:c1"]?.dismissedAtIso != null }
    val after = nowFeed(base.copy(surfacing = recs), now, null, zone)
    assertTrue((after.now + after.soon + after.later + after.overflow).none { it.item.id == "authored:c1" })
    e.stop()
  }
}
