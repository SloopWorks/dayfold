package com.sloopworks.dayfold.client.features.calendar

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.BusinessCenter
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material.icons.filled.ToggleOff
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sloopworks.dayfold.client.DayfoldIcons
import com.sloopworks.dayfold.client.DeviceCalendar
import com.sloopworks.dayfold.client.theme.LocalDayfoldColors

// WI-447 (ADR 0063 §1/§2, designs/calendar-reconciliation/Permissions-and-Settings.dc.html §1/2/4/
// 5/6/31) — the settings/primer/chooser/denied/empty/on screens. Stateless: every value is a
// param, every action a callback (mirrors ProximitySettingsScreen) — the Host owns store/dispatch.

private data class InfoRow(val icon: ImageVector, val title: String, val sub: String, val tint: Color, val bg: Color)

@Composable
private fun CalendarTopBar(title: String, leadingIcon: ImageVector = DayfoldIcons.ArrowBack, onBack: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onBack).padding(horizontal = 8.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(leadingIcon, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(25.dp))
    Spacer(Modifier.width(14.dp))
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
  }
}

@Composable
private fun HeroIcon(icon: ImageVector, bg: Color, fg: Color) {
  Surface(shape = RoundedCornerShape(26.dp), color = bg, modifier = Modifier.size(84.dp)) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
      Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(42.dp))
    }
  }
}

@Composable
private fun StatusChip(icon: ImageVector, text: String) {
  val cs = MaterialTheme.colorScheme
  Surface(shape = RoundedCornerShape(999.dp), color = cs.surfaceContainerHigh) {
    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      Icon(icon, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(15.dp))
      Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
    }
  }
}

@Composable
private fun InfoRowItem(row: InfoRow) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
    Surface(shape = RoundedCornerShape(12.dp), color = row.bg, modifier = Modifier.size(38.dp)) {
      Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Icon(row.icon, contentDescription = null, tint = row.tint, modifier = Modifier.size(20.dp))
      }
    }
    Spacer(Modifier.width(12.dp))
    Column(Modifier.padding(top = 1.dp)) {
      Text(row.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
      Text(row.sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun PrivacyPanel(text: String = "Just for you — your calendar details stay on this device.") {
  val c = LocalDayfoldColors.current
  Surface(shape = RoundedCornerShape(16.dp), color = c.privacyContainer, modifier = Modifier.fillMaxWidth()) {
    Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
      Icon(DayfoldIcons.Verified, contentDescription = null, tint = c.onPrivacyContainer, modifier = Modifier.size(24.dp))
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)) {
        Text("Compared on this phone", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = c.onPrivacyContainer)
        Text(text, style = MaterialTheme.typography.bodySmall, color = c.onPrivacyContainer)
      }
    }
  }
}

