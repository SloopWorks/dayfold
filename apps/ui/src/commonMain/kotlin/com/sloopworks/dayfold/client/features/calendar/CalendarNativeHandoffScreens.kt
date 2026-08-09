package com.sloopworks.dayfold.client.features.calendar

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sloopworks.dayfold.client.DayfoldIcons
import com.sloopworks.dayfold.client.ui.loading.rememberReduceMotion

// WI-447 (ADR 0063 §1/§2/§5, Native-Handoff.dc.html §8/§11) — the prefill review sheet + the five
// return states. Stateless/prop-driven: the Host derives phase + labels from real state, never
// invents a value the candidate/observation doesn't have (ADR 0063 §2 honesty guarantee).

// ============ 8 · PREFILL REVIEW SHEET ============
data class PrefillRow(val icon: ImageVector, val label: String, val value: String, val incomplete: Boolean = false)

@Composable
fun PrefillSheetContent(eventTitle: String, rows: List<PrefillRow>, onCancel: () -> Unit, onOpenCalendarApp: () -> Unit, modifier: Modifier = Modifier) {
  val cs = MaterialTheme.colorScheme
  Column(modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp)) {
    Box(Modifier.padding(top = 10.dp, bottom = 16.dp).size(width = 34.dp, height = 4.dp).background(cs.outlineVariant, RoundedCornerShape(3.dp)))
    Text("Add to calendar", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurface, modifier = Modifier.padding(bottom = 4.dp))
    Text(
      "Your calendar app opens with these filled in — you save or cancel there.",
      style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 14.dp),
    )
    Surface(shape = RoundedCornerShape(18.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
      Column {
        rows.forEachIndexed { i, r ->
          if (i > 0) HorizontalDivider(color = cs.outlineVariant)
          val fg = if (r.incomplete) cs.tertiary else cs.onSurface
          Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 13.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(r.icon, contentDescription = null, tint = if (r.incomplete) cs.tertiary else cs.onSurfaceVariant, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
              Text(r.label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
              Text(r.value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = fg)
            }
            if (r.incomplete) {
              Surface(shape = RoundedCornerShape(999.dp), color = cs.tertiaryContainer) {
                Text("Add it there", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = cs.onTertiaryContainer, modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp))
              }
            }
          }
        }
      }
    }
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 16.dp)) {
      Icon(DayfoldIcons.Cancel, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
      Spacer(Modifier.width(8.dp))
      Text("No guests or reminders are prefilled.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
      OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = 50.dp), shape = RoundedCornerShape(999.dp)) { Text("Cancel", fontWeight = FontWeight.SemiBold) }
      Button(onClick = onOpenCalendarApp, modifier = Modifier.weight(1.4f).heightIn(min = 50.dp), shape = RoundedCornerShape(999.dp)) {
        Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(8.dp))
        Text("Open calendar app", fontWeight = FontWeight.SemiBold)
      }
    }
  }
}

// ============ 11 · RETURN STATES ============
enum class CalendarReturnPhase { CHECKING, ADDED, CANCELED, PERMISSION_CHANGED, UNCONFIRMED }

private data class ReturnAction(val label: String, val icon: ImageVector?, val filled: Boolean)
private data class ReturnContent(
  val icon: ImageVector,
  val filled: Boolean,
  val iconBg: Color,
  val iconFg: Color,
  val cardBg: Color,
  val title: String,
  val body: String,
  val metaFg: Color,
  val actions: List<ReturnAction>,
  val animated: Boolean = false,
)

