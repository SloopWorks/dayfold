package com.familyai.client.cards

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.familyai.client.Card
import com.familyai.client.sourceLabel
import com.familyai.client.theme.LocalDayfoldColors

// CL-5 — the 6 typed Now-card layouts (ADR 0022; mockup designs/content/
// Content-Library.dc.html, signed off INB-16). Visuals run off MaterialTheme
// color ROLES so light+dark are both correct (CL-0 encodes the Dayfold hex +
// dark equivalents). No icon dependency at M0 (accent tiles + text labels —
// a11y-friendly; Material-Symbols glyph fidelity = CL-0b follow). Read-only
// (ADR 0020): inline actions are OS handoffs / open-detail intents only.

/** Dispatch a typed card to its layout; unknown/null type falls back upstream. */
@Composable
fun TypedCardItem(card: Card, onAction: (CardAction) -> Unit) {
  when (card.type) {
    "invite" -> InviteCard(card, onAction)
    "contact" -> ContactCard(card, onAction)
    "geo" -> GeoCard(card, onAction)
    "file", "link", "email" -> StandardCard(card, onAction)
    else -> StandardCard(card, onAction) // typed-but-unfamiliar → safe generic typed layout
  }
}

// ── shared chrome ─────────────────────────────────────────────────────────────

// `solid` = use the bold role (primary/secondary/tertiary) instead of its
// container. Needed when the CARD bg is already the accent container (invite),
// else the tile/chip would be the same color as the card and vanish; matches the
// mockup's solid-coral invite kicker.
@Composable
internal fun accentColors(a: CardAccent, solid: Boolean): Pair<Color, Color> {
  val cs = MaterialTheme.colorScheme
  return when (a) {
    CardAccent.Primary -> if (solid) cs.primary to cs.onPrimary else cs.primaryContainer to cs.onPrimaryContainer
    CardAccent.Secondary -> if (solid) cs.secondary to cs.onSecondary else cs.secondaryContainer to cs.onSecondaryContainer
    CardAccent.Tertiary -> if (solid) cs.tertiary to cs.onTertiary else cs.tertiaryContainer to cs.onTertiaryContainer
  }
}

/** Rounded accent tile with a short monogram (type/initials). 44dp. Decorative —
 *  the kicker chip already states the type in text, so it's hidden from a11y. */
@Composable
internal fun AccentTile(monogram: String, accent: CardAccent, solid: Boolean) {
  val (bg, fg) = accentColors(accent, solid)
  Surface(color = bg, shape = RoundedCornerShape(14.dp),
    modifier = Modifier.size(44.dp).clearAndSetSemantics {}) {
    Box(contentAlignment = Alignment.Center) {
      Text(monogram, color = fg, style = MaterialTheme.typography.titleMedium)
    }
  }
}

