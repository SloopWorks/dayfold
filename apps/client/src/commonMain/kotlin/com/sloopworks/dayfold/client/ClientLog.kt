package com.sloopworks.dayfold.client

import kotlin.concurrent.Volatile // multiplatform @Volatile (bare resolves to kotlin.jvm → fails on K/Native)

/**
 * Tiny logging seam for the client. The redux action log + engine logs go through
 * here so they reach BOTH stdout/logcat (`println`, the existing cheap text loop)
 * AND an optional host [sink] — the Android entrypoint points the sink at the debug
 * drawer's DebugLog so the drawer's Logs panel actually shows them (it was empty
 * because nothing fed its LogBuffer). The client stays debug-drawer-independent.
 */
object ClientLog {
  /** Host-installed sink (e.g. the debug drawer). Null = stdout only. */
  @Volatile
  var sink: ((tag: String, message: String) -> Unit)? = null

  fun log(tag: String, message: String) {
    println("[$tag] $message")
    sink?.invoke(tag, message)
  }
}
