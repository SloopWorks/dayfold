package com.sloopworks.debugdrawer.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Initial color scheme; the user can still toggle at runtime. */
enum class DrawerColorScheme { DARK, LIGHT, SYSTEM }

/** Dock corner for the bubble (start/end = RTL-aware, resolved at layout — R8). */
enum class Corner { TOP_START, TOP_END, BOTTOM_START, BOTTOM_END }

/**
 * The token-level customization surface (spec §3). Layout, density, and shapes are
 * fixed and NOT here. A consumer supplies brand identity + accent; everything else
 * derives from the neutral skin. `@Immutable` so composables reading it stay
 * skippable (R11). The brand mark is a concrete sail at the foundation; the
 * pluggable `DrawerMark` abstraction is deferred to Plan E (R10b).
 */
@Immutable
data class DebugDrawerTheme(
  val brandName: String = "SloopWorks",
  val accentLight: Color = Color(0xFF2A53F0),
  val accentDark: Color = Color(0xFF86A1FF),
  val colorScheme: DrawerColorScheme = DrawerColorScheme.SYSTEM,
  val bubblePosition: Corner = Corner.BOTTOM_END,
  val bubbleEdgeSnap: Boolean = true,
)

/**
 * Resolved per-theme palette handed to the UI via [LocalDebugDrawerColors].
 * All fields are stable [Color] so reads are skippable (R11). Neutrals + accent
 * come from the active light/dark token set; status (ok/warn/err) is FIXED
 * cross-app (spec §3) — not derived from the consumer accent. Log levels are a
 * fixed SEMANTIC mapping: V=muted, D=accent, I=ok, W=warn, E=err.
 */
@Immutable
data class DrawerColors(
  val bg: Color,
  val surface: Color,
  val surface2: Color,
  val surface3: Color,
  val border: Color,
  val borderStrong: Color,
  val text: Color,
  val muted: Color,
  val faint: Color,
  val accent: Color,
  val onAccent: Color,
  val accentSoft: Color,
  val ok: Color,
  val warn: Color,
  val err: Color,
  val scrim: Color,
) {
  // Fixed semantic log-level mapping (V=muted, D=accent, I=ok, W=warn, E=err).
  val logV: Color get() = muted
  val logD: Color get() = accent
  val logI: Color get() = ok
  val logW: Color get() = warn
  val logE: Color get() = err
}

/** Point-of-use color access; provided once by the host, read where needed (R11). */
val LocalDebugDrawerColors = staticCompositionLocalOf<DrawerColors> {
  error("LocalDebugDrawerColors not provided — wrap content in the debug drawer host")
}