// ============ 1 · SETTINGS — OFF ============
@Composable
fun CalendarSettingsOffScreen(onBack: () -> Unit, onSetUp: () -> Unit, modifier: Modifier = Modifier) {
  val cs = MaterialTheme.colorScheme
  Column(modifier.fillMaxWidth()) {
    CalendarTopBar("Calendar Check", onBack = onBack)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
      HeroIcon(Icons.Filled.EventAvailable, cs.secondaryContainer, cs.onSecondaryContainer)
      Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        StatusChip(Icons.Filled.ToggleOff, "Off")
        Text(
          "Check two lists before a busy week", style = MaterialTheme.typography.headlineMedium,
          fontWeight = FontWeight.SemiBold, color = cs.onSurface,
        )
      }
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        InfoRowItem(InfoRow(Icons.Filled.Difference, "Spot gaps both ways", "In Dayfold but not on your calendar — or the reverse.", cs.onPrimaryContainer, cs.primaryContainer))
        InfoRowItem(InfoRow(DayfoldIcons.Checklist, "One calm review", "Never a notification, badge, or nag.", cs.onSecondaryContainer, cs.secondaryContainer))
        InfoRowItem(InfoRow(DayfoldIcons.CalendarMonth, "Calendar stays in charge", "It keeps the schedule and event alerts. Dayfold never writes by itself.", cs.onTertiaryContainer, cs.tertiaryContainer))
      }
      PrivacyPanel()
      Button(onClick = onSetUp, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(999.dp)) {
        Icon(Icons.Filled.Difference, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text("Set up Calendar Check", fontWeight = FontWeight.SemiBold)
      }
      Text(
        "\"Add to calendar\" on Hubs works without this.", style = MaterialTheme.typography.bodySmall,
        color = cs.onSurfaceVariant, modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

// ============ 2 · READ-ACCESS PRIMER ============
@Composable
fun CalendarPrimerScreen(onNotNow: () -> Unit, onContinue: () -> Unit, modifier: Modifier = Modifier) {
  val cs = MaterialTheme.colorScheme
  Column(modifier.fillMaxWidth()) {
    CalendarTopBar("Calendar Check", leadingIcon = Icons.Filled.Close, onBack = onNotNow)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(shape = RoundedCornerShape(22.dp), color = cs.primaryContainer, modifier = Modifier.size(66.dp)) {
          Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Icon(DayfoldIcons.Event, contentDescription = null, tint = cs.onPrimaryContainer, modifier = Modifier.size(32.dp)) }
        }
        Icon(Icons.Filled.SyncAlt, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(24.dp))
        Surface(shape = RoundedCornerShape(22.dp), color = cs.secondaryContainer, modifier = Modifier.size(66.dp)) {
          Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Icon(DayfoldIcons.CalendarMonth, contentDescription = null, tint = cs.onSecondaryContainer, modifier = Modifier.size(32.dp)) }
        }
      }
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Compare with your calendar?", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
        Text("Dayfold reads events from calendars you pick.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
      }
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        InfoRowItem(InfoRow(DayfoldIcons.Event, "Reads titles, dates and times", "Of upcoming events, on this phone.", cs.onPrimaryContainer, cs.primaryContainer))
        InfoRowItem(InfoRow(DayfoldIcons.Checklist, "Shows what's only in one place", "Compared against your Dayfold plans.", cs.onSecondaryContainer, cs.secondaryContainer))
        InfoRowItem(InfoRow(DayfoldIcons.Cancel, "Never changes anything itself", "You confirm everything.", cs.onTertiaryContainer, cs.tertiaryContainer))
      }
      Surface(shape = RoundedCornerShape(16.dp), color = LocalDayfoldColors.current.privacyContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
          Icon(DayfoldIcons.Verified, contentDescription = null, tint = LocalDayfoldColors.current.onPrivacyContainer, modifier = Modifier.size(24.dp))
          Spacer(Modifier.width(12.dp))
          Text(
            "Your calendar details stay on this device", style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold, color = LocalDayfoldColors.current.onPrivacyContainer,
          )
        }
      }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        OutlinedButton(onClick = onNotNow, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(999.dp)) { Text("Not now", fontWeight = FontWeight.SemiBold) }
        Button(onClick = onContinue, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(999.dp)) { Text("Continue", fontWeight = FontWeight.SemiBold) }
      }
      Text(
        "Your phone will ask for calendar access next.", style = MaterialTheme.typography.labelSmall,
        color = cs.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(top = 9.dp),
      )
    }
  }
}

