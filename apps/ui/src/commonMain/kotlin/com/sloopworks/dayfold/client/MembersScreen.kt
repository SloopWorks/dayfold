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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sloopworks.dayfold.client.ui.loading.ErrorRetry
import com.sloopworks.dayfold.client.ui.loading.ListSkeleton
import com.sloopworks.dayfold.client.ui.loading.RowBusy

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
      Box(Modifier.size(38.dp).clip(RoundedCornerShape(50)).clickable(onClick = onBack).semantics { contentDescription = "Back" }, contentAlignment = Alignment.Center) {
        androidx.compose.material3.Icon(DayfoldIcons.ArrowBack, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(24.dp).clearAndSetSemantics {})
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
          state.pendingApprovals.forEach { p -> PendingRow(p, busy = p.uid == state.memberOpId, anyBusy = state.memberOpId != null, onApprove, onDecline) }
        }
        Spacer(Modifier.height(22.dp))
      }

      Label("MEMBERS", cs.onSurfaceVariant)
      when {
        state.members.isEmpty() && state.rosterBusy -> ListSkeleton(rows = 3)
        state.members.isEmpty() && state.rosterError != null -> ErrorRetry(state.rosterError, onRetry = onLoadMembers)
        state.members.isNotEmpty() ->
          Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            state.members.forEach { m ->
              MemberRow(m, isOwner = m.role == "owner", busy = m.uid == state.memberOpId, anyBusy = state.memberOpId != null, onRemove = onRemoveMember)
            }
          }
        else -> MemberRow(
          FamilyMember(uid = "me", displayName = "You", role = me?.role ?: "adult"),
          isOwner = me?.role == "owner", busy = false, anyBusy = false, onRemove = {},
        )
      }
    }
  }
}

@Composable
private fun MemberRow(m: FamilyMember, isOwner: Boolean, busy: Boolean, anyBusy: Boolean, onRemove: (String) -> Unit) {
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
      if (busy) RowBusy() else Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(50)).background(cs.surfaceContainerHigh)
          .testTag("remove-${m.uid}").clickable(enabled = !anyBusy) { onRemove(m.uid) }
          .semantics { contentDescription = "Remove ${m.displayName ?: "member"}" },
        contentAlignment = Alignment.Center,
      ) { Text("✕", color = cs.error, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clearAndSetSemantics {}) }
    }
  }
}

@Composable
private fun PendingRow(p: PendingMember, busy: Boolean, anyBusy: Boolean, onApprove: (String) -> Unit, onDecline: (String) -> Unit) {
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
    if (busy) {
      RowBusy()
    } else {
      Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(50)).background(cs.surfaceContainerHigh)
          .testTag("decline-${p.uid}").clickable(enabled = !anyBusy) { onDecline(p.uid) }
          .semantics { contentDescription = "Decline ${p.displayName ?: "request"}" },
        contentAlignment = Alignment.Center,
      ) { Text("✕", color = cs.error, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clearAndSetSemantics {}) }
      Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(50)).background(cs.primary)
          .testTag("approve-${p.uid}").clickable(enabled = !anyBusy) { onApprove(p.uid) }
          .semantics { contentDescription = "Approve ${p.displayName ?: "request"}" },
        contentAlignment = Alignment.Center,
      ) { Text("✓", color = cs.onPrimary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clearAndSetSemantics {}) }
    }
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
