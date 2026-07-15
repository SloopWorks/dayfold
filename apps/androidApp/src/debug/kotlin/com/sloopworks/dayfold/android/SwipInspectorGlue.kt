package com.sloopworks.dayfold.android

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.sloopworks.dayfold.swip.inspector.SecureWindow
import java.lang.ref.WeakReference
import works.sloop.swip.ExperimentalSwipDebugApi
import works.sloop.swip.debug.RingDebugSink

/**
 * Debug-only SWIP inspector host glue. Builds the RingDebugSink behind an explicit
 * install-gate and exposes a FLAG_SECURE-toggling SecureWindow. Lives in src/debug only —
 * release never references debugdrawer-swip (zero swip-debug bytes in the public APK).
 */
@OptIn(ExperimentalSwipDebugApi::class)
object SwipInspectorGlue {

  /** A process-retained plugin may retain this delegate, but never its host. */
  internal class WeakHostSecureWindow<T : Any>(
    host: T,
    private val setSecure: (T) -> Unit,
    private val clearSecure: (T) -> Unit,
  ) : SecureWindow {
    private val host = WeakReference(host)

    override fun set() {
      host.get()?.let(setSecure)
    }

    override fun clear() {
      host.get()?.let(clearSecure)
    }

    fun release() {
      host.clear()
    }
  }

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

  fun secureWindow(activity: ComponentActivity): SecureWindow {
    val secure = WeakHostSecureWindow(
      host = activity,
      setSecure = { it.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) },
      clearSecure = { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) },
    )
    activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
      override fun onDestroy(owner: LifecycleOwner) {
        secure.release()
        owner.lifecycle.removeObserver(this)
      }
    })
    return secure
  }
}
