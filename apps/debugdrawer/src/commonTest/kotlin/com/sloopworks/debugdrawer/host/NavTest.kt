package com.sloopworks.debugdrawer.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NavTest {

  @Test
  fun starts_on_list_home() {
    val nav = PanelNavState()
    assertNull(nav.current)
    assertFalse(nav.inDetail)
  }

  @Test
  fun open_then_back_round_trips() {
    val nav = PanelNavState()
    nav.open("appinfo")
    assertEquals("appinfo", nav.current)
    assertTrue(nav.inDetail)
    nav.back()
    assertNull(nav.current)
    assertFalse(nav.inDetail)
  }

  @Test
  fun width_breakpoint_picks_container() {
    assertEquals(DrawerWidth.COMPACT, drawerWidthFor(420))
    assertEquals(DrawerWidth.COMPACT, drawerWidthFor(719))
    assertEquals(DrawerWidth.EXPANDED, drawerWidthFor(720))
    assertEquals(DrawerWidth.EXPANDED, drawerWidthFor(1280))
  }
}
