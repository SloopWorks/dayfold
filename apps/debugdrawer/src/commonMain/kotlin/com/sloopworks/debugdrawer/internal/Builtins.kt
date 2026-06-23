package com.sloopworks.debugdrawer.internal

import com.sloopworks.debugdrawer.DebugDrawerConfig
import com.sloopworks.debugdrawer.DebugPlugin
import com.sloopworks.debugdrawer.panels.AppInfoPlugin
import com.sloopworks.debugdrawer.panels.BackendSwitchPlugin

/**
 * Built-in panels prepended before the consumer's plugins when
 * [DebugDrawerConfig.includeBuiltins] is true. Backend-switch appears only when the
 * app declared backends. Logs joins here as Plan C lands.
 */
internal fun builtinPlugins(config: DebugDrawerConfig): List<DebugPlugin> =
  if (!config.includeBuiltins) emptyList()
  else buildList {
    add(AppInfoPlugin(config.buildInfo))
    if (config.backends.isNotEmpty()) add(BackendSwitchPlugin())
  }