// ============ 4 · CALENDAR CHOOSER ============
@Composable
fun CalendarChooserScreen(
  groups: List<CalendarAccountGroup>,
  selectedIds: Set<String>,
  onBack: () -> Unit,
  onToggle: (String) -> Unit,
  onIncludeSelected: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val cs = MaterialTheme.colorScheme
  val includedCount = groups.sumOf { g -> g.calendars.count { it.id in selectedIds } }
  Column(modifier.fillMaxWidth()) {
    CalendarTopBar("Choose calendars", onBack = onBack)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
      Text("Dayfold compares only the calendars you include.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
      groups.forEach { group ->
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
          Text(group.accountLabel.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
          Surface(shape = RoundedCornerShape(18.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column {
              group.calendars.forEachIndexed { i, cal ->
                if (i > 0) HorizontalDivider(color = cs.outlineVariant)
                val included = cal.id in selectedIds
                Row(
                  Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onToggle(cal.id) }
                    .padding(horizontal = 14.dp, vertical = 4.dp)
                    .semantics { contentDescription = "${cal.displayName}. ${if (included) "Included" else "Not included"}." },
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Box(Modifier.size(14.dp).background(colorFromHex(cal.color), androidx.compose.foundation.shape.CircleShape))
                  Spacer(Modifier.width(13.dp))
                  Column(Modifier.weight(1f)) {
                    Text(cal.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                    Text(if (included) "Included" else "Not included", style = MaterialTheme.typography.bodySmall, color = if (included) cs.secondary else cs.onSurfaceVariant)
                  }
                  Checkbox(checked = included, onCheckedChange = { onToggle(cal.id) })
                }
              }
            }
          }
        }
      }
      Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Icon(DayfoldIcons.VisibilityOff, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(17.dp))
        Text("Calendars you leave off are never read.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
      }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
      Button(
        onClick = onIncludeSelected, enabled = includedCount > 0,
        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(999.dp),
      ) { Text(if (includedCount == 1) "Include 1 calendar" else "Include $includedCount calendars", fontWeight = FontWeight.SemiBold) }
      Text(
        "You can change this anytime in Settings.", style = MaterialTheme.typography.labelSmall,
        color = cs.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(top = 9.dp),
      )
    }
  }
}

internal fun colorFromHex(hex: String?): Color = runCatching {
  hex?.removePrefix("#")?.let { Color(("FF$it").toLong(16)) }
}.getOrNull() ?: Color(0xFF8A8F98)

// ============ 5 · DENIED / REVOKED ============
@Composable
fun CalendarDeniedScreen(onBack: () -> Unit, onOpenSettings: () -> Unit, onKeepOff: () -> Unit, modifier: Modifier = Modifier) {
  val cs = MaterialTheme.colorScheme
  Column(modifier.fillMaxWidth()) {
    CalendarTopBar("Calendar Check", onBack = onBack)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
      StatusChip(Icons.Filled.EventBusy, "Calendar access · off")
      HeroIcon(DayfoldIcons.PauseCircle, cs.surfaceContainerHigh, cs.onSurfaceVariant)
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Calendar Check is paused", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
        Text(
          "Your phone isn't sharing calendars with Dayfold — and that's a fine way to leave it.",
          style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
        )
      }
      Surface(shape = RoundedCornerShape(18.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.Top) {
          Icon(DayfoldIcons.CalendarMonth, contentDescription = null, tint = cs.secondary, modifier = Modifier.size(22.dp))
          Spacer(Modifier.width(12.dp))
          Text("\"Add to calendar\" still works — no read access needed.", style = MaterialTheme.typography.bodySmall, color = cs.onSurface)
        }
      }
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
          onClick = onOpenSettings,
          colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = cs.secondaryContainer, contentColor = cs.onSecondaryContainer),
          modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(999.dp),
        ) {
          Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(19.dp))
          Spacer(Modifier.width(8.dp))
          Text("Allow in phone settings", fontWeight = FontWeight.SemiBold)
        }
        TextButton(onClick = onKeepOff, modifier = Modifier.fillMaxWidth()) { Text("Keep it off", fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant) }
      }
    }
  }
}

