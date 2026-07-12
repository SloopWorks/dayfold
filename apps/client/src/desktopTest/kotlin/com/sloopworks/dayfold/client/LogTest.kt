package com.sloopworks.dayfold.client

import kotlin.test.*

class LogTest {
  @AfterTest fun reset() { Log.sink = null; Log.minLevel = Log.LogLevel.DEBUG }

  @Test fun `below minLevel builds no message and does not emit`() {
    Log.minLevel = Log.LogLevel.WARN
    var built = false
    val seen = mutableListOf<String>(); Log.sink = { _, t, m, _, _ -> seen += "$t/$m" }
    Log.d("x") { built = true; "hi" }
    assertFalse(built); assertTrue(seen.isEmpty())
  }

  @Test fun `bound sink receives level, tag, message`() {
    val seen = mutableListOf<Triple<Log.LogLevel, String, String>>()
    Log.sink = { l, t, m, _, _ -> seen += Triple(l, t, m) }
    Log.w("sync") { "boom" }
    assertEquals(listOf(Triple(Log.LogLevel.WARN, "sync", "boom")), seen)
  }

  @Test fun `no sink installed does not throw`() { Log.sink = null; Log.i("t") { "no sink" } }
}
