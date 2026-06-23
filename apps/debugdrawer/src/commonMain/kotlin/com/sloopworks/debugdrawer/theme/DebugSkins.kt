package com.sloopworks.debugdrawer.theme

import androidx.compose.ui.graphics.Color

/**
 * Skins resolve a [DebugDrawerTheme] (+ light/dark) into [DrawerColors]. Token hex
 * is verbatim from `designs/sloopworks/BRAND.md` / `designs/debug-drawer/spec.md` §3.
 * Neutrals are the SloopWorks scale; `accent`/`accentSoft` come from the theme;
 * status colors are FIXED constants (cross-app legibility, spec §3).
 */
object DebugSkins {

  /** The default SloopWorks skin. */
  fun sloopworks(): DebugDrawerTheme = DebugDrawerTheme()

  fun colors(theme: DebugDrawerTheme, dark: Boolean): DrawerColors =
    if (dark) dark(theme) else light(theme)

  // ── Fixed status colors (not consumer-overridable) ──────────────────────────
  private val OK_D = Color(0xFF46C97E); private val WARN_D = Color(0xFFE0A33A); private val ERR_D = Color(0xFFF2685E)
  private val OK_L = Color(0xFF1A9E55); private val WARN_L = Color(0xFFB5740C); private val ERR_L = Color(0xFFC5392B)

  private fun dark(theme: DebugDrawerTheme) = DrawerColors(
    bg = Color(0xFF0A0A0C),
    surface = Color(0xFF101013),
    surface2 = Color(0xFF17171B),
    surface3 = Color(0xFF1E1E23),
    border = Color(0xFF232329),
    borderStrong = Color(0xFF33333B),
    text = Color(0xFFF3F3F5),
    muted = Color(0xFF9B9CA6),
    faint = Color(0xFF67686F),
    accent = theme.accentDark,
    onAccent = Color(0xFF0A0A0C),
    accentSoft = Color(0xFF15182C),
    ok = OK_D, warn = WARN_D, err = ERR_D,
    scrim = Color(0x99000000), // rgba(0,0,0,.6)
  )

  private fun light(theme: DebugDrawerTheme) = DrawerColors(
    bg = Color(0xFFFBFBFC),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFF4F4F6),
    surface3 = Color(0xFFECECEF),
    border = Color(0xFFE8E8EB),
    borderStrong = Color(0xFFD6D6DB),
    text = Color(0xFF121317),
    muted = Color(0xFF56575E),
    faint = Color(0xFF8A8B93),
    accent = theme.accentLight,
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFEDF0FE),
    ok = OK_L, warn = WARN_L, err = ERR_L,
    scrim = Color(0x52121317), // rgba(18,19,23,.32)
  )
}
