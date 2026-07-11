package com.sloopworks.dayfold.client

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sloopworks.dayfold.client.ui.DayfoldAvatar
import com.sloopworks.dayfold.client.ui.FunAvatars

// ADR 0053 DC5 — the People management sheet: owner/co-owner-only participant + visibility
// controls, built on top of DC1-DC4's role/canManage model. Pure state-in/callback-out
// (mirrors AvatarPickerSheet) — HubScreens/HubsHost decides WHEN to open it (canManage) and
// wires the callbacks to HubEngine.setParticipant/removeParticipant/setVisibility; this
// composable never touches the store or the engine.
//
// Owner-row identification (DC5 code-review fix): the locked "Owner" row is pinned via the
// server-computed HubAudienceMember.isAuthor flag (uid == hubs.created_by, see hubs.hubAudience
// in apps/api/src/content/hubs.ts) — NOT "first co_owner in list order". A non-author co_owner
// (added later via this sheet) is a normal, editable/removable participant row.
//
// Write-failure surfacing (DC5 code-review fix): [errorMessage] is a plain input string — the
// sheet stays pure (no store/engine access) — HubsHost passes state.audienceError, which
// HubEngine.setParticipant/removeParticipant/setVisibility populate via HubManageFailed on a
// failed write (network error, or e.g. a 400 rejecting an author-immutable edit).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubPeopleSheet(
  audience: HubAudience,
  onSetRole: (uid: String, role: String) -> Unit,
  onRemove: (uid: String) -> Unit,
  onSetVisibility: (visibility: String) -> Unit,
  onAddPeople: () -> Unit,
  onDismiss: () -> Unit,
  errorMessage: String? = null,
) {
  val sheetState = rememberModalBottomSheetState()
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    HubPeopleContent(audience, onSetRole, onRemove, onSetVisibility, onAddPeople, errorMessage)
  }
}

// Split out (mirrors AvatarPickerContent) so it's directly scenable for the golden registry —
// a headless single-frame render never paints a ModalBottomSheet's separate Dialog scene.
@Composable
fun HubPeopleContent(
  audience: HubAudience,
  onSetRole: (uid: String, role: String) -> Unit,
  onRemove: (uid: String) -> Unit,
  onSetVisibility: (visibility: String) -> Unit,
  onAddPeople: () -> Unit,
  errorMessage: String? = null,
) {
  val cs = MaterialTheme.colorScheme
  val pinnedUid = audience.members.firstOrNull { it.isAuthor }?.uid
  val owner = audience.members.firstOrNull { it.uid == pinnedUid }
  val participants = audience.members.filter { it.permitted && it.uid != pinnedUid }
  // Confirm-pending target visibility; non-null while the who-loses-access dialog is up.
  var pendingVisibility by remember { mutableStateOf<String?>(null) }

  // Compact layout intentional (mirrors AvatarPickerContent): the sheet must fit a headless
  // test's default 1024x768 virtual window (no scroll wired) — a tall column pushes later
  // rows/the Add-people button past the visible bounds and performClick() throws.
  Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text("People in this hub", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

    // Inline write-failure banner (mirrors WhoCanSeeSheet/ErrorRetry's error-container
    // treatment, but inline — a failed role/remove/visibility op must not blow away the
    // roster the manager is mid-edit on).
    if (errorMessage != null) {
      Surface(color = cs.errorContainer, shape = RoundedCornerShape(12.dp)) {
        Text(
          errorMessage, style = MaterialTheme.typography.bodySmall, color = cs.onErrorContainer,
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
      }
    }

    // Family/Restricted visibility toggle. Loosening (restricted→family) applies immediately;
    // tightening (family→restricted) opens the who-loses-access confirm first (ADR 0030).
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
      SegmentedButton(
        selected = audience.visibility == "family",
        onClick = { if (audience.visibility != "family") onSetVisibility("family") },
        shape = SegmentedButtonDefaults.itemShape(0, 2),
      ) { Text("Family") }
      SegmentedButton(
        selected = audience.visibility == "restricted",
        onClick = { if (audience.visibility != "restricted") pendingVisibility = "restricted" },
        shape = SegmentedButtonDefaults.itemShape(1, 2),
      ) { Text("Restricted") }
    }

    owner?.let { OwnerRow(it) }
    participants.forEach { m -> ParticipantRow(m, onSetRole = onSetRole, onRemove = onRemove) }

    // Inline role explainer — one line, no separate matrix/sheet (M-next gets the tap-to-
    // expand role-explainer sheet; this is the always-visible plain-language summary).
    Text(
      "Viewer sees this hub. Contributor can also add to it. Co-owner can also manage people.",
      style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
    )

    Button(onClick = onAddPeople, modifier = Modifier.fillMaxWidth()) { Text("Add people") }
  }

  if (pendingVisibility != null) {
    // Who loses access on family→restricted: anyone currently permitted only via the
    // family-wide default (no explicit allow-list row) — the pinned owner keeps access
    // (author's implicit permanent co_owner status, ADR 0053), so is excluded here.
    val losing = audience.members.filter { it.permitted && it.uid != pinnedUid && it.participationRole == null }
    AlertDialog(
      onDismissRequest = { pendingVisibility = null },
      title = { Text("Restrict this hub?") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("Only the people you've named will see it. Everyone else in the family loses access:")
          if (losing.isEmpty()) {
            Text("No one else currently has access.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
          } else {
            losing.forEach { m -> Text("• " + (m.displayName ?: "Member"), style = MaterialTheme.typography.bodyMedium) }
          }
          Text(
            "They'll simply stop seeing this hub on their next sync — nothing is announced.",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
          )
        }
      },
      confirmButton = {
        TextButton(onClick = { onSetVisibility("restricted"); pendingVisibility = null }) { Text("Restrict") }
      },
      dismissButton = { TextButton(onClick = { pendingVisibility = null }) { Text("Cancel") } },
      containerColor = cs.surfaceContainerHigh,
    )
  }
}

