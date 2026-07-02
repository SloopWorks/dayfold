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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sloopworks.dayfold.client.theme.LocalDayfoldColors

// ADR 0044 Phase B · S2 — the two calm "non-event" in-app notification states (designs/triggers/
// Notifications §2b). Neither uses a badge, a count-as-urgency, or a "you missed out" frame.
//  • QuietHoursHeldCard — quiet hours HOLD non-urgent triggers (held, never dropped) until morning.
//  • CapReachedState — once the daily cap is met, Dayfold goes silent and points back into the app.
// Both surface inside Now (the foreground projection of selectNotifications' held/capped buckets).

/** Quiet-hours held: non-urgent triggers are waiting for morning. [heldCount] = NotifPlan.held.size. */
@Composable
fun QuietHoursHeldCard(untilLabel: String, heldCount: Int, modifier: Modifier = Modifier) {
  val cs = MaterialTheme.colorScheme
  val c = LocalDayfoldColors.current
  Surface(shape = RoundedCornerShape(22.dp), color = cs.surfaceContainer, modifier = modifier.fillMaxWidth()) {
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
      Surface(shape = RoundedCornerShape(13.dp), color = c.privacyContainer, modifier = Modifier.size(42.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
          Icon(DayfoldIcons.Bedtime, contentDescription = null, tint = c.onPrivacyContainer, modifier = Modifier.size(22.dp))
        }
      }
      Spacer(Modifier.width(13.dp))
      Column(Modifier.weight(1f)) {
        Text("Quiet until $untilLabel", style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
        Text(
          if (heldCount == 1) "1 non-urgent reminder is waiting for morning."
          else "$heldCount non-urgent reminders are waiting for morning.",
          style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
        )
      }
    }
  }
}

/** Daily cap reached: a calm done-state, not "you missed out". [cap] = NotifConfig.dailyCap. */
@Composable
fun CapReachedState(cap: Int, onOpenApp: () -> Unit, modifier: Modifier = Modifier) {
  val cs = MaterialTheme.colorScheme
  Surface(shape = RoundedCornerShape(30.dp), color = cs.surface, modifier = modifier.fillMaxWidth()) {
    Column(Modifier.padding(24.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
      Surface(shape = RoundedCornerShape(20.dp), color = cs.secondaryContainer, modifier = Modifier.size(60.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
          Icon(DayfoldIcons.Verified, contentDescription = null, tint = cs.onSecondaryContainer, modifier = Modifier.size(32.dp))
        }
      }
      Spacer(Modifier.size(14.dp))
      Text("You're all caught up", style = MaterialTheme.typography.titleLarge, color = cs.onSurface)
      Spacer(Modifier.size(6.dp))
      Text(
        "That's today's few. Anything else that matches is waiting quietly in the app — no more pings today.",
        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
      )
      Spacer(Modifier.size(16.dp))
      Button(onClick = onOpenApp, modifier = Modifier.heightIn(min = 48.dp)) {
        Text("Open the app", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(7.dp))
        Icon(DayfoldIcons.ArrowOutward, contentDescription = null, modifier = Modifier.size(16.dp))
      }
      Spacer(Modifier.size(14.dp))
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(DayfoldIcons.FilterList, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(15.dp))
        Text(
          "$cap of $cap today · cap resets at midnight",
          style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
          modifier = Modifier.semantics { contentDescription = "$cap of $cap notifications today. Cap resets at midnight." },
        )
      }
    }
  }
}
