package com.sloopworks.dayfold.swip.config

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sloopworks.dayfold.swip.inspector.NoOpSecureWindow
import com.sloopworks.dayfold.swip.inspector.SecureWindow
import com.sloopworks.debugdrawer.DebugPlugin
import com.sloopworks.debugdrawer.DebugScope
import com.sloopworks.debugdrawer.theme.DrawerColors
import com.sloopworks.debugdrawer.theme.LocalDebugDrawerColors
import kotlinx.coroutines.delay
import works.sloop.swip.config.ConfigType
import works.sloop.swip.config.ResolutionReason
import works.sloop.swip.config.SwipConfigDebug

/** Editor field test tag — the free-text editors (STRING/DURATION/JSON) share it. */
const val EDITOR_FIELD_TAG: String = "config-editor-field"

/**
 * Config debug panel — the key list, why each key resolved (the eval trace), and a typed
 * override editor, over the [SwipConfigDebug] seam.
 *
 * The plugin is constructed with the seam directly, and the host registers it ONLY when
 * `swip.configDebug != null` (non-null solely in a debuggable dev/ci build) — so there is
 * no in-panel gate to get wrong. Overrides are re-validated and re-gated inside the seam.
 *
 * EXPOSURE SAFETY: rendering goes through [snapshotConfig] → `SwipConfigDebug.resolve`,
 * which is side-effect-free. The product getters (`config.boolean/string/variant`) record
 * a real experiment exposure on read and are NEVER called here — a debug panel that read
 * through them would forge assignments into experiment data.
 *
 * The detail dialog can reveal the targeting identity, so the host window is FLAG_SECURE'd
 * for its whole open lifetime (set before the row's tap opens it, cleared on dismiss),
 * matching the SWIP inspector's capture posture.
 */
class SwipConfigPlugin(
  private val debug: SwipConfigDebug,
  private val secure: SecureWindow = NoOpSecureWindow,
) : DebugPlugin {
  override val id: String = "swip-config"
  override val title: String = "Config"

  @Composable
  override fun Content(scope: DebugScope) {
    val colors = LocalDebugDrawerColors.current
    val snap by rememberConfigSnapshot(debug)
    var filter by remember { mutableStateOf(ConfigFilter.ALL) }
    var namespace by remember { mutableStateOf<String?>(null) }
    var detailKey by remember { mutableStateOf<String?>(null) }

    // A namespace can disappear (its keys were never re-read); don't strand the filter.
    val allNamespaces = remember(snap.rows) { namespaces(snap.rows) }
    if (namespace != null && namespace !in allNamespaces) namespace = null

    Column(Modifier.fillMaxSize()) {
      Header(snap, colors) { debug.clearAllOverrides() }
      FilterBar(filter, namespace, allNamespaces, colors, onFilter = { filter = it }, onNamespace = { namespace = it })

      val shown = remember(snap.rows, filter, namespace) { applyFilter(snap.rows, filter, namespace) }
      if (shown.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
          Text(
            if (snap.rows.isEmpty()) "No config keys read yet." else "No keys match this filter.",
            color = colors.muted,
            fontSize = 14.sp,
          )
        }
      } else {
        LazyColumn(Modifier.fillMaxSize()) {
          groupRows(shown).forEach { group ->
            item(key = "ns-${group.namespace}") { GroupHeader(group.namespace, colors) }
            items(group.rows, key = { it.key }) { row ->
              ConfigRowView(row, colors) { secure.set(); detailKey = row.key }
            }
          }
        }
      }
    }

    detailKey?.let { key ->
      val row = snap.rows.firstOrNull { it.key == key }
      val res = snap.resolutions[key]
      if (row == null || res == null) {
        detailKey = null
      } else {
        DetailDialog(
          row = row,
          res = res,
          variants = snap.knownVariants[key].orEmpty(),
          colors = colors,
          scope = scope,
          secure = secure,
          onSet = { debug.setOverride(key, it) },
          onClear = { debug.clearOverride(key) },
          onDismiss = { detailKey = null },
        )
      }
    }
  }
}

