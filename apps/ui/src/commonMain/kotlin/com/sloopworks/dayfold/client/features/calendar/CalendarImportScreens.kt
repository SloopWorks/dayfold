package com.sloopworks.dayfold.client.features.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sloopworks.dayfold.client.CalendarImportProposal
import com.sloopworks.dayfold.client.DayfoldIcons
import com.sloopworks.dayfold.client.HubVisibilityChoice
import com.sloopworks.dayfold.client.ImportDestination
import com.sloopworks.dayfold.client.ImportFieldDiff

// CAL-10 (ADR 0063 §6, designs/calendar-reconciliation/Import.dc.html §23-27) — stateless import-
// wizard screens. Every value is a param, every action a callback (mirrors CalendarReviewScreens.kt)
// — CalendarImportHost owns store/dispatch/engine.

// ============ 23 · CHOOSE DESTINATION ============
@Composable
fun CalendarImportDestinationScreen(
  existingHubs: List<ImportDestinationRow>,
  onChooseNewHub: () -> Unit,
  onChooseExistingHub: (ImportDestinationRow) -> Unit,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val cs = MaterialTheme.colorScheme
  Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Add to Dayfold", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    SectionLabel("START FRESH")
    OutlinedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
      Row(Modifier.padding(15.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.AddCircle, contentDescription = null, tint = cs.primary, modifier = Modifier.size(28.dp))
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
          Text("New Event Hub", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
          Text("Starts private — only you, until you share it", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
      }
    }
    Button(onClick = onChooseNewHub, modifier = Modifier.fillMaxWidth()) { Text("Choose New Event Hub") }
    if (existingHubs.isNotEmpty()) {
      SectionLabel("OR ADD TO A HUB YOU CAN EDIT")
      existingHubs.forEach { row ->
        OutlinedCard(
          shape = RoundedCornerShape(16.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              Text(row.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            FilledTonalButton(onClick = { onChooseExistingHub(row) }) { Text(row.role.replace('_', '-').uppercase()) }
          }
        }
      }
    }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Icon(Icons.Filled.Info, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
      Text("Only Hubs you can edit are listed.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
    Spacer(Modifier.size(4.dp))
    OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
  }
}

// ============ 24 · FIELD PREVIEW ============
@Composable
fun CalendarImportFieldsScreen(
  proposal: CalendarImportProposal,
  descriptionAvailable: Boolean,
  descriptionIncluded: Boolean,
  onToggleDescription: (Boolean) -> Unit,
  onBack: () -> Unit,
  onNext: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val cs = MaterialTheme.colorScheme
  Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Text("What goes to Dayfold", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Text(
      "Only the fields you approve are added to Dayfold. Everything else stays in your calendar.",
      style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
    )
    SectionLabel("GOES TO DAYFOLD")
    OutlinedCard(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
      Column(Modifier.padding(vertical = 6.dp)) {
        FieldOnRow("TITLE", proposal.title)
        FieldOnRow("DATE & TIME", proposal.dateTimeLine())
        proposal.timezone?.let { FieldOnRow("TIMEZONE", it) }
        proposal.location?.let { FieldOnRow("LOCATION", it.label + (it.address?.let { a -> ", $a" } ?: "")) }
      }
    }
    SectionLabel("STAYS IN YOUR CALENDAR")
    Surface(shape = RoundedCornerShape(18.dp), color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
      Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (descriptionAvailable) {
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = descriptionIncluded, onCheckedChange = onToggleDescription)
            Text("Event description", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("Add anyway", style = MaterialTheme.typography.labelMedium, color = cs.secondary)
          }
        }
        StayText("Guests & responses")
        StayText("Meeting link")
        StayText("Organizer & account details")
        StayText("Reminder settings")
      }
    }
    Text("Approved fields sync to Dayfold once you confirm.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
      OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Back") }
      Button(onClick = onNext, modifier = Modifier.weight(1.4f)) { Text("Choose who sees it") }
    }
  }
}

@Composable private fun FieldOnRow(label: String, value: String) {
  val cs = MaterialTheme.colorScheme
  Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
    Text(label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
    Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
  }
}

@Composable private fun StayText(label: String) {
  val cs = MaterialTheme.colorScheme
  Text(label, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(vertical = 2.dp))
}

// ============ 25 · AUDIENCE ============
@Composable
fun CalendarImportAudienceNewHubScreen(
  visibility: HubVisibilityChoice,
  familyMemberNames: String,
  onSelect: (HubVisibilityChoice) -> Unit,
  onBack: () -> Unit,
  onNext: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val cs = MaterialTheme.colorScheme
  Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Text("Who sees it", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Text("The new Hub starts private, like your calendar event.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
    AudienceOption(
      icon = DayfoldIcons.Lock, title = "Only me", sub = "Just you can see this Hub. You can share it later.",
      selected = visibility == HubVisibilityChoice.RESTRICTED, onClick = { onSelect(HubVisibilityChoice.RESTRICTED) },
    )
    AudienceOption(
      icon = DayfoldIcons.Groups, title = "Family", sub = familyMemberNames,
      selected = visibility == HubVisibilityChoice.FAMILY, onClick = { onSelect(HubVisibilityChoice.FAMILY) },
    )
    Spacer(Modifier.size(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
      OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Back") }
      Button(onClick = onNext, modifier = Modifier.weight(1.4f)) { Text("Review & confirm") }
    }
  }
}

@Composable private fun AudienceOption(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, selected: Boolean, onClick: () -> Unit) {
  val cs = MaterialTheme.colorScheme
  OutlinedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
    Row(Modifier.padding(15.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Icon(icon, contentDescription = null, tint = if (selected) cs.primary else cs.onSurfaceVariant, modifier = Modifier.size(26.dp))
      Spacer(Modifier.size(12.dp))
      Column(Modifier.weight(1f)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
      }
      RadioButton(selected = selected, onClick = onClick)
    }
  }
}

@Composable
fun CalendarImportAudienceExistingHubScreen(
  hubTitle: String,
  role: String,
  namedAudience: List<String>,
  widerThanSource: Boolean,
  onBack: () -> Unit,
  onNext: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val cs = MaterialTheme.colorScheme
  Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Text("Who sees it", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    OutlinedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
      Row(Modifier.padding(15.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text("Adding to: $hubTitle", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
          Text("You're a ${role.replaceFirstChar { it.uppercase() }} on this Hub", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
      }
    }
    Surface(shape = RoundedCornerShape(18.dp), color = cs.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
      Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
          "This Hub is shared with ${namedAudience.size} ${if (namedAudience.size == 1) "person" else "people"}",
          style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.onTertiaryContainer,
        )
        Text(namedAudience.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = cs.onTertiaryContainer)
      }
    }
    if (widerThanSource) {
      Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(DayfoldIcons.ArrowBack, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Text("Prefer privacy? Go back and pick a new private Hub.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
      OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Back") }
      Button(onClick = onNext, modifier = Modifier.weight(1.4f)) { Text("Got it — review") }
    }
  }
}

// ============ 26 · REVIEW & CONFIRM ============
@Composable
fun CalendarImportConfirmScreen(
  proposal: CalendarImportProposal,
  audienceLine: String,
  confirmLabel: String,
  onBack: () -> Unit,
  onConfirm: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val cs = MaterialTheme.colorScheme
  Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Review & confirm", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    OutlinedCard(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
      Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ProvenanceChip()
        Text(proposal.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(proposal.dateTimeLine(), style = MaterialTheme.typography.bodyMedium)
        proposal.location?.let { Text(it.label + (it.address?.let { a -> ", $a" } ?: ""), style = MaterialTheme.typography.bodyMedium) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Icon(DayfoldIcons.Lock, contentDescription = null, tint = cs.secondary, modifier = Modifier.size(17.dp))
          Text(audienceLine, style = MaterialTheme.typography.bodyMedium)
        }
      }
    }
    Surface(shape = RoundedCornerShape(16.dp), color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
      Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Filled.Info, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Text(
          "${proposal.approvedFieldCount()} fields go to Dayfold. " +
            if (proposal.description == null) "Description, guests and reminders stay in your calendar."
            else "Guests and reminders stay in your calendar.",
          style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
        )
      }
    }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Icon(DayfoldIcons.CalendarMonth, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
      Text("Your calendar stays the schedule — nothing changes there.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
      OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Back") }
      Button(onClick = onConfirm, modifier = Modifier.weight(1.4f)) {
        Icon(Icons.Filled.AddCircle, contentDescription = null, modifier = Modifier.size(19.dp))
        Spacer(Modifier.size(6.dp))
        Text(confirmLabel)
      }
    }
    Text("Nothing is added until you tap this.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
  }
}

@Composable private fun ProvenanceChip() {
  val cs = MaterialTheme.colorScheme
  Surface(shape = RoundedCornerShape(8.dp), color = cs.surface, modifier = Modifier) {
    Text(
      "From your calendar", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
      color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
    )
  }
}

// ============ 27 · APPLY STATES ============
enum class ImportApplyKind { SAVING, SAVED, OFFLINE, PERMISSION_LOST, ROLE_DENIED, SOURCE_CHANGED, VERSION_CONFLICT }

data class ImportApplyAction(val label: String, val primary: Boolean, val onClick: () -> Unit)

@Composable
fun CalendarImportApplyScreen(
  kind: ImportApplyKind,
  title: String,
  meta: String?,
  body: String,
  diffs: List<ImportFieldDiff>,
  footer: String,
  actions: List<ImportApplyAction>,
  modifier: Modifier = Modifier,
) {
  val cs = MaterialTheme.colorScheme
  val (icon, tint) = when (kind) {
    ImportApplyKind.SAVING -> Icons.Filled.CloudOff to cs.onSecondaryContainer
    ImportApplyKind.SAVED -> DayfoldIcons.CheckCircle to cs.secondary
    ImportApplyKind.OFFLINE -> Icons.Filled.CloudOff to cs.onSurfaceVariant
    ImportApplyKind.PERMISSION_LOST -> Icons.Filled.EventBusy to cs.onSurfaceVariant
    ImportApplyKind.ROLE_DENIED -> Icons.Filled.DoNotDisturbOn to cs.onSurfaceVariant
    ImportApplyKind.SOURCE_CHANGED -> Icons.Filled.Update to cs.onTertiaryContainer
    ImportApplyKind.VERSION_CONFLICT -> Icons.Filled.CallMerge to cs.onTertiaryContainer
  }
  Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    OutlinedCard(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
      Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
          Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
          Column {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            meta?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
          }
        }
        Text(body, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          actions.forEach { a ->
            if (a.primary) FilledTonalButton(onClick = a.onClick) { Text(a.label) }
            else OutlinedButton(onClick = a.onClick) { Text(a.label) }
          }
        }
      }
    }
    if (diffs.isNotEmpty()) {
      Surface(shape = RoundedCornerShape(16.dp), color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
          SectionLabel("WHAT CHANGED")
          diffs.forEach { diff ->
            Text(diff.field.uppercase(), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            Text("${diff.before} → ${diff.after}", style = MaterialTheme.typography.bodyMedium)
          }
        }
      }
    }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Icon(Icons.Filled.Info, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
      Text(footer, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
  }
}

@Composable private fun SectionLabel(text: String) {
  Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
