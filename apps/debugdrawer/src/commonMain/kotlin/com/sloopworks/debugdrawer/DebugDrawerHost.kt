package com.sloopworks.debugdrawer

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sloopworks.debugdrawer.host.DebugBubble
import com.sloopworks.debugdrawer.host.DrawerBody
import com.sloopworks.debugdrawer.host.DrawerScaffold
import com.sloopworks.debugdrawer.host.HostHeader
import com.sloopworks.debugdrawer.host.PanelListRow
import com.sloopworks.debugdrawer.host.PanelNavState
import com.sloopworks.debugdrawer.host.drawerWidthFor
import com.sloopworks.debugdrawer.internal.DebugScopeImpl
import com.sloopworks.debugdrawer.internal.PluginRegistry
import com.sloopworks.debugdrawer.log.LogBuffer
import com.sloopworks.debugdrawer.theme.DebugSkins
import com.sloopworks.debugdrawer.theme.DrawerColorScheme
import com.sloopworks.debugdrawer.theme.LocalDebugDrawerColors

/**
 * Wraps the app's root: renders [content] plus the floating bubble and, when open,
 * the drawer. Debug-only — call from a `debugImplementation`-wired host. If
 * [DebugDrawer.install] hasn't run, this is a pure passthrough (same as the no-op).
 *
 * Overlay is in-composition (R6): the bubble floats over [content], not over
 * app-spawned separate windows.
 */
@Composable
fun DebugDrawerHost(content: @Composable () -> Unit) {
  val inst = DebugDrawer.current()
  if (inst == null) { content(); return }
  val config = inst.config

  val systemDark = isSystemInDarkTheme()
  val dark = when (config.theme.colorScheme) {
    DrawerColorScheme.DARK -> true
    DrawerColorScheme.LIGHT -> false
    DrawerColorScheme.SYSTEM -> systemDark
  }
  val colors = remember(config.theme, dark) { DebugSkins.colors(config.theme, dark) }
  val registry = remember(config) { PluginRegistry(config.plugins) }
  val logs = remember { LogBuffer() }
  val nav = remember { PanelNavState() }
  var open by remember { mutableStateOf(false) }
  val clipboard = LocalClipboardManager.current
  val scope = remember(config, logs) {
    DebugScopeImpl(
      store = inst.store,
      backends = config.backends,
      logs = logs,
      onCopy = { clipboard.setText(AnnotatedString(it)) },
      onRestart = null, // real per-platform restart = Plan B; default = inert warning (R9)
    )
  }

  CompositionLocalProvider(LocalDebugDrawerColors provides colors) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
      content()

      if (!open) {
        DebugBubble(
          colors = colors,
          badge = 0,
          initialCorner = config.theme.bubblePosition,
          edgeSnap = config.theme.bubbleEdgeSnap,
          onOpen = { open = true },
        )
      } else {
        val width = drawerWidthFor(maxWidth.value.toInt())
        DrawerScaffold(width = width, colors = colors, onDismiss = { open = false }) {
          DrawerBody(
            header = {
              HostHeader(
                colors = colors,
                brandName = config.theme.brandName,
                buildType = config.buildInfo.buildType,
                inDetail = nav.inDetail,
                detailTitle = nav.current?.let { id -> registry.find(id)?.title },
                onBack = { nav.back() },
                onClose = { open = false },
              )
            },
            colors = colors,
            body = {
              val plugin = nav.current?.let { registry.find(it) }
              when {
                plugin != null -> plugin.Content(scope)
                registry.plugins.isEmpty() -> EmptyPanels(colors.muted)
                else -> Column(Modifier.verticalScroll(rememberScrollState())) {
                  registry.plugins.forEach { p ->
                    PanelListRow(title = p.title, colors = colors, onClick = { nav.open(p.id) })
                  }
                }
              }
            },
          )
        }
      }
    }
  }
}

/** C14 — empty state shown when no panels are registered. */
@Composable
private fun EmptyPanels(mutedColor: androidx.compose.ui.graphics.Color) {
  Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
    Text("No debug panels registered yet.", color = mutedColor, fontSize = 14.sp)
  }
}