// ============ 6 · NO CALENDARS / RESTRICTED / PROVIDER UNAVAILABLE ============
@Composable
fun CalendarNoCalendarsScreen(onBack: () -> Unit, onDoneForNow: () -> Unit, modifier: Modifier = Modifier) {
  val cs = MaterialTheme.colorScheme
  Column(modifier.fillMaxWidth()) {
    CalendarTopBar("Choose calendars", onBack = onBack)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
      HeroIcon(DayfoldIcons.CalendarMonth, cs.surfaceContainerHigh, cs.onSurfaceVariant)
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("No calendars to compare", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
        Text("This phone isn't showing any calendars Dayfold can read. Usually that means:", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
      }
      Surface(shape = RoundedCornerShape(18.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column {
          val reasons = listOf(
            Icons.Filled.AccountCircle to "No calendar account is syncing to this phone yet.",
            Icons.Filled.Work to "A work profile keeps its calendars private — by design.",
            Icons.Filled.SyncProblem to "The calendar provider is temporarily unavailable — try again later.",
          )
          reasons.forEachIndexed { i, (icon, text) ->
            if (i > 0) HorizontalDivider(color = cs.outlineVariant)
            Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.Top) {
              Icon(icon, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
              Spacer(Modifier.width(12.dp))
              Text(text, style = MaterialTheme.typography.bodySmall, color = cs.onSurface)
            }
          }
        }
      }
      OutlinedButton(onClick = onDoneForNow, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(999.dp)) {
        Text("Done for now", fontWeight = FontWeight.SemiBold)
      }
      Text(
        "Calendar Check stays ready — nothing is broken.", style = MaterialTheme.typography.labelSmall,
        color = cs.onSurfaceVariant, modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

// ============ 31 · SETTINGS — ON ============
@Composable
fun CalendarSettingsOnScreen(
  includedCalendars: List<DeviceCalendar>,
  lastCheckLabel: String,
  onBack: () -> Unit,
  onToggleOff: () -> Unit,
  onChangeCalendars: () -> Unit,
  onEventTimeAlerts: (() -> Unit)?,
  onResetLocalMatches: () -> Unit,
  onTurnOff: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val cs = MaterialTheme.colorScheme
  Column(modifier.fillMaxWidth()) {
    CalendarTopBar("Calendar Check", onBack = onBack)
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Surface(shape = RoundedCornerShape(18.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = cs.secondaryContainer, modifier = Modifier.size(40.dp)) {
              Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Icon(Icons.Filled.EventAvailable, contentDescription = null, tint = cs.onSecondaryContainer, modifier = Modifier.size(21.dp)) }
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
              Text("Calendar Check", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
              Text("On · for you, on this phone", style = MaterialTheme.typography.bodySmall, color = cs.secondary)
            }
            androidx.compose.material3.Switch(checked = true, onCheckedChange = { onToggleOff() }, modifier = Modifier.semantics { contentDescription = "Calendar Check" })
          }
          HorizontalDivider(color = cs.outlineVariant, modifier = Modifier.padding(top = 12.dp, bottom = 12.dp))
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(DayfoldIcons.Verified, contentDescription = null, tint = LocalDayfoldColors.current.onPrivacyContainer, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            Text("Your calendar details stay on this device.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
          }
        }
      }
      Text("CALENDARS INCLUDED", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
      Surface(shape = RoundedCornerShape(18.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column {
          includedCalendars.forEach { cal ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
              Box(Modifier.size(14.dp).background(colorFromHex(cal.color), androidx.compose.foundation.shape.CircleShape))
              Spacer(Modifier.width(13.dp))
              Column(Modifier.weight(1f)) {
                Text(cal.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                Text(cal.accountLabel, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
              }
            }
            HorizontalDivider(color = cs.outlineVariant)
          }
          SettingsRowTinted(DayfoldIcons.Tune, "Change calendars", cs.primary, onChangeCalendars)
        }
      }
      Surface(shape = RoundedCornerShape(18.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column {
          SettingsRow(Icons.Filled.Sync, "Last check", lastCheckLabel + " · on this phone", onClick = null)
          HorizontalDivider(color = cs.outlineVariant)
          SettingsRow(DayfoldIcons.Verified, "Event-time alerts", "Calendar handles event alerts for matched events", onEventTimeAlerts)
        }
      }
      Surface(shape = RoundedCornerShape(18.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column {
          SettingsRow(Icons.Filled.SyncAlt, "Reset local matches", "Clears matches and ignores on this phone", onResetLocalMatches)
          HorizontalDivider(color = cs.outlineVariant)
          SettingsRow(Icons.Filled.ToggleOff, "Turn off Calendar Check", onTurnOff)
        }
      }
    }
  }
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, onClick: (() -> Unit)?) {
  val cs = MaterialTheme.colorScheme
  Row(
    Modifier.fillMaxWidth().heightIn(min = 48.dp)
      .let { if (onClick != null) it.clickable(onClick = onClick) else it }
      .padding(horizontal = 14.dp, vertical = 13.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(icon, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(21.dp))
    Spacer(Modifier.width(13.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurface, modifier = Modifier.weight(1f))
    if (onClick != null) Icon(DayfoldIcons.ChevronRight, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(21.dp))
  }
}

@Composable
private fun SettingsRowTinted(icon: ImageVector, title: String, tint: Color, onClick: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 13.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    Spacer(Modifier.width(13.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = tint, modifier = Modifier.weight(1f))
    Icon(DayfoldIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(21.dp))
  }
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, sub: String, onClick: (() -> Unit)?) {
  val cs = MaterialTheme.colorScheme
  Row(
    Modifier.fillMaxWidth().heightIn(min = 48.dp)
      .let { if (onClick != null) it.clickable(onClick = onClick) else it }
      .padding(horizontal = 14.dp, vertical = 13.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(icon, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(21.dp))
    Spacer(Modifier.width(13.dp))
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
      Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
    if (onClick != null) Icon(DayfoldIcons.ChevronRight, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(21.dp))
  }
}