/**
 * Main-thread poll bridge (R4: only the main thread touches Compose state) — the seam
 * exposes a monotonic [SwipConfigDebug.version], not a Flow. Re-snapshot only when it
 * changes; `previous` carries variant labels forward across the re-snapshot.
 */
@Composable
private fun rememberConfigSnapshot(debug: SwipConfigDebug): State<ConfigUiSnapshot> {
  val state = remember(debug) { mutableStateOf(snapshotConfig(debug)) }
  LaunchedEffect(debug) {
    while (true) {
      delay(300)
      if (debug.version() != state.value.version) state.value = snapshotConfig(debug, state.value)
    }
  }
  return state
}

@Composable
private fun Header(snap: ConfigUiSnapshot, colors: DrawerColors, onClearAll: () -> Unit) {
  Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 12.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(
          "rev ${snap.revision.ifEmpty { "—" }} · ${snap.overrideCount} override${if (snap.overrideCount == 1) "" else "s"}",
          color = colors.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
        )
        // keys() is the read-tracked set: a key the app hasn't read yet cannot appear.
        Text("read-so-far · authored catalog pending", color = colors.muted, fontSize = 11.sp)
      }
      if (snap.overrideCount > 0) TextButton(onClick = onClearAll) { Text("Clear all") }
    }
  }
}

@Composable
private fun FilterBar(
  filter: ConfigFilter,
  namespace: String?,
  allNamespaces: List<String>,
  colors: DrawerColors,
  onFilter: (ConfigFilter) -> Unit,
  onNamespace: (String?) -> Unit,
) {
  Column {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      Chip("ALL", filter == ConfigFilter.ALL, colors.accent, colors) { onFilter(ConfigFilter.ALL) }
      Chip("OVERRIDDEN", filter == ConfigFilter.OVERRIDDEN, colors.accent, colors) { onFilter(ConfigFilter.OVERRIDDEN) }
    }
    if (allNamespaces.size > 1) {
      Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Chip("*", namespace == null, colors.accent, colors) { onNamespace(null) }
        allNamespaces.forEach { ns -> Chip(ns, namespace == ns, colors.accent, colors) { onNamespace(ns) } }
      }
    }
  }
}

