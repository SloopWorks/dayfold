package com.sloopworks.dayfold.client

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sloopworks.dayfold.client.theme.LocalDayfoldColors

// ADR 0044 §1 — the reversible Background-proximity SETTINGS surface (designs/triggers/Settings-Phone).
// Default-off; every knob here is DEVICE-LOCAL and NEVER synced (ADR 0024). Turning it off de-registers
// every geofence and says so ("Geofences removed"), with an async "Removing…" state in between. The
// daily cap is a SEGMENTED budget (1/3/5), never a slider. Live position never leaves the phone.

// Pure: minute-of-day (0..1439) → a 12-hour "H:MM AM/PM" label. Snapshot/property-testable.
fun formatMinuteOfDay(minuteOfDay: Int): String {
  val m = ((minuteOfDay % 1440) + 1440) % 1440
  val h24 = m / 60
  val min = m % 60
  val ampm = if (h24 < 12) "AM" else "PM"
  val h12 = when (h24 % 12) { 0 -> 12; else -> h24 % 12 }
  return "$h12:${min.toString().padStart(2, '0')} $ampm"
}

@Composable
fun ProximitySettingsScreen(
  config: NotifConfig,
  permission: LocationPermission,
  deregistering: Boolean,
  onToggle: (Boolean) -> Unit,
  onPickCap: (Int) -> Unit,
  onEditQuietStart: () -> Unit,
  onEditQuietEnd: () -> Unit,
  onOpenPermission: () -> Unit,
  onPrivacyInfo: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val cs = MaterialTheme.colorScheme
  val on = config.enabled
  Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {

    // device-local banner — the never-synced posture, stated up front.
    Surface(shape = RoundedCornerShape(18.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
      Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTileS(DayfoldIcons.Smartphone, cs.surfaceContainerHigh, cs.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
          Text("These settings live on this phone", style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
          Text("Device-local — never synced to your family account.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
      }
    }

    // main toggle.
    val toggleBg = if (on) cs.secondaryContainer else cs.surfaceContainer
    val toggleFg = if (on) cs.onSecondaryContainer else cs.onSurface
    Surface(shape = RoundedCornerShape(22.dp), color = toggleBg, modifier = Modifier.fillMaxWidth()) {
      Row(Modifier.padding(18.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
          Text("Background proximity", style = MaterialTheme.typography.titleMedium, color = toggleFg)
          Spacer(Modifier.size(4.dp))
          Text(
            if (on) "On — a calm nudge the moment you arrive, even when Dayfold is closed."
            else "Off — nearby items only show while the app is open.",
            style = MaterialTheme.typography.bodySmall, color = toggleFg,
          )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
          checked = on, onCheckedChange = onToggle,
          modifier = Modifier.semantics { contentDescription = "Background proximity" },
        )
      }
    }

    // OFF: de-registration confirmation / async state (privacy teal).
    if (!on) {
      val c = LocalDayfoldColors.current
      Surface(shape = RoundedCornerShape(18.dp), color = c.privacyContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.Top) {
          Icon(if (deregistering) DayfoldIcons.MyLocation else DayfoldIcons.Verified, contentDescription = null, tint = c.onPrivacyContainer, modifier = Modifier.size(22.dp))
          Spacer(Modifier.width(12.dp))
          Column(Modifier.weight(1f)) {
            Text(
              if (deregistering) "Removing geofences…" else "Geofences removed",
              style = MaterialTheme.typography.titleSmall, color = c.onPrivacyContainer,
            )
            Text(
              if (deregistering) "De-registering every saved geofence from the OS."
              else "Turned off. Every saved geofence is de-registered from the OS. You'll still see nearby items when the app is open.",
              style = MaterialTheme.typography.bodySmall, color = c.onPrivacyContainer,
            )
          }
        }
      }
    }

    // controls — active when on, dimmed + inert when off.
    val ctrlAlpha = if (on) 1f else 0.42f
    Column(Modifier.fillMaxWidth().alpha(ctrlAlpha), verticalArrangement = Arrangement.spacedBy(14.dp)) {
      SectionLabel("PERMISSION")

      // current permission row + the on-device privacy line.
      Surface(shape = RoundedCornerShape(18.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
          Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
              .semantics { contentDescription = "Location access. Opens system settings." },
            verticalAlignment = Alignment.CenterVertically,
          ) {
            IconTileS(DayfoldIcons.MyLocation, cs.secondaryContainer, cs.onSecondaryContainer, big = true)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
              Text("Location access", style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
              Text(permission.settingsLabel(), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Icon(DayfoldIcons.ArrowOutward, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
          }
          Spacer(Modifier.size(12.dp))
          HorizontalDivider(color = cs.outlineVariant)
          Spacer(Modifier.size(12.dp))
          val c = LocalDayfoldColors.current
          Row(verticalAlignment = Alignment.Top) {
            Icon(DayfoldIcons.Verified, contentDescription = null, tint = c.onPrivacyContainer, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(9.dp))
            Text(
              "Matched on your device — saved places sync to your family encrypted; your live position stays on this phone.",
              style = MaterialTheme.typography.bodySmall, color = c.onPrivacyContainer,
            )
          }
        }
      }

      SectionLabel("CALM BUDGET")

      // quiet-hours editor.
      Surface(shape = RoundedCornerShape(18.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(DayfoldIcons.Bedtime, contentDescription = null, tint = cs.secondary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
              Text("Quiet hours", style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
              Text("Non-urgent triggers wait until morning.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
          }
          Spacer(Modifier.size(14.dp))
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TimeCell("FROM", formatMinuteOfDay(config.quietStartMinuteOfDay), Modifier.weight(1f), onEditQuietStart)
            Icon(DayfoldIcons.ArrowForward, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
            TimeCell("UNTIL", formatMinuteOfDay(config.quietEndMinuteOfDay), Modifier.weight(1f), onEditQuietEnd)
          }
        }
      }

      // daily-cap chooser — a segmented budget, not a slider.
      Surface(shape = RoundedCornerShape(18.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(DayfoldIcons.FilterList, contentDescription = null, tint = cs.secondary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
              Text("Daily cap", style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
              Text("The most notifications you'll ever get in a day.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
          }
          Spacer(Modifier.size(14.dp))
          val caps = listOf(1, 3, 5)
          SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            caps.forEachIndexed { i, n ->
              SegmentedButton(
                selected = config.dailyCap == n,
                onClick = { onPickCap(n) },
                shape = SegmentedButtonDefaults.itemShape(i, caps.size),
              ) { Text("$n", fontWeight = FontWeight.SemiBold) }
            }
          }
          Spacer(Modifier.size(10.dp))
          Text("Often it's zero. Silence beats spam.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
        }
      }
    }
  }
}

private fun LocationPermission.settingsLabel(): String = when (this) {
  LocationPermission.Always -> "Always · background allowed"
  LocationPermission.WhenInUse -> "While using the app"
  LocationPermission.Denied -> "Off"
}

@Composable
private fun SectionLabel(text: String) {
  Text(
    text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp),
  )
}

@Composable
private fun TimeCell(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
  val cs = MaterialTheme.colorScheme
  Surface(
    shape = RoundedCornerShape(13.dp), color = cs.surfaceContainerHigh,
    onClick = onClick, modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = "$label $value, edit" },
  ) {
    Column(Modifier.padding(vertical = 11.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
      Spacer(Modifier.size(2.dp))
      Text(value, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
    }
  }
}

@Composable
private fun IconTileS(icon: ImageVector, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color, big: Boolean = false) {
  val tile = if (big) 40.dp else 38.dp
  Surface(shape = RoundedCornerShape(if (big) 13.dp else 12.dp), color = bg, modifier = Modifier.size(tile)) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
      Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(if (big) 22.dp else 21.dp))
    }
  }
}
