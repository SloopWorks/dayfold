package com.sloopworks.debugdrawer.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// No-op mirror of the theme surface — same shapes so consumer theme overrides compile.

enum class DrawerColorScheme { DARK, LIGHT, SYSTEM }
enum class Corner { TOP_START, TOP_END, BOTTOM_START, BOTTOM_END }

@Immutable
data class DebugDrawerTheme(
  val brandName: String = "SloopWorks",
  val accentLight: Color = Color(0xFF2A53F0),
  val accentDark: Color = Color(0xFF86A1FF),
  val colorScheme: DrawerColorScheme = DrawerColorScheme.SYSTEM,
  val bubblePosition: Corner = Corner.BOTTOM_END,
  val bubbleEdgeSnap: Boolean = true,
)

@Immutable
data class DrawerColors(
  val bg: Color, val surface: Color, val surface2: Color, val surface3: Color,
  val border: Color, val borderStrong: Color,
  val text: Color, val muted: Color, val faint: Color,
  val accent: Color, val onAccent: Color, val accentSoft: Color,
  val ok: Color, val warn: Color, val err: Color, val scrim: Color,
) {
  val logV: Color get() = muted
  val logD: Color get() = accent
  val logI: Color get() = ok
  val logW: Color get() = warn
  val logE: Color get() = err
}

val LocalDebugDrawerColors = staticCompositionLocalOf<DrawerColors> {
  error("LocalDebugDrawerColors not provided")
}

val LocalReducedMotion = staticCompositionLocalOf { false }

object DebugSkins {
  fun sloopworks(): DebugDrawerTheme = DebugDrawerTheme()
  fun colors(theme: DebugDrawerTheme, dark: Boolean): DrawerColors {
    val u = Color.Unspecified
    return DrawerColors(u, u, u, u, u, u, u, u, u, theme.accentLight, u, u, u, u, u, u)
  }
}
