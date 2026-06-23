package com.sloopworks.debugdrawer.log

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

enum class LogLevel { V, D, I, W, E }

data class LogEntry(
  val level: LogLevel,
  val tag: String,
  val message: String,
  val timestampMs: Long,
  val seq: Long,
)

/**
 * Thread-safe bounded ring of recent log lines (R4). Logs arrive on ARBITRARY
 * threads via [record]; reads ([snapshot]) happen on the main thread during
 * composition. All mutation is guarded by a single lock and capacity eviction
 * happens inside that lock, so there are no torn reads / lost writes / CME.
 *
 * Display wiring (a Compose-observable that only the main thread mutates) is built
 * on top of [snapshot] + [version] by the Logs panel (Plan C); the buffer itself
 * never touches snapshot state, keeping it safe to call from any thread.
 */
class LogBuffer(val capacity: Int = 1000) {
  private val lock = SynchronizedObject()
  private val entries = ArrayDeque<LogEntry>()
  private var seq = 0L
  private var ver = 0L

  /** Append a line from any thread. Oldest entries evict past [capacity]. */
  fun record(level: LogLevel, tag: String, message: String, timestampMs: Long) = synchronized(lock) {
    entries.addLast(LogEntry(level, tag, message, timestampMs, seq++))
    while (entries.size > capacity) entries.removeFirst()
    ver++
    Unit
  }

  /** An immutable snapshot for the UI (oldest → newest). */
  fun snapshot(): List<LogEntry> = synchronized(lock) { entries.toList() }

  /** Monotonic version; changes on every [record]/[clear] — cheap change signal. */
  fun version(): Long = synchronized(lock) { ver }

  fun clear() = synchronized(lock) { entries.clear(); ver++; Unit }
}
