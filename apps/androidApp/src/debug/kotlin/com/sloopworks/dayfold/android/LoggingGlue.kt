package com.sloopworks.dayfold.android

import com.sloopworks.dayfold.client.Log
import com.sloopworks.debugdrawer.log.DebugLog
import works.sloop.swip.LogWriter
import works.sloop.swip.Severity
import works.sloop.swip.logging.DefaultSloopLogging
import works.sloop.swip.logging.FixedPolicy
import works.sloop.swip.logging.platformConsoleWriter

/** Feeds the debug-drawer Logs panel from the SWIP logging pipeline (already scrubbed). */
private class DebugDrawerWriter : LogWriter {
  override fun write(severity: Severity, tag: String, message: String, context: Map<String, Any?>?, error: Throwable?) {
    DebugLog.record(when (severity) {
      Severity.DEBUG -> com.sloopworks.debugdrawer.log.LogLevel.D
      Severity.INFO -> com.sloopworks.debugdrawer.log.LogLevel.I
      Severity.WARN -> com.sloopworks.debugdrawer.log.LogLevel.W
      Severity.ERROR -> com.sloopworks.debugdrawer.log.LogLevel.E
    }, tag, message)
    error?.let { DebugLog.record(com.sloopworks.debugdrawer.log.LogLevel.E, tag, it.stackTraceToString()) }
  }
}

private fun sev(l: Log.LogLevel): Severity = when (l) {
  Log.LogLevel.DEBUG -> Severity.DEBUG; Log.LogLevel.INFO -> Severity.INFO
  Log.LogLevel.WARN -> Severity.WARN; Log.LogLevel.ERROR -> Severity.ERROR
}
private fun lvl(s: Severity): Log.LogLevel = when (s) {
  Severity.DEBUG -> Log.LogLevel.DEBUG; Severity.INFO -> Log.LogLevel.INFO
  Severity.WARN -> Log.LogLevel.WARN; Severity.ERROR -> Log.LogLevel.ERROR
}

/** Bind SloopLogging (console + drawer writers) behind Log.sink. Debug builds only. */
fun installLogging(debug: Boolean) {
  val logging = DefaultSloopLogging(
    listOf(platformConsoleWriter(), DebugDrawerWriter()),
    FixedPolicy(emptyMap(), if (debug) Severity.DEBUG else Severity.WARN),
  )
  Log.sink = { l, t, m, c, e -> logging.log(sev(l), t, m, c, e) }
  Log.minLevel = lvl(logging.minSeverity())
}
