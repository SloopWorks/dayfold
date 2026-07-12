package com.sloopworks.dayfold.android

import android.view.WindowManager
import androidx.activity.ComponentActivity
import com.sloopworks.dayfold.swip.inspector.SecureWindow
import works.sloop.swip.ExperimentalSwipDebugApi
import works.sloop.swip.debug.RingDebugSink

/**
 * Debug-only SWIP inspector host glue. Builds the RingDebugSink behind an explicit
 * install-gate and exposes a FLAG_SECURE-toggling SecureWindow. Lives in src/debug only —
 * release never references debugdrawer-swip (zero swip-debug bytes in the public APK).
 */
@OptIn(ExperimentalSwipDebugApi::class)
object SwipInspectorGlue {

  // Install-gate (allowlist). src/debug already implies a debug build; this is the explicit,
  // documented seam for a real channel signal.
  // TODO: gate on channel ∈ {dev,ci} once a real channel signal exists — never a != prod
  // blocklist (beta ships to real users).
  private fun gateOpen(): Boolean = BuildConfig.DEBUG

  /** The gated capture sink, created once and shared with Swip.init. Null when gate closed. */
  fun debugSink(): RingDebugSink? {
    if (!gateOpen()) return null
    SwipAnalyticsHolder.debugSink?.let { return it }
    return RingDebugSink(
      scope = SwipAnalyticsHolder.scope,
      nowMs = { System.currentTimeMillis() },
    ).also { SwipAnalyticsHolder.debugSink = it }
  }

  fun secureWindow(activity: ComponentActivity): SecureWindow = object : SecureWindow {
    override fun set() = activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    override fun clear() = activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
  }
}
