package com.sloopworks.dayfold.client

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/**
 * ADR 0064 GAP 5 — Settings › Smart content. Every rule provenance in one list, each honest
 * about where it lives, plus the Done records with byline and note.
 *
 * The LAST RUN row from the design is deliberately absent: its "saw N marked done" figure
 * comes from the ADR 0062 run receipt, which is Proposed and unbuilt. Rendering the row with
 * a fabricated count would be the one thing this feature's copy rules forbid — promising more
 * than the mechanism delivers.
 */
@Composable
fun SmartContentScreen(
  model: SmartContentModel,
  memberNames: Map<String, String>,
  offline: Boolean,
  onRemove: (String) -> Unit,
  onBack: () -> Unit,
) {
  val cs = MaterialTheme.colorScheme
  Column(Modifier.fillMaxSize().background(cs.surface).verticalScroll(rememberScrollState())) {
    if (offline) {
      Row(
        Modifier.fillMaxWidth().background(cs.surfaceContainerHigh).padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(DayfoldIcons.WifiOff, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Text(
          SMART_CONTENT_OFFLINE_BANNER,
          style = MaterialTheme.typography.bodySmall,
          fontWeight = FontWeight.SemiBold,
          color = cs.onSurfaceVariant,
        )
      }
    }

    Row(
      Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
      Icon(
        DayfoldIcons.ArrowBack,
        contentDescription = "Back",
        tint = cs.onSurfaceVariant,
        modifier = Modifier.size(22.dp).clip(RoundedCornerShape(999.dp)).clickable(onClick = onBack),
      )
      Text("Smart content", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
    }

    SectionHeader(SMART_CONTENT_MUTED_HEADER)
    if (model.mutedRules.isEmpty()) {
      EmptyLine("Nothing muted yet.")
    } else {
      Column(
        Modifier.padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        model.mutedRules.forEach { row -> RuleRowItem(row, memberNames, onRemove) }
      }
    }

    Spacer(Modifier.height(16.dp))
    SectionHeader(SMART_CONTENT_DONE_HEADER)
    if (model.doneRecords.isEmpty()) {
      EmptyLine("Nothing marked done yet.")
    } else {
      Column(
        Modifier.padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        model.doneRecords.forEach { row -> DoneRowItem(row, memberNames) }
      }
    }

    Spacer(Modifier.height(16.dp))
    Row(
      Modifier
        .padding(horizontal = 18.dp)
        .clip(RoundedCornerShape(10.dp))
        .background(cs.secondaryContainer)
        .padding(horizontal = 11.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
      Icon(DayfoldIcons.Verified, contentDescription = null, tint = cs.onSecondaryContainer, modifier = Modifier.size(15.dp))
      Text(
        SMART_CONTENT_PRIVACY_NOTE,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.SemiBold,
        color = cs.onSecondaryContainer,
      )
    }
    Spacer(Modifier.height(24.dp))
  }
}

@Composable
private fun SectionHeader(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.labelSmall,
    fontWeight = FontWeight.Bold,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(start = 22.dp, bottom = 8.dp),
  )
}

@Composable
private fun EmptyLine(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(horizontal = 22.dp),
  )
}

@Composable
private fun RuleRowItem(row: RuleRow, memberNames: Map<String, String>, onRemove: (String) -> Unit) {
  val cs = MaterialTheme.colorScheme
  Row(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(16.dp))
      .background(cs.surfaceContainer)
      .padding(horizontal = 13.dp, vertical = 11.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Icon(
      if (row.pending) DayfoldIcons.WifiOff else DayfoldIcons.DoNotDisturbOn,
      contentDescription = null,
      tint = cs.secondary,
      modifier = Modifier.size(19.dp),
    )
    Column(Modifier.weight(1f)) {
      Text(row.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
      Text(sublineFor(row, memberNames), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
    if (row.removable) {
      Text(
        "Remove",
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Bold,
        color = cs.onSecondaryContainer,
        modifier = Modifier
          .clip(RoundedCornerShape(999.dp))
          .background(cs.secondaryContainer)
          .clickable { onRemove(row.id) }
          .heightIn(min = 48.dp)
          .padding(horizontal = 12.dp, vertical = 14.dp),
      )
    }
  }
}

@Composable
private fun DoneRowItem(row: DoneRow, memberNames: Map<String, String>) {
  val cs = MaterialTheme.colorScheme
  Row(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(16.dp))
      .border(1.dp, cs.outlineVariant, RoundedCornerShape(16.dp))
      .padding(horizontal = 13.dp, vertical = 11.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Icon(DayfoldIcons.CheckCircle, contentDescription = null, tint = cs.secondary, modifier = Modifier.size(21.dp))
    Column(Modifier.weight(1f)) {
      Text(
        row.title,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = cs.onSurface,
        textDecoration = TextDecoration.LineThrough,
      )
      Text(doneSublineFor(row, memberNames), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
  }
}