@Composable
private fun Chip(label: String, active: Boolean, accent: Color, colors: DrawerColors, onClick: () -> Unit) {
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

@Composable
private fun GroupHeader(namespace: String, colors: DrawerColors) {
  Text(
    namespace,
    color = colors.muted,
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
    fontWeight = FontWeight.Bold,
    modifier = Modifier.fillMaxWidth().background(colors.surface2).padding(horizontal = 12.dp, vertical = 4.dp),
  )
}

@Composable
private fun ConfigRowView(row: ConfigRow, colors: DrawerColors, onClick: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().clickable(onClickLabel = "Open config key") { onClick() }.padding(horizontal = 12.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(row.shortKey, color = colors.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, maxLines = 1)
      Text(row.value, color = colors.muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
    }
    ReasonChip(row.reason, colors)
  }
}

/**
 * OVERRIDE is highlighted (it's the state you can act on); ERROR is distinguished by its
 * LABEL, not by color alone — the reason text is always spelled out.
 */
@Composable
private fun ReasonChip(reason: ResolutionReason, colors: DrawerColors) {
  val color = when (reason) {
    ResolutionReason.OVERRIDE -> colors.warn
    ResolutionReason.ERROR -> colors.err
    ResolutionReason.SPLIT -> colors.accent
    else -> colors.muted
  }
  Box(
    Modifier.clip(RoundedCornerShape(4.dp))
      .background(if (reason == ResolutionReason.OVERRIDE) colors.accentSoft else colors.surface2)
      .border(1.dp, if (reason == ResolutionReason.OVERRIDE) color else colors.border, RoundedCornerShape(4.dp))
      .padding(horizontal = 6.dp, vertical = 2.dp),
  ) {
    Text(reason.name, color = color, fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
  }
}

@Composable
private fun DetailDialog(
  row: ConfigRow,
  res: works.sloop.swip.config.ConfigResolution,
  variants: List<String>,
  colors: DrawerColors,
  scope: DebugScope,
  secure: SecureWindow,
  onSet: (kotlinx.serialization.json.JsonElement) -> Unit,
  onClear: () -> Unit,
  onDismiss: () -> Unit,
) {
  var revealed by remember(row.key) { mutableStateOf(false) }
  val lines = remember(row.key, res) { detailLines(row, res) }
  val hasSensitive = lines.any { it.sensitive }

  // The window is secured by the caller BEFORE this dialog opens (so the dialog's own
  // window inherits FLAG_SECURE at creation); cleared when it leaves composition.
  DisposableEffect(Unit) { onDispose { secure.clear() } }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(row.key, fontFamily = FontFamily.Monospace, fontSize = 14.sp) },
    text = {
      Column(Modifier.verticalScroll(rememberScrollState())) {
        lines.forEach { line ->
          Text(
            "${line.label}: ${renderLine(line, revealed)}",
            color = if (line.sensitive) colors.warn else colors.text,
            fontFamily = FontFamily.Monospace, fontSize = 12.sp,
          )
        }
        Text(
          "override",
          color = colors.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold,
          modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
        OverrideEditor(row, res, variants, colors, onSet)
      }
    },
    confirmButton = {
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (row.overridden) TextButton(onClick = onClear) { Text("Clear") }
        TextButton(onClick = { scope.copy(copyText(row, res, revealed)) }) { Text("Copy") }
      }
    },
    dismissButton = {
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (hasSensitive) TextButton(onClick = { revealed = !revealed }) { Text(if (revealed) "Mask" else "Reveal") }
        TextButton(onClick = onDismiss) { Text("Close") }
      }
    },
  )
}

/**
 * Typed editor. BOOLEAN and VARIANT are pickers (no invalid input to make); STRING,
 * DURATION and JSON are free text validated by [buildOverride] before it reaches the seam
 * — which re-validates anyway. A VARIANT key whose labels aren't known (cold start with
 * the override already applied → the trace no longer carries them) falls back to text.
 */
@Composable
private fun OverrideEditor(
  row: ConfigRow,
  res: works.sloop.swip.config.ConfigResolution,
  variants: List<String>,
  colors: DrawerColors,
  onSet: (kotlinx.serialization.json.JsonElement) -> Unit,
) {
  when {
    row.type == ConfigType.BOOLEAN ->
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(true, false).forEach { v ->
          val active = editorSeed(res, ConfigType.BOOLEAN) == v.toString()
          Chip(v.toString(), active, colors.accent, colors) {
            buildOverride(ConfigType.BOOLEAN, v.toString())?.let(onSet)
          }
        }
      }

    row.type == ConfigType.VARIANT && variants.isNotEmpty() ->
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        variants.forEach { label ->
          Chip(label, editorSeed(res, ConfigType.VARIANT) == label, colors.accent, colors) {
            buildOverride(ConfigType.VARIANT, label)?.let(onSet)
          }
        }
      }

    else -> {
      var text by remember(row.key) { mutableStateOf(editorSeed(res, row.type)) }
      val parsed = buildOverride(row.type, text)
      Column {
        OutlinedTextField(
          value = text,
          onValueChange = { text = it },
          singleLine = row.type != ConfigType.JSON,
          isError = parsed == null,
          label = { Text(if (row.type == ConfigType.DURATION) "ms or duration (2s)" else row.type.name.lowercase()) },
          modifier = Modifier.fillMaxWidth().testTag(EDITOR_FIELD_TAG),
        )
        TextButton(onClick = { parsed?.let(onSet) }, enabled = parsed != null) { Text("Set") }
      }
    }
  }
}
