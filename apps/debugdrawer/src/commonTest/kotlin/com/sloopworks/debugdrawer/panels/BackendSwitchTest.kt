package com.sloopworks.debugdrawer.panels

import com.sloopworks.debugdrawer.Backend
import com.sloopworks.debugdrawer.BuildInfo
import com.sloopworks.debugdrawer.DebugDrawerConfig
import com.sloopworks.debugdrawer.internal.builtinPlugins
import kotlin.test.Test
import kotlin.test.assertEquals

class BackendSwitchTest {

  @Test
  fun backend_builtin_appears_only_when_backends_declared() {
    val withBackends = builtinPlugins(
      DebugDrawerConfig(BuildInfo("1", "1"), backends = listOf(Backend("prod", "Prod", "https://p")))
    )
    assertEquals(listOf("appinfo", "backend"), withBackends.map { it.id })

    val noBackends = builtinPlugins(DebugDrawerConfig(BuildInfo("1", "1")))
    assertEquals(listOf("appinfo"), noBackends.map { it.id })
  }
}
