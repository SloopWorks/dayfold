package com.sloopworks.dayfold.client.features.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.JoinLeft
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sloopworks.dayfold.client.CalendarGapKind
import com.sloopworks.dayfold.client.DayfoldIcons
import com.sloopworks.dayfold.client.FieldResolution

// WI-446 (ADR 0063 §4/§5, designs/calendar-reconciliation/Review.dc.html §16-22, NOTES.md's M3
// mapping table) — stateless review-flow screens. Every value is a param, every action a callback
// (mirrors CalendarSettingsScreens.kt) — CalendarReviewHost owns store/dispatch. No new reconciler
// logic here: these screens only render CalendarSelectors.kt's already-computed viewstate and hand
// taps back as plain callbacks.

@Composable
private fun ReviewTopBar(compareLabel: String, onBack: () -> Unit) {
  val cs = MaterialTheme.colorScheme
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Row(
      Modifier.heightIn(min = 48.dp).clickable(onClick = onBack).padding(horizontal = 8.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(DayfoldIcons.ArrowBack, contentDescription = "Back", tint = cs.onSurface, modifier = Modifier.size(25.dp))
    }
    Spacer(Modifier.width(6.dp))
    Column {
      Text("Calendar check", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(DayfoldIcons.Lock, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(13.dp))
        Text(compareLabel, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
      }
    }
  }
}

@Composable
private fun SectionHeader(text: String, color: androidx.compose.ui.graphics.Color) {
  val cs = MaterialTheme.colorScheme
  Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = color)
    HorizontalDivider(Modifier.weight(1f), color = cs.outlineVariant)
  }
}

@Composable
private fun CalendarDotLabel(dotColor: String?, label: String) {
  val cs = MaterialTheme.colorScheme
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Box(Modifier.size(9.dp).background(colorFromHex(dotColor), CircleShape))
    Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
  }
}

// ============ 16+17 · REVIEW LIST ============
@Composable
fun CalendarReviewListScreen(
  ui: CalendarReviewListUiState,
  compareLabel: String,
  onBack: () -> Unit,
  onAddToCalendar: (DayfoldOnlyRow) -> Unit,
  onIgnoreDayfoldOnly: (DayfoldOnlyRow) -> Unit,
  onOpenHub: (DayfoldOnlyRow) -> Unit,
  onOpenNeedsReview: (NeedsReviewRow) -> Unit,
  onKeepCalendarOnly: (CalendarOnlyRow) -> Unit,
  onAddToHub: (CalendarOnlyRow) -> Unit,
  onOpenIgnored: () -> Unit,
  onResumeImport: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val cs = MaterialTheme.colorScheme
  Column(modifier.fillMaxWidth()) {
    ReviewTopBar(compareLabel, onBack)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      if (onResumeImport != null) {
        FilledTonalButton(onClick = onResumeImport, modifier = Modifier.fillMaxWidth()) {
          Icon(DayfoldIcons.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(8.dp))
          Text("Resume calendar import", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.size(8.dp))
      }
      if (ui.dayfoldRows.isNotEmpty() || ui.needsReviewRows.isNotEmpty()) {
        SectionHeader("IN DAYFOLD · NOT ON THIS CALENDAR", cs.primary)
        ui.dayfoldRows.forEach { row ->
          DayfoldOnlyCard(row, onAddToCalendar = { onAddToCalendar(row) }, onIgnore = { onIgnoreDayfoldOnly(row) }, onOpenHub = { onOpenHub(row) })
          Spacer(Modifier.size(2.dp))
        }
        ui.needsReviewRows.forEach { row ->
          NeedsReviewCard(row, onClick = { onOpenNeedsReview(row) })
          Spacer(Modifier.size(2.dp))
        }
        Spacer(Modifier.size(12.dp))
      }
      if (ui.calendarOnlyRows.isNotEmpty()) {
        SectionHeader("ON YOUR CALENDAR · NOT IN DAYFOLD", cs.secondary)
        ui.calendarOnlyRows.forEach { row ->
          CalendarOnlyCard(row, onKeepCalendarOnly = { onKeepCalendarOnly(row) }, onAddToHub = { onAddToHub(row) })
          Spacer(Modifier.size(2.dp))
        }
        Spacer(Modifier.size(8.dp))
      }
      Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
        Icon(Icons.Filled.Info, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Text("Nothing here is urgent — ignoring is always fine.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
      }
      if (ui.ignoredCount > 0) {
        Spacer(Modifier.size(8.dp))
        Surface(
          shape = RoundedCornerShape(16.dp), color = cs.surfaceContainerLow,
          border = androidx.compose.foundation.BorderStroke(1.5.dp, cs.outlineVariant),
          modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenIgnored).semantics { contentDescription = "${ui.ignoredCount} ignored on this phone" },
        ) {
          Row(Modifier.padding(horizontal = 15.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.VisibilityOff, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(
              if (ui.ignoredCount == 1) "1 ignored on this phone" else "${ui.ignoredCount} ignored on this phone",
              style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f),
            )
            Icon(DayfoldIcons.ChevronRight, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
          }
        }
      }
    }
  }
}

@Composable
private fun DayfoldOnlyCard(row: DayfoldOnlyRow, onAddToCalendar: () -> Unit, onIgnore: () -> Unit, onOpenHub: () -> Unit) {
  val cs = MaterialTheme.colorScheme
  OutlinedCard(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp)) {
      Row(verticalAlignment = Alignment.Top) {
        Surface(shape = RoundedCornerShape(13.dp), color = cs.primaryContainer, modifier = Modifier.size(40.dp)) {
          Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Icon(DayfoldIcons.Event, contentDescription = null, tint = cs.onPrimaryContainer, modifier = Modifier.size(20.dp)) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
          Text(row.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
          Text(row.meta, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
      }
      FlowRow(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = onAddToCalendar, contentPadding = ButtonDefaults.ContentPadding) {
          Icon(Icons.Filled.EditCalendar, contentDescription = null, modifier = Modifier.size(17.dp))
          Spacer(Modifier.width(6.dp))
          Text("Add to calendar", fontWeight = FontWeight.SemiBold)
        }
        OutlinedButton(onClick = onIgnore) { Text("Ignore", fontWeight = FontWeight.SemiBold) }
        // A card-derived candidate with no target hub (CalendarCandidates.kt's
        // `card.targetHubId?.let { ... }`) has nothing to deep-link into — omit the button rather
        // than leave a tap that silently does nothing.
        if (row.target != null) {
          TextButton(onClick = onOpenHub) {
            Text("Open Hub", fontWeight = FontWeight.SemiBold, maxLines = 1)
            Icon(DayfoldIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp))
          }
        }
      }
    }
  }
}

