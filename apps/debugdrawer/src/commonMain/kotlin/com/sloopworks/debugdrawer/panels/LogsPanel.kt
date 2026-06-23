package com.sloopworks.debugdrawer.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sloopworks.debugdrawer.DebugPlugin
import com.sloopworks.debugdrawer.DebugScope
import com.sloopworks.debugdrawer.log.LogBuffer
import com.sloopworks.debugdrawer.log.LogEntry
import com.sloopworks.debugdrawer.log.LogLevel
import com.sloopworks.debugdrawer.log.filterLogs
import com.sloopworks.debugdrawer.log.letter
import com.sloopworks.debugdrawer.log.levelColor
import com.sloopworks.debugdrawer.theme.DrawerColors
import com.sloopworks.debugdrawer.theme.LocalDebugDrawerColors
import kotlinx.coroutines.delay

/**
 * Built-in Logs panel (C6/C11/C12/C14). Observes the thread-safe [LogBuffer] via a
 * main-thread poll bridge (R4: only the main thread touches Compose state); segmented
 * V/D/I/W/E filter; newest-first list; tap a row → detail with copy. "Share" copies
 * the entry text (a real system share sheet is a follow-up).
 */
class LogsPlugin : DebugPlugin {
  override val id: String = "logs"
  override val title: String = "Logs"

  @Composable
  override fun Content(scope: DebugScope) {
    val colors = LocalDebugDrawerColors.current
    val entries by rememberLogSnapshot(scope.logs)
    var filter by remember { mutableStateOf<LogLevel?>(null) }
    var detail by remember { mutableStateOf<LogEntry?>(null) }

    Column(Modifier.fillMaxSize()) {
      LevelFilter(filter, colors) { filter = it }
      val shown = remember(entries, filter) { filterLogs(entries, filter).asReversed() } // newest first
      if (shown.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
          Text("No log entries.", color = colors.muted, fontSize = 14.sp)
        }
      } else {
        LazyColumn(Modifier.fillMaxSize()) {
          items(shown, key = { it.seq }) { entry -> LogRow(entry, colors) { detail = entry } }
        }
      }
    }

    detail?.let { e ->
      AlertDialog(
        onDismissRequest = { detail = null },
        title = { Text("${e.level.letter()} · ${e.tag}") },
        text = { Text(e.message, fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
        confirmButton = {
          TextButton(onClick = { scope.copy("[${e.level.letter()}] ${e.tag}: ${e.message}"); detail = null }) { Text("Copy") }
        },
        dismissButton = { TextButton(onClick = { detail = null }) { Text("Close") } },
      )
    }
  }
}

/** Poll the thread-safe buffer's version on the main thread; re-snapshot when it changes. */
@Composable
private fun rememberLogSnapshot(logs: LogBuffer): State<List<LogEntry>> {
  val state = remember(logs) { mutableStateOf(logs.snapshot()) }
  LaunchedEffect(logs) {
    var lastVer = -1L
    while (true) {
      val v = logs.version()
      if (v != lastVer) { lastVer = v; state.value = logs.snapshot() }
      delay(300)
    }
  }
  return state
}

@Composable
private fun LevelFilter(selected: LogLevel?, colors: DrawerColors, onSelect: (LogLevel?) -> Unit) {
  Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    FilterChip("ALL", selected == null, colors.accent, colors) { onSelect(null) }
    LogLevel.entries.forEach { lvl ->
      FilterChip(lvl.letter(), selected == lvl, levelColor(lvl, colors), colors) { onSelect(lvl) }
    }
  }
}

@Composable
private fun FilterChip(label: String, active: Boolean, accent: androidx.compose.ui.graphics.Color, colors: DrawerColors, onClick: () -> Unit) {
  Box(
    Modifier
      .clip(RoundedCornerShape(6.dp))
      .background(if (active) colors.accentSoft else colors.surface2)
      .border(1.dp, if (active) accent else colors.border, RoundedCornerShape(6.dp))
      .clickable(onClickLabel = "Filter $label") { onClick() }
      .padding(horizontal = 10.dp, vertical = 5.dp),
  ) {
    Text(label, color = if (active) accent else colors.muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Medium)
  }
}

@Composable
private fun LogRow(entry: LogEntry, colors: DrawerColors, onClick: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().clickable(onClickLabel = "Open log entry") { onClick() }.padding(horizontal = 12.dp, vertical = 6.dp),
    verticalAlignment = Alignment.Top,
  ) {
    Box(
      Modifier.clip(RoundedCornerShape(4.dp)).background(colors.surface2).padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
      Text(entry.level.letter(), color = levelColor(entry.level, colors), fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
    Text(
      "  ${entry.tag}: ${entry.message}",
      color = colors.text,
      fontFamily = FontFamily.Monospace,
      fontSize = 12.sp,
      maxLines = 1,
    )
  }
}
