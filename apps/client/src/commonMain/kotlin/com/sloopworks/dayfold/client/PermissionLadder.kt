package com.sloopworks.dayfold.client

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sloopworks.dayfold.client.theme.LocalDayfoldColors

// ADR 0044 §1 — the opt-in PERMISSION LADDER (designs/triggers/Permission-Phone). Progressive +
// reversible: location while-using is primed before background "Always"; notifications are a separate
// axis; declining is always a FULL-COLOR PEER (never a disabled/greyed "Not now"); the granted-but-
// limited and denied states are calm, never broken. The on-device honesty promise rides every priming
// screen (only your LIVE position stays on this phone; saved places sync encrypted).
//
// Icon note: a few Material-Symbols glyphs in the mockup (pin_drop/storefront/school/touch_app/
// notifications_off/battery_5_bar/cached) reuse the nearest shipped DayfoldIcon — the load-bearing
// fidelity (copy, color, the peer-button posture, the promise) is exact; the decorative glyph is close.

enum class PermissionPrompt { LocPrime, AlwaysUpgrade, NotifPrime, Limited, Denied, Downgraded }

private data class Reason(val icon: ImageVector, val title: String, val sub: String)

@Composable
fun PermissionLadderScreen(
  prompt: PermissionPrompt,
  onPrimary: () -> Unit,
  onSecondary: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier.fillMaxWidth()) {
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).fillMaxWidth().padding(horizontal = 20.dp)) {
      when (prompt) {
        PermissionPrompt.LocPrime, PermissionPrompt.AlwaysUpgrade, PermissionPrompt.NotifPrime -> PrimingBody(prompt)
        PermissionPrompt.Limited -> LimitedBody()
        PermissionPrompt.Downgraded -> DowngradedBody()
        PermissionPrompt.Denied -> DeniedBody()
      }
      Spacer(Modifier.height(16.dp))
    }
    PermissionFooter(prompt, onPrimary, onSecondary)
  }
}

@Composable
private fun PrimingBody(prompt: PermissionPrompt) {
  val cs = MaterialTheme.colorScheme
  val cfg = when (prompt) {
    PermissionPrompt.AlwaysUpgrade -> PrimingCfg(
      kicker = "OPTIONAL UPGRADE · BACKGROUND", heroIcon = DayfoldIcons.MyLocation,
      title = "A calm nudge the moment you arrive",
      body = "Today Dayfold only checks while it's open. Allowing “Always” lets a place reminder reach you the moment you walk in — without opening the app first.",
      reasons = emptyList(),
      promiseTitle = "Still matched on your device",
      promiseBody = "Background matching runs on this phone too. Saved places stay encrypted; your live position is never uploaded.",
    )
    PermissionPrompt.NotifPrime -> PrimingCfg(
      kicker = "NOTIFICATIONS", heroIcon = DayfoldIcons.NotificationsActive,
      title = "A few nudges — never a stream",
      body = "We'll only notify you when a trigger genuinely matters, and we cap it hard. No badges, no streaks, no engagement games.",
      reasons = listOf(
        Reason(DayfoldIcons.FilterList, "At most 3 a day", "A real budget. Often it's zero."),
        Reason(DayfoldIcons.Bedtime, "Quiet overnight", "Non-urgent things wait until morning."),
      ),
      promiseTitle = "You're always in control",
      promiseBody = "Turn any category off in a tap — and they stay off.",
    )
    else -> PrimingCfg(
      kicker = "LOCATION · WHILE USING THE APP", heroIcon = DayfoldIcons.MyLocation,
      title = "See what's nearby while Dayfold is open",
      body = "When the app is open, Dayfold surfaces the right list or reminder for a place you've saved — the store, school, home. You'll see it in your feed while you're looking.",
      reasons = listOf(
        Reason(DayfoldIcons.Location, "Open at the store", "The party list, right there in your feed."),
        Reason(DayfoldIcons.Event, "Checking before pickup", "The permission slip, when you look."),
      ),
      promiseTitle = "Matched on your device",
      promiseBody = "Saved places sync to your family, encrypted. Your live position stays on this phone.",
    )
  }

  Spacer(Modifier.height(6.dp))
  // hero — the big icon tile on a calm container.
  Surface(shape = RoundedCornerShape(30.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth().height(180.dp)) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
      Surface(shape = RoundedCornerShape(28.dp), color = cs.primary, modifier = Modifier.size(92.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
          Icon(cfg.heroIcon, contentDescription = null, tint = cs.onPrimary, modifier = Modifier.size(46.dp))
        }
      }
    }
  }
  Spacer(Modifier.height(20.dp))
  Text(cfg.kicker, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = cs.secondary)
  Spacer(Modifier.height(6.dp))
  Text(cfg.title, style = MaterialTheme.typography.headlineSmall, color = cs.onSurface)
  Spacer(Modifier.height(8.dp))
  Text(cfg.body, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
  Spacer(Modifier.height(16.dp))
  cfg.reasons.forEach { r ->
    Row(Modifier.fillMaxWidth().padding(bottom = 11.dp), verticalAlignment = Alignment.Top) {
      IconTile(r.icon, cs.surfaceContainerHigh, cs.secondary)
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)) {
        Text(r.title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
        Text(r.sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
      }
    }
  }

  // the honest tradeoff panel — Always only.
  if (prompt == PermissionPrompt.AlwaysUpgrade) {
    Surface(shape = RoundedCornerShape(18.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
      Column(Modifier.padding(16.dp)) {
        Text("HONEST ABOUT THE TRADE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(11.dp))
        TradeRow(DayfoldIcons.Tune, "Uses a little more battery in the background.")
        Spacer(Modifier.height(9.dp))
        TradeRow(DayfoldIcons.ArrowForward, "Checks places when the app is closed — still on-device.")
      }
    }
    Spacer(Modifier.height(12.dp))
  }

  // on-device promise row (privacy teal).
  val c = LocalDayfoldColors.current
  Surface(shape = RoundedCornerShape(16.dp), color = c.privacyContainer, modifier = Modifier.fillMaxWidth()) {
    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
      Icon(DayfoldIcons.Verified, contentDescription = null, tint = c.onPrivacyContainer, modifier = Modifier.size(24.dp))
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)) {
        Text(cfg.promiseTitle, style = MaterialTheme.typography.titleSmall, color = c.onPrivacyContainer)
        Text(cfg.promiseBody, style = MaterialTheme.typography.bodySmall, color = c.onPrivacyContainer)
      }
    }
  }
}

