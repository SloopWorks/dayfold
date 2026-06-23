package com.sloopworks.debugdrawer.redux

import com.sloopworks.debugdrawer.DebugPlugin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReduxDevToolsDebugPluginTest {

  @Test
  fun is_a_debug_plugin_with_stable_id_and_title() {
    val plugin: DebugPlugin = ReduxDevToolsDebugPlugin()
    assertEquals("redux", plugin.id)
    assertEquals("Redux", plugin.title)
    assertTrue(plugin is ReduxDevToolsDebugPlugin)
  }
}
