package com.sloopworks.debugdrawer.log

import kotlin.test.Test
import kotlin.test.assertEquals

class LogFormatTest {

  private val entries = listOf(
    LogEntry(LogLevel.V, "a", "verbose", 0L, 0L),
    LogEntry(LogLevel.E, "b", "error", 0L, 1L),
    LogEntry(LogLevel.I, "c", "info", 0L, 2L),
    LogEntry(LogLevel.E, "d", "error2", 0L, 3L),
  )

  @Test
  fun null_filter_returns_all() {
    assertEquals(4, filterLogs(entries, null).size)
  }

  @Test
  fun level_filter_keeps_only_that_level() {
    val errs = filterLogs(entries, LogLevel.E)
    assertEquals(2, errs.size)
    assertEquals(listOf("error", "error2"), errs.map { it.message })
  }

  @Test
  fun letter_is_level_name() {
    assertEquals(listOf("V", "D", "I", "W", "E"), LogLevel.entries.map { it.letter() })
  }
}
