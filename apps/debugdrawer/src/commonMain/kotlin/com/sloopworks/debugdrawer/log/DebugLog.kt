package com.sloopworks.debugdrawer.log

import com.sloopworks.debugdrawer.DebugDrawer

/** Wall-clock millis for log timestamps (expect/actual — no kotlinx-datetime dep). */
internal expect fun nowMs(): Long

/**
 * One-line logging sink the app points its logger at (a Napier/Timber antilog, or
 * direct calls). Writes to the installed [LogBuffer] from ANY thread (thread-safe,
 * R4); a no-op before install or in release (the no-op artifact discards).
 */
object DebugLog {
  fun record(level: LogLevel, tag: String, message: String) {
    DebugDrawer.current()?.logs?.record(level, tag, message, nowMs())
  }
  fun v(tag: String, message: String) = record(LogLevel.V, tag, message)
  fun d(tag: String, message: String) = record(LogLevel.D, tag, message)
  fun i(tag: String, message: String) = record(LogLevel.I, tag, message)
  fun w(tag: String, message: String) = record(LogLevel.W, tag, message)
  fun e(tag: String, message: String) = record(LogLevel.E, tag, message)
}
