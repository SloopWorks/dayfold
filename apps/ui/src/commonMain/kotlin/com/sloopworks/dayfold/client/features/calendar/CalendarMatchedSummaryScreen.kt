package com.sloopworks.dayfold.client.features.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sloopworks.dayfold.client.DayfoldIcons
import com.sloopworks.dayfold.client.theme.LocalDayfoldColors

// WI-446 (ADR 0063 §5, designs/calendar-reconciliation/Calendar-Check-Phone.dc.html §28) — the
// matched-summary screen for an already-linked subject. Fully stateless/prop-driven (mirrors
// CalendarReturnScreen, WI-447): a matched-and-unchanged subject has NO redux slice of its own —
// state.calendar.check.results deliberately excludes it (CalendarReconciler.kt's doc comment:
// "matched-and-unchanged subjects appear in neither review bucket"), and the local CalendarBinding
// carries only mechanical fields, never title/date. A future WI supplies real values from wherever
// this screen is entered (e.g. a Hub's linked-event affordance) — same deferred-nav posture as the
// rest of this epic pending ADR 0063 acceptance.

data class MatchedChecklistItem(val label: String, val done: Boolean)

@Composable
fun CalendarMatchedSummaryScreen(
  hubTitle: String,
  monthAbbrev: String,
  dayNumber: String,
  dateLabel: String,
  timeLocationLabel: String,
  calendarName: String,
  calendarDotColor: String?,
  lastCheckedLabel: String,
  onBack: () -> Unit,
  onOpenInCalendar: () -> Unit,
  onUnlink: () -> Unit,
  checklistTitle: String? = null,
  checklistItems: List<MatchedChecklistItem> = emptyList(),
  modifier: Modifier = Modifier,
) {
  val cs = MaterialTheme.colorScheme
  val privacy = LocalDayfoldColors.current
  Column(modifier.fillMaxWidth()) {
    Row(
      Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onBack).padding(horizontal = 8.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(DayfoldIcons.ArrowBack, contentDescription = "Back", tint = cs.onSurface, modifier = Modifier.size(25.dp))
      Spacer(Modifier.width(14.dp))
      Text(hubTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
      Surface(shape = RoundedCornerShape(20.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
          Surface(shape = RoundedCornerShape(14.dp), color = cs.primaryContainer, modifier = Modifier.size(44.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
              Spacer(Modifier.size(6.dp))
              Text(monthAbbrev.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = cs.onPrimaryContainer)
              Text(dayNumber, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = cs.onPrimaryContainer)
            }
          }
          Spacer(Modifier.width(13.dp))
          Column(Modifier.weight(1f)) {
            Text(dateLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
            Text(timeLocationLabel, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
          }
        }
      }
      // The quiet matched card — outlined, never elevated: a settled state, not something to act on.
      Surface(
        shape = RoundedCornerShape(20.dp), color = cs.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, cs.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
      ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 15.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.EventAvailable, contentDescription = null, tint = cs.secondary, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(11.dp))
            Text("On your calendar", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurface, modifier = Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              Box(Modifier.size(9.dp).background(colorFromHex(calendarDotColor), CircleShape))
              Text(calendarName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
            }
          }
          Text(lastCheckedLabel, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenInCalendar) {
              Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(6.dp))
              Text("Open in Calendar", fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = onUnlink) { Text("Unlink", fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant) }
          }
        }
      }
      Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
        Icon(Icons.Filled.NotificationsNone, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Text("Calendar handles the \"starts soon\" alert.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
      }
      if (checklistTitle != null && checklistItems.isNotEmpty()) {
        Spacer(Modifier.size(4.dp))
        Surface(shape = RoundedCornerShape(20.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
          Column(Modifier.padding(16.dp)) {
            Text(checklistTitle, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
            checklistItems.forEachIndexed { i, c ->
              if (i > 0) HorizontalDivider(color = cs.outlineVariant, modifier = Modifier.padding(vertical = 8.dp))
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ChecklistDot(c.done)
                Text(
                  c.label, style = MaterialTheme.typography.bodyMedium,
                  color = if (c.done) cs.onSurfaceVariant else cs.onSurface,
                  textDecoration = if (c.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                )
              }
            }
          }
        }
      }
      Spacer(Modifier.size(20.dp))
    }
  }
}

@Composable
private fun ChecklistDot(done: Boolean) {
  val cs = MaterialTheme.colorScheme
  if (done) {
    Surface(shape = RoundedCornerShape(7.dp), color = cs.secondary, modifier = Modifier.size(20.dp)) {
      Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Icon(DayfoldIcons.Check, contentDescription = null, tint = cs.onSecondary, modifier = Modifier.size(13.dp)) }
    }
  } else {
    Box(Modifier.size(20.dp).border(androidx.compose.foundation.BorderStroke(2.dp, cs.outline), RoundedCornerShape(7.dp)))
  }
}
