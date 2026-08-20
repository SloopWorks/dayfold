package com.sloopworks.dayfold.client

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * ADR 0064 GAP 1 — the mute precision step, with the operator-decided audience segment.
 *
 * Personal is the default and is preselected; family-wide is an explicit second choice, never
 * the fast path, and the consequence is spoken before the commit button.
 */
data class ScopeRow(
  val scope: MatchScope,
  val icon: ImageVector,
  val title: String,
  val subtitle: String,
  val selected: Boolean,
  /**
   * The source rung does NOT mint a rule — it deep-links to the routine's own pause control in
   * Smart Briefings. Duplicating that control here would create two sources of truth for one
   * behavior (`NOTES.md` § Response-option catalog).
   */
  val deepLinksToRoutines: Boolean = false,
)

fun scopeRowsFor(sheet: ResponseSheetState): List<ScopeRow> = listOf(
  ScopeRow(
    MatchScope.SUBJECT, DayfoldIcons.Cancel,
    "Just this card", "This exact card won't return",
    selected = sheet.pendingMatchScope == MatchScope.SUBJECT,
  ),
  ScopeRow(
    MatchScope.KIND, DayfoldIcons.Category,
    "Any ${sheet.reasonKind} cards",
    "${sheet.source ?: "This routine"} stops making these",
    selected = sheet.pendingMatchScope == MatchScope.KIND,
  ),
  ScopeRow(
    MatchScope.SOURCE, DayfoldIcons.PauseCircle,
    "Everything from ${sheet.source ?: "this routine"}",
    "Pauses the routine — manage in Smart Briefings",
    selected = false,
    deepLinksToRoutines = true,
  ),
)

/** The commit button names the FINAL action, including who it applies to. */
fun commitLabel(sheet: ResponseSheetState, subjectNoun: String): String {
  val who = if (sheet.pendingAudience == AudienceScope.FAMILY) "everyone" else "you"
  return when (sheet.pendingMatchScope) {
    MatchScope.SUBJECT -> "Mute this card for $who"
    else -> "Mute $subjectNoun cards for $who"
  }
}

/** Spoken before the commit, so family-wide is never a surprise. */
fun audienceFootnote(): String =
  "Family-wide mutes are visible to everyone in Settings and removable by any adult — always attributed."

@Composable
fun ResponseScopeStep(
  sheet: ResponseSheetState,
  onScope: (MatchScope) -> Unit,
  onAudience: (AudienceScope) -> Unit,
  onOpenRoutines: () -> Unit,
  onCommit: () -> Unit,
  onBack: () -> Unit = {},
) {
  val cs = MaterialTheme.colorScheme
  Column(
    Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
      .padding(horizontal = 14.dp).padding(bottom = 22.dp),
  ) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back" }) {
        Icon(DayfoldIcons.ArrowBack, contentDescription = null, modifier = Modifier.size(22.dp).clearAndSetSemantics {})
      }
      Column(Modifier.weight(1f).padding(end = 8.dp)) {
        Text("Don't add this again", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Text(
          "How much should stop?",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          color = cs.onSurface,
        )
      }
    }

    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      scopeRowsFor(sheet).forEach { row ->
        ScopeRowItem(row, onClick = { if (row.deepLinksToRoutines) onOpenRoutines() else onScope(row.scope) })
      }
    }

    Spacer(Modifier.height(12.dp))
    Text(
      "WHO IT APPLIES TO",
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.Bold,
      color = cs.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 8.dp),
    )
    Spacer(Modifier.height(9.dp))
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      AudienceChip("For you", DayfoldIcons.Check, sheet.pendingAudience == AudienceScope.PERSONAL, Modifier.weight(1f)) {
        onAudience(AudienceScope.PERSONAL)
      }
      AudienceChip("Whole family", DayfoldIcons.Groups, sheet.pendingAudience == AudienceScope.FAMILY, Modifier.weight(1f)) {
        onAudience(AudienceScope.FAMILY)
      }
    }
    Spacer(Modifier.height(9.dp))
    Text(
      audienceFootnote(),
      style = MaterialTheme.typography.bodySmall,
      color = cs.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 8.dp),
    )

    Spacer(Modifier.height(14.dp))
    Button(
      onClick = onCommit,
      modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
      colors = ButtonDefaults.buttonColors(containerColor = cs.secondary, contentColor = cs.onSecondary),
    ) {
      Icon(DayfoldIcons.DoNotDisturbOn, contentDescription = null, modifier = Modifier.size(18.dp))
      Spacer(Modifier.size(7.dp))
      Text(commitLabel(sheet, sheet.reasonKind), fontWeight = FontWeight.SemiBold)
    }
  }
}

@Composable
private fun ScopeRowItem(row: ScopeRow, onClick: () -> Unit) {
  val cs = MaterialTheme.colorScheme
  val selection = if (row.deepLinksToRoutines) Modifier.clickable(onClick = onClick)
    else Modifier.selectable(selected = row.selected, role = Role.RadioButton, onClick = onClick)
  Row(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(14.dp))
      .background(if (row.selected) cs.secondaryContainer else cs.surface)
      .then(selection)
      .heightIn(min = 48.dp)
      .padding(horizontal = 10.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Icon(row.icon, contentDescription = null, tint = cs.secondary, modifier = Modifier.size(21.dp))
    Column(Modifier.weight(1f)) {
      Text(
        row.title,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
        color = if (row.selected) cs.onSecondaryContainer else cs.onSurface,
      )
      Text(row.subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
    if (row.selected) {
      Icon(DayfoldIcons.CheckCircle, contentDescription = "Selected", tint = cs.secondary, modifier = Modifier.size(20.dp))
    }
  }
}

@Composable
private fun AudienceChip(
  label: String,
  icon: ImageVector,
  selected: Boolean,
  modifier: Modifier = Modifier,
  onClick: () -> Unit,
) {
  val cs = MaterialTheme.colorScheme
  Row(
    modifier
      .clip(RoundedCornerShape(999.dp))
      .then(if (selected) Modifier.background(cs.secondaryContainer) else Modifier.border(1.dp, cs.outline, RoundedCornerShape(999.dp)))
      .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
      .heightIn(min = 48.dp)
      .padding(horizontal = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
  ) {
    Icon(
      icon,
      contentDescription = null,
      tint = if (selected) cs.onSecondaryContainer else cs.onSurfaceVariant,
      modifier = Modifier.size(16.dp),
    )
    Text(
      label,
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.SemiBold,
      color = if (selected) cs.onSecondaryContainer else cs.onSurfaceVariant,
    )
  }
}
