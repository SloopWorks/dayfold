package com.sloopworks.debugdrawer.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class ThemeTest {

  @Test
  fun sloopworks_accent_resolves_per_theme() {
    assertEquals(Color(0xFF86A1FF), DebugSkins.colors(DebugSkins.sloopworks(), dark = true).accent)
    assertEquals(Color(0xFF2A53F0), DebugSkins.colors(DebugSkins.sloopworks(), dark = false).accent)
  }

  @Test
  fun status_colors_fixed_regardless_of_accent_override() {
    val custom = DebugSkins.sloopworks().copy(accentDark = Color(0xFFFF0000), accentLight = Color(0xFFFF0000))
    // err is fixed cross-app — a consumer accent override must not change it.
    assertEquals(
      DebugSkins.colors(DebugSkins.sloopworks(), dark = true).err,
      DebugSkins.colors(custom, dark = true).err,
    )
    assertEquals(Color(0xFFF2685E), DebugSkins.colors(custom, dark = true).err)
    assertEquals(Color(0xFFC5392B), DebugSkins.colors(custom, dark = false).err)
  }

  @Test
  fun log_levels_map_to_fixed_semantic_roles() {
    val c = DebugSkins.colors(DebugSkins.sloopworks(), dark = true)
    assertEquals(c.muted, c.logV)
    assertEquals(c.accent, c.logD)
    assertEquals(c.ok, c.logI)
    assertEquals(c.warn, c.logW)
    assertEquals(c.err, c.logE)
  }
}
