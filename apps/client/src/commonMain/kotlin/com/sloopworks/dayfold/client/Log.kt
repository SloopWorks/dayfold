package com.sloopworks.dayfold.client

import kotlin.concurrent.Volatile // multiplatform @Volatile (bare resolves to kotlin.jvm → fails on K/Native)

/**
 * Leveled logging front-door for :client — SWIP-free by design (ADR 0056). A host binds
 * [sink] to the SWIP SloopLogging runtime (see :swip-wiring); unbound → stdout fallback so
 * bare :client / desktopTest / pre-init still print. Lazy inline [message] → below-threshold
 * calls build no string. [context] is evaluated eagerly → use at INFO+ only, not hot paths.
 */
object Log {
  enum class LogLevel { DEBUG, INFO, WARN, ERROR }   // 4 levels, parity with SWIP Severity

  @Volatile var sink: ((level: LogLevel, tag: String, message: String, context: Map<String, Any?>?, error: Throwable?) -> Unit)? = null
  @Volatile var minLevel: LogLevel = LogLevel.DEBUG   // coarse global floor (host sets from the policy)

  inline fun log(level: LogLevel, tag: String, error: Throwable? = null, context: Map<String, Any?>? = null, message: () -> String) {
    if (level < minLevel) return
    val m = message()
    val s = sink
    if (s != null) s(level, tag, m, context, error) else println("[$level/$tag] $m")
  }
  inline fun d(tag: String, message: () -> String) = log(LogLevel.DEBUG, tag, message = message)
  inline fun i(tag: String, message: () -> String) = log(LogLevel.INFO, tag, message = message)
  inline fun w(tag: String, error: Throwable? = null, message: () -> String) = log(LogLevel.WARN, tag, error, message = message)
  inline fun e(tag: String, error: Throwable? = null, message: () -> String) = log(LogLevel.ERROR, tag, error, message = message)
}
