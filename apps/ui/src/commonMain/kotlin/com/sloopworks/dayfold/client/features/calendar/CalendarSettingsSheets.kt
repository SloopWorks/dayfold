package com.sloopworks.dayfold.client.features.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sloopworks.dayfold.client.CalendarNotificationOwner
import com.sloopworks.dayfold.client.DayfoldIcons

// WI-447 (ADR 0063 §5/§7, Permissions-and-Settings.dc.html §32-34, Calendar-Check-Phone.dc.html
// §30) — confirm/reset/override sheet CONTENT (split from the ModalBottomSheet wrapper so a
// headless snapshot render can show it directly — mirrors AvatarPickerContent's convention). The
// Host wraps each of these in a ModalBottomSheet; snapshot scenes render the Content directly.

@Composable
private fun SheetHandle() {
  Box(
    Modifier.padding(top = 10.dp, bottom = 18.dp).size(width = 34.dp, height = 4.dp)
      .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(3.dp)),
  )
}

// ============ 32 · CHANGE CALENDARS — stop-including confirm ============
@Composable
fun ChangeCalendarsSheetContent(calendarName: String, calendarColor: androidx.compose.ui.graphics.Color, onKeepIt: () -> Unit, onStopIncluding: () -> Unit, modifier: Modifier = Modifier) {
  val cs = MaterialTheme.colorScheme
  Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
    SheetHandle()
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.size(14.dp).background(calendarColor, CircleShape))
      Spacer(Modifier.width(10.dp))
      Text("Stop including $calendarName?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
    }
    Text(
      "Its matches go back to review. Nothing is deleted — you can include it again anytime.",
      style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp, bottom = 22.dp),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
      androidx.compose.material3.FilledTonalButton(onClick = onKeepIt, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Keep it", fontWeight = FontWeight.SemiBold) }
      Button(onClick = onStopIncluding, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Stop including", fontWeight = FontWeight.SemiBold) }
    }
  }
}

// ============ 33 · TURN OFF ============
@Composable
fun TurnOffSheetContent(onCancel: () -> Unit, onTurnOff: () -> Unit, modifier: Modifier = Modifier) {
  val cs = MaterialTheme.colorScheme
  Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
    SheetHandle()
    Text("Turn off Calendar Check?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurface, modifier = Modifier.padding(bottom = 8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      ReassuranceRow("Deletes nothing — calendar and Hubs stay as they are.")
      ReassuranceRow("\"Add to calendar\" keeps working.")
    }
    Spacer(Modifier.size(22.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
      androidx.compose.material3.FilledTonalButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Cancel", fontWeight = FontWeight.SemiBold) }
      Button(onClick = onTurnOff, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Turn off", fontWeight = FontWeight.SemiBold) }
    }
  }
}

@Composable
private fun ReassuranceRow(text: String) {
  val cs = MaterialTheme.colorScheme
  Row(verticalAlignment = Alignment.Top) {
    Icon(DayfoldIcons.Check, contentDescription = null, tint = cs.secondary, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(10.dp))
    Text(text, style = MaterialTheme.typography.bodySmall, color = cs.onSurface)
  }
}

// ============ 34 · RESET LOCAL MATCHES — the ONLY errorContainer use in this feature ============
@Composable
fun ResetLocalMatchesSheetContent(onCancel: () -> Unit, onReset: () -> Unit, modifier: Modifier = Modifier) {
  val cs = MaterialTheme.colorScheme
  Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
    SheetHandle()
    Text("Reset local matches?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurface, modifier = Modifier.padding(bottom = 8.dp))
    Text("This clears, on this phone only:", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 14.dp))
    Surface(shape = RoundedCornerShape(16.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
      Column(Modifier.padding(horizontal = 15.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ClearedRow(DayfoldIcons.Link, "Confirmed matches between Dayfold and your calendar")
        ClearedRow(DayfoldIcons.VisibilityOff, "Items you chose to ignore")
        ClearedRow(Icons.Filled.Sync, "Check history and alert-ownership choices")
      }
    }
    Text(
      "Reviewed items may reappear. Nothing is deleted anywhere.", style = MaterialTheme.typography.bodySmall,
      color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp, bottom = 22.dp),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
      androidx.compose.material3.FilledTonalButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Cancel", fontWeight = FontWeight.SemiBold) }
      Button(
        onClick = onReset, modifier = Modifier.weight(1f).heightIn(min = 52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = cs.errorContainer, contentColor = cs.onErrorContainer),
      ) { Text("Reset", fontWeight = FontWeight.SemiBold) }
    }
  }
}

@Composable
private fun ClearedRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
  val cs = MaterialTheme.colorScheme
  Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(17.dp))
    Spacer(Modifier.width(10.dp))
    Text(text, style = MaterialTheme.typography.bodySmall, color = cs.onSurface)
  }
}

// ============ 30 · PER-MATCH ALERT OVERRIDE ============
@Composable
fun AlertOverrideSheetContent(owner: CalendarNotificationOwner, onPick: (CalendarNotificationOwner) -> Unit, modifier: Modifier = Modifier) {
  val cs = MaterialTheme.colorScheme
  Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
    SheetHandle()
    Text("Event-time alerts for this match", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurface, modifier = Modifier.padding(bottom = 6.dp))
    Text("Who reminds you when it's about to start.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 16.dp))
    Surface(shape = RoundedCornerShape(18.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
      Column {
        OwnerOptionRow(
          selected = owner == CalendarNotificationOwner.CALENDAR, title = "Calendar", isDefault = true,
          sub = "Your calendar app sends the alert.", onClick = { onPick(CalendarNotificationOwner.CALENDAR) },
        )
        HorizontalDivider(color = cs.outlineVariant)
        OwnerOptionRow(
          selected = owner == CalendarNotificationOwner.DAYFOLD, title = "Dayfold", isDefault = false,
          sub = "Dayfold sends it instead.", onClick = { onPick(CalendarNotificationOwner.DAYFOLD) },
        )
      }
    }
    Text(
      "Preparation nudges stay on either way.", style = MaterialTheme.typography.bodySmall,
      color = cs.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
      textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
  }
}

@Composable
private fun OwnerOptionRow(selected: Boolean, title: String, isDefault: Boolean, sub: String, onClick: () -> Unit) {
  val cs = MaterialTheme.colorScheme
  Row(
    Modifier.fillMaxWidth().heightIn(min = 48.dp)
      .selectable(selected = selected, onClick = onClick)
      .padding(horizontal = 14.dp, vertical = 13.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioButton(selected = selected, onClick = onClick)
    Spacer(Modifier.width(9.dp))
    Column(Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
        if (isDefault) {
          Spacer(Modifier.width(6.dp))
          Surface(shape = RoundedCornerShape(999.dp), color = cs.secondaryContainer) {
            Text("Default", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = cs.onSecondaryContainer, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
          }
        }
      }
      Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
  }
}
