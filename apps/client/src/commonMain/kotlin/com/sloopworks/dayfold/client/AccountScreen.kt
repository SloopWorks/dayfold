package com.sloopworks.dayfold.client

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// AUTH-S5 Slice A — the account/profile surface (Dayfold, from Settings-Phone
// `profile`). Hosts sign-out (the slice-1 follow). Reached from the Feed top bar
// (OpenAccount) and dismissed back to the gate (CloseAccount). No icon-font dep —
// monogram + glyph drawn, matching AuthScreens. Display-name editing + method
// linking are Slice C (need an API/Firebase); M0 shows "You" + the active role.
@Composable
fun AccountScreen(
  state: AppState,
  onSignOut: () -> Unit = {},
  onClose: () -> Unit = {},
  onOpenMembers: () -> Unit = {},
  onOpenDevices: () -> Unit = {},
) {
  val cs = MaterialTheme.colorScheme
  val active = state.families.firstOrNull { it.familyId == state.activeFamilyId }
  val role = (active?.role ?: "adult").replaceFirstChar { it.uppercase() }
  // Transient UI — a local confirm flag, not app state (ADR 0013 is nav, not
  // every ephemeral dialog). Signing out is reversible but deliberate; a one-tap
  // guard avoids an accidental session clear.
  var confirmSignOut by remember { mutableStateOf(false) }

  if (confirmSignOut) {
    AlertDialog(
      onDismissRequest = { confirmSignOut = false },
      title = { Text("Sign out?", style = MaterialTheme.typography.titleLarge) },
      text = { Text("You'll need to sign in again to see your family.", style = MaterialTheme.typography.bodyMedium) },
      confirmButton = {
        TextButton(onClick = { confirmSignOut = false; onSignOut() }, modifier = Modifier.testTag("confirm-signout")) {
          Text("Sign out", color = cs.error)
        }
      },
      dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
      containerColor = cs.surfaceContainerHigh,
    )
  }

  Column(Modifier.fillMaxSize().background(cs.surface)) {
    // top bar — back + title (chevron glyph, no icon font)
    Row(
      Modifier.fillMaxWidth().padding(start = 18.dp, end = 20.dp, top = 16.dp, bottom = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Box(
        Modifier.size(38.dp).clip(RoundedCornerShape(50)).clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
      ) { Text("‹", style = MaterialTheme.typography.headlineSmall, color = cs.onSurface) }
      Text("Account", style = MaterialTheme.typography.titleLarge, color = cs.onSurface)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 6.dp)) {
      // profile card
      Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.surfaceContainer).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        Box(
          Modifier.size(48.dp).clip(RoundedCornerShape(50)).background(cs.primaryContainer),
          contentAlignment = Alignment.Center,
        ) { Text("Y", style = MaterialTheme.typography.titleMedium, color = cs.onPrimaryContainer) }
        Column(Modifier.weight(1f)) {
          Text("You", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
          Text(
            if (active != null) "$role · ${active.name}" else role,
            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
          )
        }
      }

      Spacer(Modifier.height(22.dp))
      SectionLabel("FAMILY")
      Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainer)
          .clickable(onClick = onOpenMembers).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Column(Modifier.weight(1f)) {
          Text("Members & approvals", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
          Text(active?.name ?: "Your family", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        }
        if (state.pendingApprovals.isNotEmpty()) {
          Box(Modifier.clip(RoundedCornerShape(50)).background(cs.primary).padding(horizontal = 9.dp, vertical = 3.dp)) {
            Text("${state.pendingApprovals.size}", style = MaterialTheme.typography.labelLarge, color = cs.onPrimary)
          }
        }
        Text("›", style = MaterialTheme.typography.titleLarge, color = cs.onSurfaceVariant)
      }

      Spacer(Modifier.height(11.dp))
      Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainer)
          .clickable(onClick = onOpenDevices).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text("Connected devices", style = MaterialTheme.typography.titleMedium, color = cs.onSurface, modifier = Modifier.weight(1f))
        Text("›", style = MaterialTheme.typography.titleLarge, color = cs.onSurfaceVariant)
      }

      Spacer(Modifier.height(22.dp))
      SectionLabel("SIGN-IN METHODS")
      Column(Modifier.clip(RoundedCornerShape(18.dp)).background(cs.surfaceContainer)) {
        MethodRow("Google", "Linked", trailingTint = cs.secondary, trailing = "✓")
        Divider()
        MethodRow("Apple", "Add a backup way in", trailingTint = cs.primary, trailing = "Link")
        Divider()
        MethodRow("Phone", "Available later", trailingTint = cs.onSurfaceVariant, trailing = null, dim = true)
      }

      Spacer(Modifier.height(26.dp))
      // sign out — confirm first (guards an accidental session clear)
      Box(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp))
          .border(1.5.dp, cs.outline, RoundedCornerShape(16.dp)).clickable { confirmSignOut = true },
        contentAlignment = Alignment.Center,
      ) { Text("Sign out", style = MaterialTheme.typography.labelLarge, color = cs.onSurface) }

      Spacer(Modifier.height(12.dp))
      // delete (designed: deleteconfirm/transferowner — wired in a later slice)
      Box(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainer),
        contentAlignment = Alignment.Center,
      ) { Text("Delete account", style = MaterialTheme.typography.labelLarge, color = cs.error) }
    }
  }
}

@Composable
private fun SectionLabel(text: String) {
  Text(
    text, style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(start = 4.dp, bottom = 9.dp),
  )
}

@Composable
private fun MethodRow(name: String, sub: String, trailingTint: Color, trailing: String?, dim: Boolean = false) {
  val cs = MaterialTheme.colorScheme
  Row(
    Modifier.fillMaxWidth().padding(14.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(13.dp),
  ) {
    Column(Modifier.weight(1f)) {
      Text(name, style = MaterialTheme.typography.titleMedium, color = if (dim) cs.onSurfaceVariant else cs.onSurface)
      Text(sub, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
    }
    if (trailing != null) {
      Text(trailing, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = trailingTint)
    }
  }
}

@Composable
private fun Divider() {
  Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}