@Composable
private fun returnContent(phase: CalendarReturnPhase, matchedMeta: String?): ReturnContent {
  val cs = MaterialTheme.colorScheme
  val privacy = com.sloopworks.dayfold.client.theme.LocalDayfoldColors.current
  return when (phase) {
    CalendarReturnPhase.CHECKING -> ReturnContent(
      Icons.Filled.Search, false, cs.secondaryContainer, cs.onSecondaryContainer, cs.surfaceContainer,
      "Checking your calendar…", "Dayfold is looking for the event before it says anything is added.",
      cs.onSurfaceVariant, emptyList(), animated = true,
    )
    CalendarReturnPhase.ADDED -> ReturnContent(
      Icons.Filled.EventAvailable, true, privacy.privacyContainer, privacy.onPrivacyContainer, privacy.privacyContainer,
      "On your calendar", (matchedMeta?.let { "This Hub and the event are now linked on this phone. Calendar handles event alerts." } ?: "This Hub and the event are now linked on this phone. Calendar handles event alerts."),
      privacy.onPrivacyContainer,
      listOf(ReturnAction("Open in Calendar", Icons.Filled.OpenInNew, filled = false), ReturnAction("Unlink", null, filled = false)),
    )
    CalendarReturnPhase.CANCELED -> ReturnContent(
      Icons.Filled.Undo, false, cs.surfaceContainerHigh, cs.onSurfaceVariant, cs.surfaceContainer,
      "Nothing was added", "The editor closed without saving — that's fine. The Hub is unchanged.",
      cs.onSurfaceVariant, listOf(ReturnAction("Add to calendar", DayfoldIcons.CalendarMonth, filled = false)),
    )
    CalendarReturnPhase.PERMISSION_CHANGED -> ReturnContent(
      Icons.Filled.EventBusy, false, cs.surfaceContainerHigh, cs.onSurfaceVariant, cs.surfaceContainer,
      "Calendar access changed",
      "Calendar access turned off while you were away, so Dayfold can't confirm the save. If you saved it, it's in your calendar.",
      cs.onSurfaceVariant,
      listOf(ReturnAction("Allow in phone settings", Icons.Filled.Settings, filled = false), ReturnAction("Keep access off", null, filled = false)),
    )
    CalendarReturnPhase.UNCONFIRMED -> ReturnContent(
      Icons.Filled.HelpOutline, false, cs.tertiaryContainer, cs.onTertiaryContainer, cs.surfaceContainer,
      "We couldn't confirm it yet", "The calendar hasn't shown the event yet — it may still be syncing.",
      cs.onSurfaceVariant,
      listOf(ReturnAction("Check again", Icons.Filled.Refresh, filled = false), ReturnAction("I didn't save it", null, filled = false)),
    )
  }
}

@Composable
fun CalendarReturnScreen(
  phase: CalendarReturnPhase,
  hubTitle: String,
  dateLabel: String,
  timeRangeLabel: String,
  locationLabel: String? = null,
  matchedCalendarName: String? = null,
  onBack: () -> Unit,
  onPrimaryAction: () -> Unit = {},
  onSecondaryAction: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val cs = MaterialTheme.colorScheme
  val content = returnContent(phase, matchedCalendarName)
  val reduceMotion = rememberReduceMotion()
  Column(modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      Icon(DayfoldIcons.ArrowBack, contentDescription = "Back", tint = cs.onSurface, modifier = Modifier.size(25.dp))
      Spacer(Modifier.width(14.dp))
      Text(hubTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
      Surface(shape = RoundedCornerShape(22.dp), color = content.cardBg, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
            val iconAlpha = if (content.animated && !reduceMotion) {
              val transition = rememberInfiniteTransition(label = "checking")
              val a by transition.animateFloat(0.5f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "checkingAlpha")
              a
            } else 1f
            Surface(shape = RoundedCornerShape(15.dp), color = content.iconBg, modifier = Modifier.size(46.dp).alpha(iconAlpha)) {
              Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Icon(content.icon, contentDescription = null, tint = content.iconFg, modifier = Modifier.size(24.dp)) }
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
              Text(content.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
              matchedCalendarName?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = content.metaFg, modifier = Modifier.padding(top = 3.dp)) }
              if (phase == CalendarReturnPhase.CHECKING) Text("Just a moment", style = MaterialTheme.typography.bodySmall, color = content.metaFg, modifier = Modifier.padding(top = 3.dp))
            }
          }
          Text(content.body, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 14.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            content.actions.forEachIndexed { i, a ->
              val onClick = if (i == 0) onPrimaryAction else onSecondaryAction
              if (a.filled) {
                FilledTonalButton(onClick = onClick) { a.icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)) }; Text(a.label, fontWeight = FontWeight.SemiBold) }
              } else {
                OutlinedButton(onClick = onClick) { a.icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)) }; Text(a.label, fontWeight = FontWeight.SemiBold) }
              }
            }
          }
        }
      }
      Surface(shape = RoundedCornerShape(20.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(top = 18.dp).alpha(0.65f)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
          Icon(DayfoldIcons.CalendarMonth, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(24.dp))
          Spacer(Modifier.width(13.dp))
          Column {
            Text(dateLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
            Text(timeRangeLabel + (locationLabel?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
          }
        }
      }
    }
  }
}