private data class PrimingCfg(
  val kicker: String, val heroIcon: ImageVector, val title: String, val body: String,
  val reasons: List<Reason>, val promiseTitle: String, val promiseBody: String,
)

@Composable
private fun TradeRow(icon: ImageVector, text: String) {
  val cs = MaterialTheme.colorScheme
  Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(19.dp))
    Spacer(Modifier.width(10.dp))
    Text(text, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
  }
}

@Composable
private fun LimitedBody() {
  val cs = MaterialTheme.colorScheme
  val c = LocalDayfoldColors.current
  Spacer(Modifier.height(8.dp))
  StatusPill("Location · while using the app", DayfoldIcons.MyLocation, cs.secondaryContainer, cs.onSecondaryContainer)
  Spacer(Modifier.height(20.dp))
  IconTile(DayfoldIcons.MyLocation, cs.secondaryContainer, cs.onSecondaryContainer, big = true)
  Spacer(Modifier.height(20.dp))
  Text("Open Dayfold to see what's nearby", style = MaterialTheme.typography.headlineSmall, color = cs.onSurface)
  Spacer(Modifier.height(8.dp))
  Text(
    "You've allowed location only while the app is open — so we check your place reminders when you're here, and never in the background. That's a perfectly good setup.",
    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
  )
  Spacer(Modifier.height(14.dp))
  InfoCard(DayfoldIcons.NotificationsActive, "No background pings while it's set this way. We won't nag — your reminders just wait inside Now.")
  Spacer(Modifier.height(10.dp))
  Surface(shape = RoundedCornerShape(18.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
    Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
      Icon(DayfoldIcons.Verified, contentDescription = null, tint = c.onPrivacyContainer, modifier = Modifier.size(22.dp))
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)) {
        Text("Want background proximity?", style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
        Text("It's an optional upgrade — and reversible.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
      }
      Text("Review", style = MaterialTheme.typography.labelLarge, color = cs.secondary)
    }
  }
}

// Downgraded-but-functional: "Always" was granted then revoked in OS settings — fall back to the
// limited (while-using) experience, stated honestly + calmly. Background pings stopped; nothing broke.
@Composable
private fun DowngradedBody() {
  val cs = MaterialTheme.colorScheme
  Spacer(Modifier.height(8.dp))
  StatusPill("Background access turned off", DayfoldIcons.LocationOff, cs.surfaceContainerHigh, cs.onSurfaceVariant)
  Spacer(Modifier.height(20.dp))
  IconTile(DayfoldIcons.MyLocation, cs.secondaryContainer, cs.onSecondaryContainer, big = true)
  Spacer(Modifier.height(20.dp))
  Text("Back to while-using only", style = MaterialTheme.typography.headlineSmall, color = cs.onSurface)
  Spacer(Modifier.height(8.dp))
  Text(
    "Background location was switched off in your phone settings, so place reminders pause when Dayfold is closed. Everything still works the moment you open the app — and you can re-enable Always anytime.",
    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
  )
  Spacer(Modifier.height(14.dp))
  InfoCard(DayfoldIcons.NotificationsActive, "No background pings until you turn Always back on. Your reminders wait quietly inside Now.")
}