@Composable
internal fun KickerChip(text: String, accent: CardAccent, solid: Boolean) {
  if (text.isBlank()) return
  val (bg, fg) = accentColors(accent, solid)
  Surface(color = bg, shape = RoundedCornerShape(8.dp)) {
    Text(
      text, color = fg, style = MaterialTheme.typography.labelSmall,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
  }
}

@Composable
internal fun ProvenanceChip(source: String?) {
  val label = sourceLabel(source) ?: return
  Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** honest privacy.storage → enforced copy (ADR 0014/0015). NOTE: a geo card whose
 *  payload holds coords must be authored `on_device` ("a copy is cached"), NOT
 *  `location_local` — only *live position* stays local (ADR 0014). */
internal fun privacyLabel(storage: String?): String? = when (storage) {
  null -> null
  "on_device" -> "Cached on your device"
  "in_browser" -> "Kept in your browser"
  "location_local" -> "Location stays on device"
  "matched_on_device" -> "Matched on your device"
  else -> storage
}

/** Honesty chip (ADR 0014/0015) — rendered from the enforced privacy.storage enum. */
@Composable
internal fun PrivacyChip(storage: String?) {
  val label = privacyLabel(storage) ?: return
  val ext = LocalDayfoldColors.current
  Surface(color = ext.privacyContainer, shape = RoundedCornerShape(8.dp)) {
    Text(label, color = ext.onPrivacyContainer, style = MaterialTheme.typography.labelSmall,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
  }
}

@Composable
private fun PrimaryActionPill(label: String, onClick: () -> Unit) {
  FilledTonalButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
}

/** Base card scaffold: tap = open detail; header (tile + kicker + title), body,
 *  type-specific [extra], then the action/provenance row + privacy chip. */
@Composable
private fun BaseCard(
  card: Card,
  monogram: String,
  onAction: (CardAction) -> Unit,
  container: Color? = null,
  solidAccent: Boolean = false,
  extra: @Composable () -> Unit = {},
) {
  val accent = accentFor(card.type)
  val colors = if (container != null) CardDefaults.elevatedCardColors(containerColor = container)
  else CardDefaults.elevatedCardColors()
  ElevatedCard(
    onClick = { onAction(CardAction.OpenDetail(card.id)) },
    shape = MaterialTheme.shapes.large, // 26dp
    colors = colors,
    modifier = Modifier.fillMaxWidth().cardSharedBounds(card.id), // CL-7b: morph source
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        AccentTile(monogram, accent, solidAccent)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          KickerChip(kickerFor(card), accent, solidAccent)
          Text(card.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
      }
      bodySummaryFor(card)?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      extra()
      Row(verticalAlignment = Alignment.CenterVertically) {
        val (label, action) = primaryActionFor(card)
        PrimaryActionPill(label) { onAction(action) }
        Spacer(Modifier.weight(1f))
        ProvenanceChip(card.provenance?.source)
      }
      PrivacyChip(card.privacy?.storage)
    }
  }
}

private fun monogramOf(text: String?, fallback: String): String =
  text?.trim()?.split(" ")?.filter { it.isNotEmpty() }?.take(2)?.joinToString("") { it.first().uppercase() }
    ?.ifBlank { fallback } ?: fallback

// ── per-type cards ────────────────────────────────────────────────────────────

/** file / link / email — the standard layout (no distinct inline affordance). */
@Composable
private fun StandardCard(card: Card, onAction: (CardAction) -> Unit) {
  val mono = when (card.type) { "file" -> "F"; "link" -> "L"; "email" -> "@"; else -> "•" }
  BaseCard(card, mono, onAction)
}

/** invite — coral primaryContainer bg + a DISPLAY-ONLY Yes/No row (no write path, ADR 0020/0016). */
@Composable
private fun InviteCard(card: Card, onAction: (CardAction) -> Unit) {
  BaseCard(card, "!", onAction, container = MaterialTheme.colorScheme.primaryContainer, solidAccent = true) {
    RsvpDisplayRow(card.payload?.invite?.rsvpState)
  }
}

@Composable
internal fun RsvpDisplayRow(rsvpState: String?) {
  // Static reflection of the authored RSVP — NOT an input (M0 has no write path).
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics {
    contentDescription = "RSVP status: ${rsvpState ?: "no reply yet"}"
  }) {
    RsvpPill("Yes", selected = rsvpState == "yes")
    RsvpPill("No", selected = rsvpState == "no")
  }
}

@Composable
private fun RsvpPill(label: String, selected: Boolean) {
  val cs = MaterialTheme.colorScheme
  val bg = if (selected) cs.primary else cs.surfaceContainerHighest
  val fg = if (selected) cs.onPrimary else cs.onSurfaceVariant
  Surface(color = bg, shape = RoundedCornerShape(999.dp), modifier = Modifier.heightIn(min = 36.dp)) {
    Text(label, color = fg, style = MaterialTheme.typography.labelLarge,
      modifier = Modifier.widthIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp))
  }
}

/** contact — avatar monogram + inline Call / Text handoffs. */
@Composable
private fun ContactCard(card: Card, onAction: (CardAction) -> Unit) {
  val name = card.payload?.contact?.name
  BaseCard(card, monogramOf(name, "C"), onAction) {
    val phone = card.payload?.contact?.phone
    if (phone != null) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onAction(CardAction.Call(phone)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Call") }
        OutlinedButton(onClick = { onAction(CardAction.Message(phone)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Text") }
      }
    }
  }
}

/** geo — stylized map strip (no SDK/key/position leak, ADR 0014) + Navigate handoff. */
@Composable
private fun GeoCard(card: Card, onAction: (CardAction) -> Unit) {
  BaseCard(card, "G", onAction) { MapStrip() }
}

@Composable
internal fun MapStrip() {
  val ext = LocalDayfoldColors.current
  Surface(color = ext.mapBackground, shape = RoundedCornerShape(12.dp),
    modifier = Modifier.fillMaxWidth().height(92.dp).clearAndSetSemantics {}) {
    Box {
      // two stylized "roads" — purely decorative placeholder
      Box(Modifier.fillMaxWidth().height(6.dp).padding(top = 28.dp)) {
        Surface(color = ext.mapRoad, modifier = Modifier.fillMaxWidth().height(6.dp)) {}
      }
      Box(Modifier.fillMaxWidth().height(6.dp).padding(top = 58.dp)) {
        Surface(color = ext.mapLine, modifier = Modifier.fillMaxWidth().height(6.dp)) {}
      }
    }
  }
}
