package com.sloopworks.debugdrawer.panels

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sloopworks.debugdrawer.Backend
import com.sloopworks.debugdrawer.DebugDrawer
import com.sloopworks.debugdrawer.DebugPlugin
import com.sloopworks.debugdrawer.DebugScope
import com.sloopworks.debugdrawer.attemptRestart
import com.sloopworks.debugdrawer.host.MonoChip
import com.sloopworks.debugdrawer.log.LogLevel
import com.sloopworks.debugdrawer.theme.LocalDebugDrawerColors

/**
 * Built-in Backend/Env panel (C7/C8/C9). Lists named backends with the active one
 * marked; selecting a different one stages it and reveals the sticky "Apply &
 * Restart" bar; confirming persists the override (DebugDrawer.setOverride) and
 * restarts (Android) or logs "relaunch to apply" (desktop/iOS). In-module, so it
 * calls the internal seam directly — no DebugScope surface change.
 */
class BackendSwitchPlugin : DebugPlugin {
  override val id: String = "backend"
  override val title: String = "Backend"

  @Composable
  override fun Content(scope: DebugScope) {
    val colors = LocalDebugDrawerColors.current
    val active = scope.activeBackendId()
    var staged by remember(active) { mutableStateOf(active) }
    var confirming by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
      Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        scope.backends.forEach { b ->
          BackendRow(b, selected = staged == b.id, active = b.id == active, colors.text, colors.faint, colors) {
            staged = b.id
          }
        }
        Spacer(Modifier.height(76.dp)) // clearance for the sticky bar
      }

      if (staged != active) {
        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), color = colors.surface2) {
          Button(onClick = { confirming = true }, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Apply & Restart")
          }
        }
      }
    }

    if (confirming) {
      val target = scope.backends.firstOrNull { it.id == staged }?.label ?: staged
      AlertDialog(
        onDismissRequest = { confirming = false },
        title = { Text("Switch backend?") },
        text = { Text("Switch to $target? The app will restart and your session and cached data will be cleared.") },
        confirmButton = {
          TextButton(onClick = {
            confirming = false
            DebugDrawer.setOverride(staged)
            if (!attemptRestart()) {
              scope.logs.record(LogLevel.W, "DebugDrawer", "Backend set to '$staged' — relaunch to apply.", 0L)
            }
          }) { Text("Apply & Restart") }
        },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
      )
    }
  }
}

@Composable
private fun BackendRow(
  backend: Backend,
  selected: Boolean,
  active: Boolean,
  textColor: androidx.compose.ui.graphics.Color,
  subColor: androidx.compose.ui.graphics.Color,
  colors: com.sloopworks.debugdrawer.theme.DrawerColors,
  onSelect: () -> Unit,
) {
  Row(
    Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected = selected, onClick = onSelect)
      .padding(horizontal = 12.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioButton(selected = selected, onClick = onSelect)
    Column(Modifier.padding(start = 8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(backend.label, color = textColor, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        if (active) MonoChip("ACTIVE", colors, Modifier.padding(start = 8.dp))
      }
      Text(backend.url, color = subColor, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
  }
}
