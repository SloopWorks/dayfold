package com.sloopworks.debugdrawer.persistence

/**
 * Tiny durable key/value store for the drawer's own state (backend override, bubble
 * corner, color-scheme choice). Per-platform actual (R3): Android SharedPreferences,
 * desktop java.util.prefs.Preferences, iOS NSUserDefaults.
 *
 * Contract (R5): [put]/[remove] are fire-and-forget / non-blocking (no synchronous
 * disk flush on the calling — usually main — thread). [get] may do a small sync read,
 * intended for one-time startup seeding, not per-recomposition reads (the override is
 * cached in observable state at install, R2).
 */
interface DebugStore {
  fun get(key: String): String?
  fun put(key: String, value: String)
  fun remove(key: String)
}

object DebugKeys {
  const val BACKEND_OVERRIDE = "backend_override"
  const val BUBBLE_CORNER = "bubble_corner"
  const val COLOR_SCHEME = "color_scheme"
}

/** Builds the platform store. Android requires [initAndroidDebugStore] first (R3). */
internal expect fun createDebugStore(): DebugStore
