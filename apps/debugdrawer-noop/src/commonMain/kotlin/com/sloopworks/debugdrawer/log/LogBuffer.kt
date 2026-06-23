package com.sloopworks.debugdrawer.log

// No-op mirror of the real LogBuffer surface — inert (release records nothing).

enum class LogLevel { V, D, I, W, E }

data class LogEntry(
  val level: LogLevel,
  val tag: String,
  val message: String,
  val timestampMs: Long,
  val seq: Long,
)

class LogBuffer(val capacity: Int = 1000) {
  fun record(level: LogLevel, tag: String, message: String, timestampMs: Long) {}
  fun snapshot(): List<LogEntry> = emptyList()
  fun version(): Long = 0L
  fun clear() {}
}