@Composable
private fun CalendarOnlyCard(row: CalendarOnlyRow, onKeepCalendarOnly: () -> Unit, onAddToHub: () -> Unit) {
  val cs = MaterialTheme.colorScheme
  OutlinedCard(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp)) {
      Row(verticalAlignment = Alignment.Top) {
        Box {
          Surface(shape = RoundedCornerShape(13.dp), color = cs.surfaceContainerHigh, modifier = Modifier.size(40.dp)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Icon(DayfoldIcons.CalendarMonth, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
          }
          Box(Modifier.size(9.dp).background(colorFromHex(row.dotColor), CircleShape).align(Alignment.TopEnd))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
          Text(row.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
          Text(row.meta, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
      }
      FlowRow(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onKeepCalendarOnly) { Text("Keep calendar-only", fontWeight = FontWeight.SemiBold) }
        if (row.isRecurring) {
          Text(
            "Recurring series stay in Calendar",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
          )
        } else {
          FilledTonalButton(onClick = onAddToHub) {
            Icon(Icons.Filled.AddCircle, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add to a Hub", fontWeight = FontWeight.SemiBold)
          }
        }
      }
    }
  }
}

@Composable
private fun NeedsReviewCard(row: NeedsReviewRow, onClick: () -> Unit) {
  val cs = MaterialTheme.colorScheme
  OutlinedCard(
    shape = RoundedCornerShape(18.dp),
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).semantics { contentDescription = "${row.kindLabel}, ${row.title}" },
  ) {
    Row(Modifier.padding(horizontal = 15.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
      Icon(needsReviewIcon(row.gapKind), contentDescription = null, tint = cs.tertiary, modifier = Modifier.size(20.dp))
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)) {
        Text(row.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
        Text(row.kindLabel, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
      }
      Icon(DayfoldIcons.ChevronRight, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
  }
}

private fun needsReviewIcon(gapKind: String): ImageVector = when (gapKind) {
  CalendarGapKind.SUGGESTED -> Icons.Filled.JoinLeft
  CalendarGapKind.AMBIGUOUS -> Icons.Filled.AltRoute
  CalendarGapKind.DIFFERS -> Icons.Filled.Difference
  CalendarGapKind.RECURRING -> Icons.Filled.EventRepeat
  else -> Icons.Filled.Difference
}

// ============ 18 · SUGGESTED MATCH ============
@Composable
fun CalendarSuggestedMatchScreen(
  ui: CalendarSuggestedUiState,
  onBack: () -> Unit,
  onKeepSeparate: () -> Unit,
  onConfirmMatch: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val cs = MaterialTheme.colorScheme
  Column(modifier.fillMaxWidth()) {
    ReviewTopBar("Compared on this phone", onBack)
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
      Surface(shape = RoundedCornerShape(999.dp), color = cs.tertiaryContainer, modifier = Modifier.padding(bottom = 14.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Icon(Icons.Filled.JoinLeft, contentDescription = null, tint = cs.onTertiaryContainer, modifier = Modifier.size(15.dp))
          Text("Looks like the same event — you decide", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = cs.onTertiaryContainer)
        }
      }
      Surface(shape = RoundedCornerShape(22.dp), color = cs.tertiaryContainer.copy(alpha = 0.35f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(DayfoldIcons.Event, contentDescription = null, tint = cs.primary, modifier = Modifier.size(17.dp))
            Text("IN DAYFOLD", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
          }
          Text(ui.dayfoldTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = cs.onSurface, modifier = Modifier.padding(top = 10.dp))
          Text(ui.dayfoldMeta, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
      }
      Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(shape = CircleShape, color = cs.surfaceContainerHigh, modifier = Modifier.size(34.dp)) {
          Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Icon(Icons.Filled.UnfoldLess, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(19.dp)) }
        }
      }
      Surface(shape = RoundedCornerShape(22.dp), color = cs.tertiaryContainer.copy(alpha = 0.35f), modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
          CalendarDotLabel(ui.calendarDotColor, "ON FAMILY CALENDAR")
          Text(ui.calendarTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = cs.onSurface, modifier = Modifier.padding(top = 10.dp))
          Text(ui.calendarMeta, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
      }
      Surface(shape = RoundedCornerShape(18.dp), color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
        Column(Modifier.padding(14.dp)) {
          Text("WHY THEY LOOK THE SAME", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
          ui.evidence.forEach { e ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 6.dp)) {
              Icon(DayfoldIcons.Check, contentDescription = null, tint = cs.secondary, modifier = Modifier.size(16.dp))
              Text(e, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
            }
          }
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        OutlinedButton(onClick = onKeepSeparate, modifier = Modifier.weight(1f).heightIn(min = 50.dp), shape = RoundedCornerShape(999.dp)) { Text("Keep separate", fontWeight = FontWeight.SemiBold) }
        FilledTonalButton(onClick = onConfirmMatch, modifier = Modifier.weight(1f).heightIn(min = 50.dp), shape = RoundedCornerShape(999.dp)) {
          Icon(DayfoldIcons.Link, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(7.dp))
          Text("Confirm match", fontWeight = FontWeight.SemiBold)
        }
      }
      Text(
        "Confirming links them on this phone.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 20.dp),
      )
    }
  }
}

// ============ 19 · AMBIGUOUS MATCH ============
@Composable
fun CalendarAmbiguousMatchScreen(
  ui: CalendarAmbiguousUiState,
  onBack: () -> Unit,
  onLeaveUnresolved: () -> Unit,
  onMatchSelected: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val cs = MaterialTheme.colorScheme
  var chosen by remember(ui.subjectKey) { mutableStateOf<String?>(null) }
  Column(modifier.fillMaxWidth()) {
    ReviewTopBar("Compared on this phone", onBack)
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)) {
      Surface(shape = RoundedCornerShape(999.dp), color = cs.tertiaryContainer, modifier = Modifier.padding(bottom = 14.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Icon(Icons.Filled.AltRoute, contentDescription = null, tint = cs.onTertiaryContainer, modifier = Modifier.size(15.dp))
          Text("Two calendar events could match — we're not sure", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = cs.onTertiaryContainer)
        }
      }
      Surface(shape = RoundedCornerShape(22.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(DayfoldIcons.Event, contentDescription = null, tint = cs.primary, modifier = Modifier.size(17.dp))
            Text("IN DAYFOLD", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
          }
          Text(ui.dayfoldTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = cs.onSurface, modifier = Modifier.padding(top = 10.dp))
          Text(ui.dayfoldMeta, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
      }
      Text("WHICH ONE IS IT — IF EITHER?", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 9.dp, start = 4.dp))
      ui.candidates.forEach { cand ->
        val selected = chosen == cand.eventId
        OutlinedCard(
          shape = RoundedCornerShape(18.dp),
          modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp).clickable { chosen = cand.eventId }
            .semantics { contentDescription = "${cand.title}, ${cand.meta}${if (selected) ", selected" else ""}" },
        ) {
          Row(Modifier.padding(horizontal = 15.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = { chosen = cand.eventId })
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
              Text(cand.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
                Box(Modifier.size(9.dp).background(colorFromHex(cand.dotColor), CircleShape))
                Text(cand.meta, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
              }
            }
          }
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.padding(top = 8.dp)) {
        OutlinedButton(onClick = onLeaveUnresolved, modifier = Modifier.weight(1f).heightIn(min = 50.dp), shape = RoundedCornerShape(999.dp)) { Text("Leave unresolved", fontWeight = FontWeight.SemiBold) }
        Button(
          onClick = { chosen?.let(onMatchSelected) }, enabled = chosen != null,
          modifier = Modifier.weight(1f).heightIn(min = 50.dp), shape = RoundedCornerShape(999.dp),
        ) { Text("Match selected", fontWeight = FontWeight.SemiBold) }
      }
      Text(
        "Leaving it unresolved is always safe.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 20.dp),
      )
    }
  }
}

// ============ 20 · DETAILS DIFFER ============
@Composable
fun CalendarDetailsDifferScreen(
  ui: CalendarDifferUiState,
  onBack: () -> Unit,
  onFieldChoice: (field: String, resolution: FieldResolution) -> Unit,
  modifier: Modifier = Modifier,
) {
  val cs = MaterialTheme.colorScheme
  Column(modifier.fillMaxWidth()) {
    ReviewTopBar("Compared on this phone", onBack)
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)) {
      Surface(shape = RoundedCornerShape(999.dp), color = cs.tertiaryContainer, modifier = Modifier.padding(bottom = 8.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Icon(Icons.Filled.Difference, contentDescription = null, tint = cs.onTertiaryContainer, modifier = Modifier.size(15.dp))
          Text("Matched, but the details differ", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = cs.onTertiaryContainer)
        }
      }
      Text(ui.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
      CalendarDotLabel(ui.calendarDotColor, "Linked to ${ui.calendarName} · decide field by field")
      Spacer(Modifier.size(16.dp))
      ui.diffs.forEach { d ->
        Surface(shape = RoundedCornerShape(20.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
          Column(Modifier.padding(15.dp)) {
            Text(fieldDiffLabel(d.field), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.padding(bottom = 11.dp)) {
              FieldValueTile("DAYFOLD", d.dayfoldValue ?: "—", DayfoldIcons.Event, cs.primary, Modifier.weight(1f))
              FieldValueTile("CALENDAR", d.calendarValue ?: "—", null, null, Modifier.weight(1f), dotColor = ui.calendarDotColor)
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
              OutlinedButton(onClick = { onFieldChoice(d.field, FieldResolution.KEEP_DAYFOLD) }) { Text("Keep Dayfold's", fontWeight = FontWeight.SemiBold) }
              if (d.calendarWriteSupported) {
                OutlinedButton(onClick = { onFieldChoice(d.field, FieldResolution.USE_CALENDAR) }) { Text("Use Calendar's", fontWeight = FontWeight.SemiBold) }
              }
              TextButton(onClick = { onFieldChoice(d.field, FieldResolution.LEAVE_BOTH) }) { Text("Leave both as-is", fontWeight = FontWeight.SemiBold, maxLines = 1) }
            }
          }
        }
      }
      Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.padding(bottom = 20.dp)) {
        Icon(Icons.Filled.Info, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Text("Nothing merges silently — each choice is confirmed.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
      }
    }
  }
}

@Composable
private fun FieldValueTile(label: String, value: String, icon: ImageVector?, iconTint: androidx.compose.ui.graphics.Color?, modifier: Modifier = Modifier, dotColor: String? = null) {
  val cs = MaterialTheme.colorScheme
  Surface(shape = RoundedCornerShape(14.dp), color = cs.surface, modifier = modifier) {
    Column(Modifier.padding(12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if (icon != null) Icon(icon, contentDescription = null, tint = iconTint ?: cs.onSurfaceVariant, modifier = Modifier.size(14.dp))
        else Box(Modifier.size(8.dp).background(colorFromHex(dotColor), CircleShape))
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
      }
      Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurface, modifier = Modifier.padding(top = 4.dp))
    }
  }
}

// ============ 21 · RECURRING EVENT ============
@Composable
fun CalendarRecurringScreen(
  ui: CalendarRecurringUiState,
  onBack: () -> Unit,
  onReviewOccurrence: () -> Unit,
  onKeepSeriesCalendarOnly: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val cs = MaterialTheme.colorScheme
  Column(modifier.fillMaxWidth()) {
    ReviewTopBar("Compared on this phone", onBack)
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)) {
      Surface(shape = RoundedCornerShape(22.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
          Box {
            Surface(shape = RoundedCornerShape(13.dp), color = cs.surfaceContainerHigh, modifier = Modifier.size(40.dp)) {
              Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Repeat, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
            }
            Box(Modifier.size(9.dp).background(colorFromHex(ui.calendarDotColor), CircleShape).align(Alignment.TopEnd))
          }
          Spacer(Modifier.width(12.dp))
          Column(Modifier.weight(1f)) {
            Text(ui.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
              Icon(Icons.Filled.Repeat, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(14.dp))
              Text(ui.recurrenceMeta, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
          }
        }
      }
      Surface(shape = RoundedCornerShape(18.dp), color = cs.tertiaryContainer, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.Top) {
          Icon(Icons.Filled.EventRepeat, contentDescription = null, tint = cs.onTertiaryContainer, modifier = Modifier.size(22.dp))
          Spacer(Modifier.width(12.dp))
          Text(
            "This event repeats. Dayfold reviews one occurrence at a time — the series stays your calendar's.",
            style = MaterialTheme.typography.bodyMedium, color = cs.onTertiaryContainer,
          )
        }
      }
      RecurringActionRow(DayfoldIcons.Event, "Review just this occurrence", "The series is untouched", onReviewOccurrence)
      Spacer(Modifier.size(9.dp))
      RecurringActionRow(DayfoldIcons.CalendarMonth, "Keep the series calendar-only", "Won't come up again on this phone", onKeepSeriesCalendarOnly)
      Spacer(Modifier.size(20.dp))
    }
  }
}

@Composable
private fun RecurringActionRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
  val cs = MaterialTheme.colorScheme
  Surface(shape = RoundedCornerShape(16.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
    Row(Modifier.padding(horizontal = 15.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
      Icon(icon, contentDescription = null, tint = cs.secondary, modifier = Modifier.size(20.dp))
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
        Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
      }
      Icon(DayfoldIcons.ChevronRight, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
  }
}

// ============ 22 · IGNORED LOCALLY ============
data class IgnoredRowDisplay(val itemKey: String, val icon: ImageVector, val title: String, val meta: String)

@Composable
fun CalendarIgnoredScreen(rows: List<IgnoredRowDisplay>, onBack: () -> Unit, onUndo: (IgnoredRowDisplay) -> Unit, modifier: Modifier = Modifier) {
  val cs = MaterialTheme.colorScheme
  Column(modifier.fillMaxWidth()) {
    ReviewTopBar("Compared on this phone", onBack)
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)) {
      SectionHeader("IGNORED · ON THIS PHONE ONLY", cs.onSurfaceVariant)
      Text(
        "These stay out of your calendar check on this phone. Nothing was changed anywhere.",
        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 14.dp, top = 4.dp),
      )
      rows.forEach { row ->
        Surface(shape = RoundedCornerShape(16.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp)) {
          Row(
            Modifier.padding(horizontal = 15.dp, vertical = 13.dp).semantics { contentDescription = "ignored on this phone, ${row.title}, undo available" },
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Surface(shape = RoundedCornerShape(11.dp), color = cs.surfaceContainerHigh, modifier = Modifier.size(36.dp)) {
              Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Icon(row.icon, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
              Text(row.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
              Text(row.meta, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
            TextButton(onClick = { onUndo(row) }) {
              Icon(Icons.Filled.Undo, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(5.dp))
              Text("Undo", fontWeight = FontWeight.SemiBold)
            }
          }
        }
      }
    }
  }
}
