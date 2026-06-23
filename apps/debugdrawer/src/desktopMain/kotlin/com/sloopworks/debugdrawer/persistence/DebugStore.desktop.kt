package com.sloopworks.debugdrawer.persistence

import java.util.prefs.Preferences

/**
 * Desktop store backed by [Preferences]. Writes are buffered by the JVM and flushed
 * by the runtime — we deliberately do NOT call flush() per put (R5), avoiding sync
 * disk I/O on the UI thread.
 */
internal class PreferencesDebugStore(nodeName: String) : DebugStore {
  private val prefs: Preferences = Preferences.userRoot().node(nodeName)
  override fun get(key: String): String? = prefs.get(key, null)
  override fun put(key: String, value: String) { prefs.put(key, value) }
  override fun remove(key: String) { prefs.remove(key) }
}

internal actual fun createDebugStore(): DebugStore =
  PreferencesDebugStore("com/sloopworks/debugdrawer")
