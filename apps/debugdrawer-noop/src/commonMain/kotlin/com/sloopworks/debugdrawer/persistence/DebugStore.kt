package com.sloopworks.debugdrawer.persistence

// No-op mirror of the persistence surface — inert (release persists nothing).

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
