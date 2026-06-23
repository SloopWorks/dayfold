package com.sloopworks.debugdrawer.log

// No-op mirror — release discards all logging.
object DebugLog {
  fun record(level: LogLevel, tag: String, message: String) {}
  fun v(tag: String, message: String) {}
  fun d(tag: String, message: String) {}
  fun i(tag: String, message: String) {}
  fun w(tag: String, message: String) {}
  fun e(tag: String, message: String) {}
}
