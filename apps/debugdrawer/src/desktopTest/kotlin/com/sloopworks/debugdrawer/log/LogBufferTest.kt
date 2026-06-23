package com.sloopworks.debugdrawer.log

import kotlin.test.Test
import kotlin.test.assertEquals

class LogBufferTest {

  @Test
  fun concurrent_appends_lose_nothing_within_capacity() {
    val buf = LogBuffer(capacity = 1000)
    val threads = (0 until 8).map { t ->
      Thread { repeat(100) { i -> buf.record(LogLevel.I, "t$t", "m$i", 0L) } }
    }
    threads.forEach { it.start() }
    threads.forEach { it.join() }
    // 8 threads × 100 = 800 ≤ 1000 → no loss, no exception, no torn state.
    assertEquals(800, buf.snapshot().size)
  }

  @Test
  fun capacity_evicts_oldest_first() {
    val buf = LogBuffer(capacity = 10)
    repeat(25) { i -> buf.record(LogLevel.D, "t", "m$i", 0L) }
    val snap = buf.snapshot()
    assertEquals(10, snap.size)
    assertEquals("m15", snap.first().message) // oldest retained
    assertEquals("m24", snap.last().message)  // newest
  }

  @Test
  fun version_changes_on_record_and_clear() {
    val buf = LogBuffer()
    val v0 = buf.version()
    buf.record(LogLevel.E, "t", "boom", 0L)
    val v1 = buf.version()
    buf.clear()
    val v2 = buf.version()
    assertEquals(true, v1 > v0 && v2 > v1)
  }
}
