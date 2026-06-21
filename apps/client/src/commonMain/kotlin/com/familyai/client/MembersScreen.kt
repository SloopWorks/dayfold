package com.familyai.client

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// AUTH-S6 — the family members/approvals surface (Dayfold, A8b `members`). The
// owner-approval queue (approve/decline closes invitee-join) + the active roster
// (GET /members) with remove on non-owner members. onLoad/onLoadMembers run once
// on entry.
@Composable
fun MembersScreen(
  state: AppState,
  onApprove: (String) -> Unit = {},
  onDecline: (String) -> Unit = {},
  onLoad: () -> Unit = {},
  onLoadMembers: () -> Unit = {},
  onRemoveMember: (String) -> Unit = {},
  onBack: () -> Unit = {},
) {
  val cs = MaterialTheme.colorScheme
  LaunchedEffect(Unit) { onLoad(); onLoadMembers() }
  val me = state.families.firstOrNull { it.familyId == state.activeFamilyId }

  Column(Modifier.fillMaxSize().background(cs.surface)) {
    Row(
      Modifier.fillMaxWidth().padding(start = 18.dp, end = 20.dp, top = 16.dp, bottom = 10.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Box(Modifier.size(38.dp).clip(RoundedCornerShape(50)).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
        Text("‹", style = MaterialTheme.typography.headlineSmall, color = cs.onSurface)
      }
      Column {
        Text("Family", style = MaterialTheme.typography.titleLarge, color = cs.onSurface)
        me?.let { Text(it.name, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant) }
      }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 6.dp)) {
      if (state.pendingApprovals.isNotEmpty()) {
        Label("PENDING APPROVAL · ${state.pendingApprovals.size}", cs.primary)
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
          state.pendingApprovals.forEach { p -> PendingRow(p, onApprove, onDecline) }
        }
        Spacer(Modifier.height(22.dp))
      }

      Label("MEMBERS", cs.onSurfaceVariant)
      if (state.members.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
          state.members.forEach { m -> MemberRow(m, isOwner = m.role == "owner", onRemove = onRemoveMember) }
        }
      } else {
        // fallback before the roster loads — the caller's own row.
        MemberRow(
          FamilyMember(uid = "me", displayName = "You", role = me?.role ?: "adult"),
          isOwner = me?.role == "owner", onRemove = {},
        )
      }
    }
  }
}

@Composable
private fun MemberRow(m: FamilyMember, isOwner: Boolean, onRemove: (String) -> Unit) {
  val cs = MaterialTheme.colorScheme
  Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainer).padding(13.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Avatar((m.displayName ?: "?").take(1).uppercase(), cs.primaryContainer, cs.onPrimaryContainer)
    Column(Modifier.weight(1f)) {
      Text(m.displayName ?: "Member", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
      Text(m.role.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
    }
    if (isOwner) {
      RoleBadge("OWNER")          // owners can't be removed (≥1-owner invariant)
    } else {
      Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(50)).background(cs.surfaceContainerHigh)
          .testTag("remove-${m.uid}").clickable { onRemove(m.uid) },
        contentAlignment = Alignment.Center,
      ) { Text("✕", color = cs.error, style = MaterialTheme.typography.labelLarge) }
    }
  }
}

@Composable
private fun PendingRow(p: PendingMember, onApprove: (String) -> Unit, onDecline: (String) -> Unit) {
  val cs = MaterialTheme.colorScheme
  Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainer)
      .border(1.5.dp, cs.primaryContainer, RoundedCornerShape(16.dp)).padding(13.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Avatar((p.displayName ?: "?").take(1).uppercase(), cs.tertiaryContainer, cs.onTertiaryContainer)
    Column(Modifier.weight(1f)) {
      Text(p.displayName ?: "Someone", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
      Text("Invited as ${p.role}", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
    }
    // decline
    Box(
      Modifier.size(36.dp).clip(RoundedCornerShape(50)).background(cs.surfaceContainerHigh)
        .testTag("decline-${p.uid}").clickable { onDecline(p.uid) },
      contentAlignment = Alignment.Center,
    ) { Text("✕", color = cs.error, style = MaterialTheme.typography.labelLarge) }
    // approve
    Box(
      Modifier.size(36.dp).clip(RoundedCornerShape(50)).background(cs.primary)
        .testTag("approve-${p.uid}").clickable { onApprove(p.uid) },
      contentAlignment = Alignment.Center,
    ) { Text("✓", color = cs.onPrimary, style = MaterialTheme.typography.labelLarge) }
  }
}

@Composable
private fun Avatar(initial: String, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color) {
  Box(Modifier.size(40.dp).clip(RoundedCornerShape(50)).background(bg), contentAlignment = Alignment.Center) {
    Text(initial, style = MaterialTheme.typography.titleMedium, color = fg)
  }
}

@Composable
private fun RoleBadge(text: String) {
  val cs = MaterialTheme.colorScheme
  Box(Modifier.clip(RoundedCornerShape(8.dp)).background(cs.secondaryContainer).padding(horizontal = 10.dp, vertical = 4.dp)) {
    Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = cs.onSecondaryContainer)
  }
}

@Composable
private fun Label(text: String, color: androidx.compose.ui.graphics.Color) {
  Text(text, style = MaterialTheme.typography.labelLarge, color = color, modifier = Modifier.padding(start = 4.dp, bottom = 9.dp))
}
