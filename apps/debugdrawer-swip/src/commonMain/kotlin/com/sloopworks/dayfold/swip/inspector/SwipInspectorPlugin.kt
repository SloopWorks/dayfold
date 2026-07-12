package com.sloopworks.dayfold.swip.inspector

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sloopworks.debugdrawer.DebugPlugin
import com.sloopworks.debugdrawer.DebugScope
import com.sloopworks.debugdrawer.theme.DrawerColors
import com.sloopworks.debugdrawer.theme.LocalDebugDrawerColors
import kotlinx.coroutines.flow.StateFlow
import works.sloop.swip.DebugRecord
import works.sloop.swip.ExperimentalSwipDebugApi
import works.sloop.swip.debug.DebugEntry

/** Host-provided window-security control: set while unmasked PII is on screen. */
interface SecureWindow { fun set(); fun clear() }
object NoOpSecureWindow : SecureWindow { override fun set() {}; override fun clear() {} }

/**
 * SWIP capture inspector — "LogsPanel over RingDebugSink.entries". Flat live timeline,
 * type/dropped filter, tap→detail with mask-by-default reveal. The host window is FLAG_SECURE'd
 * (via [secure]) for the detail dialog's whole open lifetime — set before the row's tap opens the
 * dialog (so the dialog's window inherits the flag at creation) and cleared on dismiss — so raw
 * PII can't land in a screenshot / dogfood bug bundle (the bug reporter captures via PixelCopy,
 * which honors it).
 */
@OptIn(ExperimentalSwipDebugApi::class)
class SwipInspectorPlugin(
  private val entries: StateFlow<List<DebugEntry>>,
  private val secure: SecureWindow = NoOpSecureWindow,
) : DebugPlugin {
  override val id: String = "swip"
  override val title: String = "SWIP"

  @Composable
  override fun Content(scope: DebugScope) {
    val colors = LocalDebugDrawerColors.current
    val list by entries.collectAsState()
    var filter by remember { mutableStateOf(SwipFilter.ALL) }
    var detail by remember { mutableStateOf<DebugEntry?>(null) }

    Column(Modifier.fillMaxSize()) {
      FilterBar(filter, colors) { filter = it }
      val shown = remember(list, filter) { swipFilter(list, filter).asReversed() } // newest first
      if (shown.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
          Text("No SWIP records.", color = colors.muted, fontSize = 14.sp)
        }
      } else {
        LazyColumn(Modifier.fillMaxSize()) {
          items(shown, key = { it.seq }) { e -> SwipRow(e, colors) { secure.set(); detail = e } }
        }
      }
    }

    detail?.let { e -> DetailDialog(e, colors, scope, secure) { detail = null } }
  }
}

@Composable
private fun FilterBar(selected: SwipFilter, colors: DrawerColors, onSelect: (SwipFilter) -> Unit) {
  Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    SwipFilter.entries.forEach { f ->
      Chip(f.name, active = selected == f, accent = colors.accent, colors = colors) { onSelect(f) }
    }
  }
}

@Composable
private fun Chip(label: String, active: Boolean, accent: androidx.compose.ui.graphics.Color, colors: DrawerColors, onClick: () -> Unit) {
  Box(
    Modifier.clip(RoundedCornerShape(6.dp))
      .background(if (active) colors.accentSoft else colors.surface2)
      .border(1.dp, if (active) accent else colors.border, RoundedCornerShape(6.dp))
      .clickable(onClickLabel = "Filter $label") { onClick() }
      .padding(horizontal = 10.dp, vertical = 5.dp),
  ) {
    Text(label, color = if (active) accent else colors.muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Medium)
  }
}

@OptIn(ExperimentalSwipDebugApi::class)
@Composable
private fun SwipRow(entry: DebugEntry, colors: DrawerColors, onClick: () -> Unit) {
  val cat = categoryOf(entry.rec)
  val badgeColor = when (cat) { SwipFilter.DROPPED -> colors.err; SwipFilter.EVENTS -> colors.accent; else -> colors.muted }
  Row(
    Modifier.fillMaxWidth().clickable(onClickLabel = "Open SWIP record") { onClick() }.padding(horizontal = 12.dp, vertical = 6.dp),
    verticalAlignment = Alignment.Top,
  ) {
    Box(Modifier.clip(RoundedCornerShape(4.dp)).background(colors.surface2).padding(horizontal = 5.dp, vertical = 1.dp)) {
      Text(cat.name.take(3), color = badgeColor, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
    Text("  ${rowLabel(entry.rec)}", color = colors.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
  }
}

@OptIn(ExperimentalSwipDebugApi::class)
@Composable
private fun DetailDialog(entry: DebugEntry, colors: DrawerColors, scope: DebugScope, secure: SecureWindow, onDismiss: () -> Unit) {
  var revealed by remember(entry.seq) { mutableStateOf(false) }
  val lines = remember(entry.seq) { detailLines(entry.rec) }
  val hasSensitive = remember(entry.seq) { lines.any { it.sensitive } }

  // Window is secured by the caller before this dialog opens (so the dialog's own window
  // inherits FLAG_SECURE at creation); clear it once the dialog leaves composition.
  DisposableEffect(Unit) { onDispose { secure.clear() } }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(rowLabel(entry.rec), fontFamily = FontFamily.Monospace, fontSize = 14.sp) },
    text = {
      Column(Modifier.verticalScroll(rememberScrollState())) {
        lines.forEach { line ->
          Text(
            "${line.label}: ${renderValue(line, revealed)}",
            color = if (line.sensitive) colors.warn else colors.text,
            fontFamily = FontFamily.Monospace, fontSize = 13.sp,
          )
        }
      }
    },
    confirmButton = {
      TextButton(onClick = { scope.copy(copyText(entry.rec, revealed)) }) { Text("Copy") }
    },
    dismissButton = {
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (hasSensitive) TextButton(onClick = { revealed = !revealed }) { Text(if (revealed) "Mask" else "Reveal") }
        TextButton(onClick = onDismiss) { Text("Close") }
      }
    },
  )
}