@Composable
private fun DeniedBody() {
  val cs = MaterialTheme.colorScheme
  Spacer(Modifier.height(8.dp))
  StatusPill("Location · off", DayfoldIcons.LocationOff, cs.surfaceContainerHigh, cs.onSurfaceVariant)
  Spacer(Modifier.height(20.dp))
  IconTile(DayfoldIcons.PauseCircle, cs.surfaceContainerHigh, cs.onSurfaceVariant, big = true)
  Spacer(Modifier.height(20.dp))
  Text("Place reminders are paused", style = MaterialTheme.typography.headlineSmall, color = cs.onSurface)
  Spacer(Modifier.height(8.dp))
  Text(
    "That's completely fine. Everything is still here — your briefing and Hubs work exactly the same, just without the place-based timing.",
    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
  )
  Spacer(Modifier.height(14.dp))
  Surface(shape = RoundedCornerShape(20.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp)) {
      Text("STILL WORKS — JUST NOT TIMED", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
      Spacer(Modifier.height(10.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        IconTile(DayfoldIcons.Today, cs.surfaceContainerHigh, cs.secondary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
          Text("Party shopping list", style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
          Text("7 open · open it anytime from the Hub", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
      }
    }
  }
}

@Composable
private fun PermissionFooter(prompt: PermissionPrompt, onPrimary: () -> Unit, onSecondary: () -> Unit) {
  val cs = MaterialTheme.colorScheme
  val (primaryLabel, primaryIcon, secondaryLabel, footnote) = when (prompt) {
    PermissionPrompt.LocPrime -> FooterCfg("Allow while using", DayfoldIcons.Verified, "Not now", "Background proximity is a separate, optional step — later, or never.")
    PermissionPrompt.AlwaysUpgrade -> FooterCfg("Allow always", DayfoldIcons.MyLocation, "Keep when-in-use", "Reversible anytime — in Dayfold or your phone's settings.")
    PermissionPrompt.NotifPrime -> FooterCfg("Turn on notifications", DayfoldIcons.NotificationsActive, "Not now", "You can change this anytime in Settings.")
    PermissionPrompt.Limited -> FooterCfg("Open Now", DayfoldIcons.Today, "Location access", "No background location is used in this mode.")
    PermissionPrompt.Downgraded -> FooterCfg("Re-enable Always", DayfoldIcons.MyLocation, "Keep while-using", "Reversible anytime — in Dayfold or your phone's settings.")
    PermissionPrompt.Denied -> FooterCfg("Turn on in Settings", DayfoldIcons.Tune, "Keep it off", "No pressure — nothing here is broken.")
  }
  Surface(color = cs.surface, modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        // secondary is a FULL-COLOR PEER — outlined, never disabled (ADR 0044 §1: declining is first-class).
        OutlinedButton(onClick = onSecondary, modifier = Modifier.heightIn(min = 48.dp)) {
          Text(secondaryLabel, style = MaterialTheme.typography.labelLarge)
        }
        Button(onClick = onPrimary, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
          Icon(primaryIcon, contentDescription = null, modifier = Modifier.size(19.dp))
          Spacer(Modifier.width(8.dp))
          Text(primaryLabel, style = MaterialTheme.typography.labelLarge)
        }
      }
      Spacer(Modifier.height(9.dp))
      Text(footnote, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.fillMaxWidth().semantics { contentDescription = footnote })
    }
  }
}

private data class FooterCfg(val primaryLabel: String, val primaryIcon: ImageVector, val secondaryLabel: String, val footnote: String)

@Composable
private fun StatusPill(text: String, icon: ImageVector, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color) {
  Surface(shape = RoundedCornerShape(999.dp), color = bg) {
    Row(Modifier.padding(horizontal = 13.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
      Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
      Spacer(Modifier.width(7.dp))
      Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = fg)
    }
  }
}

@Composable
private fun IconTile(icon: ImageVector, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color, big: Boolean = false) {
  val tile = if (big) 84.dp else 38.dp
  Surface(shape = RoundedCornerShape(if (big) 26.dp else 12.dp), color = bg, modifier = Modifier.size(tile)) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
      Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(if (big) 42.dp else 20.dp))
    }
  }
}

@Composable
private fun InfoCard(icon: ImageVector, text: String) {
  val cs = MaterialTheme.colorScheme
  Surface(shape = RoundedCornerShape(18.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
    Row(Modifier.padding(15.dp), verticalAlignment = Alignment.Top) {
      Icon(icon, contentDescription = null, tint = cs.secondary, modifier = Modifier.size(22.dp))
      Spacer(Modifier.width(12.dp))
      Text(text, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
    }
  }
}
