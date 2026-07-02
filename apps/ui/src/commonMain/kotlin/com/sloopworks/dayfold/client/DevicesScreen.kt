package com.sloopworks.dayfold.client

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sloopworks.dayfold.client.ui.loading.ErrorRetry
import com.sloopworks.dayfold.client.ui.loading.ListSkeleton
import com.sloopworks.dayfold.client.ui.loading.RowBusy

// AUTH-S6 — Connected devices & apps (Dayfold, A8b `devices`). The caller's own
// sessions + CLI grants; revoke any except this one. onLoad pulls the list on
// entry. Revoke is immediate (server gates per-request).
@Composable
fun DevicesScreen(
  state: AppState,
  onLoad: () -> Unit = {},
  onRevoke: (String) -> Unit = {},
  onBack: () -> Unit = {},
  onConnectDevice: () -> Unit = {},
) {
  val cs = MaterialTheme.colorScheme
  LaunchedEffect(Unit) { onLoad() }

  Column(Modifier.fillMaxSize().background(cs.surface)) {
    Row(
      Modifier.fillMaxWidth().padding(start = 18.dp, end = 20.dp, top = 16.dp, bottom = 10.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Box(Modifier.size(38.dp).clip(RoundedCornerShape(50)).clickable(onClick = onBack).semantics { contentDescription = "Back" }, contentAlignment = Alignment.Center) {
        androidx.compose.material3.Icon(DayfoldIcons.ArrowBack, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(24.dp).clearAndSetSemantics {})
      }
      Text("Connected devices", style = MaterialTheme.typography.titleLarge, color = cs.onSurface)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 6.dp)) {
      Text(
        "Phones you sign in on and any CLI or script you've authorized. Revoke anything you don't recognize.",
        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
      )
      Spacer(Modifier.height(14.dp))
      // Connect a device/CLI — opens the enter-code → approve flow (S6-D).
      Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
          .background(cs.surfaceContainer).testTag("devices-connect").clickable(onClick = onConnectDevice).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp),
      ) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(cs.tertiaryContainer), contentAlignment = Alignment.Center) {
          Text("+", style = MaterialTheme.typography.titleLarge, color = cs.onTertiaryContainer)
        }
        Column(Modifier.weight(1f)) {
          Text("Connect a device", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
          Text("Enter the code shown by a CLI or another device", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        }
      }
      Spacer(Modifier.height(9.dp))
      when {
        state.devices.isEmpty() && state.deviceListBusy -> ListSkeleton(rows = 3, modifier = Modifier.padding(top = 4.dp))
        state.devices.isEmpty() && state.deviceListError != null -> ErrorRetry(state.deviceListError, onRetry = onLoad)
        else -> Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
          state.devices.forEach { d -> DeviceRow(d, busy = d.id == state.deviceOpId, anyBusy = state.deviceOpId != null, onRevoke) }
        }
      }
    }
  }
}

@Composable
private fun DeviceRow(d: DeviceCredential, busy: Boolean, anyBusy: Boolean, onRevoke: (String) -> Unit) {
  val cs = MaterialTheme.colorScheme
  Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainer).padding(14.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp),
  ) {
    // kind glyph (no icon font): app = filled disc, cli = monospace ">_"
    Box(
      Modifier.size(42.dp).clip(RoundedCornerShape(12.dp))
        .background(if (d.current) cs.secondaryContainer else cs.surfaceContainerHigh),
      contentAlignment = Alignment.Center,
    ) {
      Text(if (d.kind == "cli") ">_" else "▢", color = if (d.current) cs.onSecondaryContainer else cs.onSurfaceVariant, style = MaterialTheme.typography.titleMedium)
    }
    Column(Modifier.weight(1f)) {
      Text(d.label ?: if (d.kind == "cli") "CLI access" else "This account", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
      if (d.current) {
        Text("This device · active now", style = MaterialTheme.typography.bodyMedium, color = cs.secondary)
      } else {
        Text(lastUsedLabel(d), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
      }
    }
    if (!d.current) {
      if (busy) RowBusy() else Box(
        Modifier.clip(RoundedCornerShape(50)).background(cs.surfaceContainerHigh)
          .testTag("revoke-${d.id}").clickable(enabled = !anyBusy) { onRevoke(d.id) }.padding(horizontal = 13.dp, vertical = 7.dp),
      ) { Text("Revoke", style = MaterialTheme.typography.labelLarge, color = cs.error) }
    }
  }
}

private fun lastUsedLabel(d: DeviceCredential): String {
  val ip = d.lastUsedIp?.takeIf { it.isNotBlank() }
  val used = d.lastUsedAt?.substringBefore("T")   // M0: date only; relative time is a follow
  return listOfNotNull(used?.let { "Last used $it" }, ip).joinToString(" · ").ifEmpty { "Not used yet" }
}
