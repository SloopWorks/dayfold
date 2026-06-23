package com.sloopworks.debugdrawer.redux

import androidx.compose.runtime.Composable
import com.sloopworks.debugdrawer.DebugPlugin
import com.sloopworks.debugdrawer.DebugScope
import org.reduxkotlin.devtools.inapp.ReduxDevToolsPanel
import org.reduxkotlin.devtools.ui.DevToolsTab
import org.reduxkotlin.devtools.ui.DevToolsThemeMode

/**
 * Embeds reduxkotlin's DevTools inspector ([ReduxDevToolsPanel]) as one panel in the
 * SloopWorks debug drawer — unifying redux time-travel/state inspection with the
 * other debug panels under a single bubble. Reads the global `DevToolsHub`, so it
 * shows whatever stores the app registered via `devTools(...)`.
 *
 * Add it only in debug builds, e.g.:
 * ```
 * DebugDrawer.install(DebugDrawerConfig(
 *     buildInfo = …,
 *     plugins = listOf(ReduxDevToolsDebugPlugin()),
 * ))
 * ```
 *
 * @param instanceId redux session id to show; `null` shows all sessions with a picker.
 * @param startTab the inspector tab shown first.
 * @param theme the inspector theme mode (defaults to follow the system).
 */
class ReduxDevToolsDebugPlugin(
  private val instanceId: String? = null,
  private val startTab: DevToolsTab = DevToolsTab.ACTIONS,
  private val theme: DevToolsThemeMode = DevToolsThemeMode.SYSTEM,
) : DebugPlugin {
  override val id: String = "redux"
  override val title: String = "Redux"

  @Composable
  override fun Content(scope: DebugScope) {
    ReduxDevToolsPanel(instanceId = instanceId, startTab = startTab, theme = theme)
  }
}