// The pinned/locked hub-author row — Owner badge, no dropdown, no remove (see the
// class-level KNOWN LIMITATION doc for how this row is identified).
@Composable
private fun OwnerRow(m: HubAudienceMember) {
  val cs = MaterialTheme.colorScheme
  Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainer).padding(horizontal = 14.dp, vertical = 11.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    DayfoldAvatar(
      name = m.displayName ?: "?", size = 40.dp,
      avatarColorKey = m.avatarColor, avatarRef = m.avatarRef,
      contentDescription = FunAvatars.resolve(m.avatarRef)?.name,
    )
    Column(Modifier.weight(1f)) {
      Text(m.displayName ?: "Member", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
      Text("Created this hub · always has access", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
    }
    Surface(color = cs.secondaryContainer, shape = RoundedCornerShape(8.dp)) {
      Text("Owner", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = cs.onSecondaryContainer, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
  }
}

// A managed participant: avatar + name + role DropdownMenu (Viewer/Contributor/Co-owner) +
// remove. permitted-but-no-explicit-row participants default to Viewer (the family-visible
// implicit tier, ADR 0053).
@Composable
private fun ParticipantRow(m: HubAudienceMember, onSetRole: (String, String) -> Unit, onRemove: (String) -> Unit) {
  val cs = MaterialTheme.colorScheme
  var menuOpen by remember { mutableStateOf(false) }
  val currentRole = m.participationRole ?: "viewer"
  Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainer).padding(horizontal = 14.dp, vertical = 9.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp),
  ) {
    DayfoldAvatar(
      name = m.displayName ?: "?", size = 40.dp,
      avatarColorKey = m.avatarColor, avatarRef = m.avatarRef,
      contentDescription = FunAvatars.resolve(m.avatarRef)?.name,
    )
    Text(m.displayName ?: "Member", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurface, modifier = Modifier.weight(1f))
    Box {
      Surface(
        color = cs.surface, shape = RoundedCornerShape(10.dp),
        modifier = Modifier.testTag("role-${m.uid}")
          .clickable(onClickLabel = "Change ${m.displayName ?: "member"}'s role") { menuOpen = true }
          .semantics { contentDescription = "Change ${m.displayName ?: "member"}'s role" },
      ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
          Text(roleLabel(currentRole), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
          androidx.compose.material3.Icon(DayfoldIcons.ExpandMore, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp).clearAndSetSemantics {})
        }
      }
      DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        listOf("viewer" to "Viewer", "contributor" to "Contributor", "co_owner" to "Co-owner").forEach { (value, label) ->
          DropdownMenuItem(text = { Text(label) }, onClick = { menuOpen = false; onSetRole(m.uid, value) })
        }
      }
    }
    Box(
      Modifier.size(30.dp).clip(RoundedCornerShape(50)).background(cs.surfaceContainerHigh)
        .testTag("remove-${m.uid}").clickable { onRemove(m.uid) }
        .semantics { contentDescription = "Remove ${m.displayName ?: "member"}" },
      contentAlignment = Alignment.Center,
    ) { Text("✕", color = cs.error, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clearAndSetSemantics {}) }
  }
}

private fun roleLabel(role: String): String = when (role) {
  "contributor" -> "Contributor"
  "co_owner" -> "Co-owner"
  else -> "Viewer"
}
