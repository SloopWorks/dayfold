package com.sloopworks.dayfold.client

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sloopworks.dayfold.client.cards.CardAction
import com.sloopworks.dayfold.client.cards.TypedCardItem
import com.sloopworks.dayfold.client.features.calendar.CalendarCheckFooterLine
import com.sloopworks.dayfold.client.features.calendar.CalendarCheckNowCard

// ADR 0043 Phase A — the merged Now feed render. Derived + authored items are rendered as PEERS in
// one calm ranked list (LazyColumn), distinguished only by a small "why" chip. Bands (now/soon/
// later) carry the horizon; the calm budget's tail collapses into a quiet "More" (never a wall,
// never a count). One on-device engine supplies the order; the UI only renders it.
@Composable
fun NowFeedList(
  feed: RankedFeed,
  cardsById: Map<String, Card>,
  onAction: (CardAction) -> Unit,
  modifier: Modifier = Modifier,
  // Hoisted so the scroll position survives the feed↔detail AnimatedContent swap (which has no
  // SaveableStateHolder → the default internal state is discarded on exit). Owner: ContentHost.
  listState: LazyListState = rememberLazyListState(),
  // WI-446 (ADR 0063 §5) — the aggregate Calendar Check card's "Review" tap. Deferred navigation
  // (mirrors WI-447's settings/native-handoff surfaces): no-op until a later WI wires the review
  // flow into the route graph, once ADR 0063 is Accepted.
  onOpenCalendarReview: () -> Unit = {},
) {
  var overflowOpen by remember { mutableStateOf(false) }
  LazyColumn(
    modifier.fillMaxSize(),
    state = listState,
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // Caught-up headline (NOW empty) — calm + positive, then the horizon still shows below it.
    if (feed.caughtUp) {
      item(key = "caught-up") { CaughtUpHeader() }
    }
    band("Now", feed.now, cardsById, onAction, onOpenCalendarReview)
    band("Soon", feed.soon, cardsById, onAction, onOpenCalendarReview)
    band("Later", feed.later, cardsById, onAction, onOpenCalendarReview)

    if (feed.overflow.isNotEmpty()) {
      item(key = "overflow-toggle") {
        TextButton(onClick = { overflowOpen = !overflowOpen }) {
          Text(if (overflowOpen) "Show less" else "More", style = MaterialTheme.typography.labelLarge)
        }
      }
      if (overflowOpen) {
        items(feed.overflow, key = { "of-${it.item.id}" }) { RankedRow(it, cardsById, onAction, onOpenCalendarReview) }
      }
    }
    // ADR 0063 §5 — the quiet all-clear/stale line, mutually exclusive with a CALENDAR_CHECK
    // item appearing above (calendarCheckDisplay computes exactly one of the two).
    feed.calendarCheckFooter?.let { footer ->
      item(key = "calendar-check-footer") { CalendarCheckFooterLine(footer) }
    }
  }
}

// A time band: a quiet label + divider, then its rows. Omitted entirely when empty (no empty
// headers cluttering the calm feed).
private fun LazyListScope.band(
  label: String,
  rows: List<RankedItem>,
  cardsById: Map<String, Card>,
  onAction: (CardAction) -> Unit,
  onOpenCalendarReview: () -> Unit,
) {
  if (rows.isEmpty()) return
  item(key = "band-$label") {
    Column(Modifier.fillMaxWidth()) {
      Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Spacer(Modifier.size(6.dp))
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
  }
  items(rows, key = { it.item.id }) { RankedRow(it, cardsById, onAction, onOpenCalendarReview) }
}

// One ranked unit. An authored item reuses its shipped typed/plain card renderer; a derived item
// gets the on-device "why" card. Collapsed peers (dedup) render inset beneath the head.
@Composable
private fun RankedRow(ranked: RankedItem, cardsById: Map<String, Card>, onAction: (CardAction) -> Unit, onOpenCalendarReview: () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    RenderItem(ranked.item, ranked.emphasized, ranked.softened, cardsById, onAction, onOpenCalendarReview)
    // dedup: the merged peer(s) render quietly inset under the head (the "two reasons, one event"
    // group), each still carrying its own why chip.
    ranked.collapsedWith.forEach { peer ->
      key(peer.id) {
        Row(Modifier.padding(start = 12.dp)) { RenderItem(peer, emphasized = false, softened = ranked.softened, cardsById, onAction, onOpenCalendarReview) }
      }
    }
  }
}

@Composable
private fun RenderItem(
  item: NowItem,
  emphasized: Boolean,
  softened: Boolean,
  cardsById: Map<String, Card>,
  onAction: (CardAction) -> Unit,
  onOpenCalendarReview: () -> Unit,
) {
  if (item.origin == Origin.AUTHORED) {
    val card = cardsById[item.id.removePrefix("authored:")]
    if (card != null) {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // ADR 0064 — BOTH card renderers own the respond ⋮ themselves, inside the card.
        if (card.type != null) TypedCardItem(card, onAction) else CardItem(card, onAction)
      }
      return
    }
  }
  // ADR 0063 §5 — the ONE aggregate Calendar Check unit gets its own dedicated render, never the
  // generic derived "why" card (it has no single reason line, no respond ⋮, and its own pill CTA).
  if (item.reasonKind == ReasonKind.CALENDAR_CHECK) {
    CalendarCheckNowCard(item, onReview = onOpenCalendarReview)
    return
  }
  DerivedNowCard(item, emphasized, softened, onAction)
}

// A derived item: the computed "why" is the hero line; a small provenance/honesty chip makes "why
// am I seeing this" obvious and trustworthy. Geo-active items get the emphasized ring. Tapping
// deep-links into the source hub block (container transform + highlight pulse already shipped).
@Composable
private fun DerivedNowCard(item: NowItem, emphasized: Boolean, softened: Boolean, onAction: (CardAction) -> Unit) {
  val cs = MaterialTheme.colorScheme
  val shape = RoundedCornerShape(16.dp)
  // emphasized (geo-active nearest-N) → an animated primary ring (M3E spring), per the mockup.
  val ringWidth by animateDpAsState(if (emphasized) 2.dp else 0.dp, spring(), label = "nowRing")
  var tap = Modifier.fillMaxWidth()
  item.target?.let { t ->
    tap = tap.clickable { onAction(CardAction.OpenHub(t.hubId, t.blockId ?: t.sectionId)) }
      .semantics { contentDescription = "Open ${item.title} in its hub" }
  }
  // Apply the ring while it has width — renders during the de-emphasis spring (animates OUT, not
  // snaps) yet leaves a non-emphasized card perfectly plain (no 0.dp hairline).
  if (ringWidth > 0.dp) tap = tap.border(BorderStroke(ringWidth, cs.primary), shape)
  ElevatedCard(tap, shape = shape) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      if (emphasized) NearbyPulse()
      // hero "why" — softened items de-emphasize (the calm "easing off" treatment), never nag.
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
          item.why,
          style = if (item.reasonKind == ReasonKind.COUNTDOWN) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
          color = if (softened) cs.onSurfaceVariant else cs.onSurface,
          modifier = Modifier.weight(1f),
        )
        RespondOverflow(item, onAction)
      }
      // ADR 0064 entry point B — the provenance chip is the DOOR to the sheet. Teaching lives
      // where the explanation lives, which is why "why am I seeing this" is never a row inside.
      WhyChip(item, onClick = { onAction(item.respondAction()) })
    }
  }
}

// The "why" chip vocabulary (designs/now-derived/Chips, designs/triggers/Privacy-Affordance). Derived
// items carry the honest affordance "Matched on your device". The chip stays the short honest claim —
// the true two-part story (saved places sync to your family encrypted; only your LIVE position stays on
// this phone, ADR 0014/0044 §3) lives in the privacy info-row/sheet, NOT crammed onto the chip. The old
// "· location never leaves" suffix was the ADR 0044 §3 P0 honesty bug (it read as "saved coords never
// leave", which is false) — killed here. Authored derived-mapped items fall through to a plain label.
@Composable
private fun WhyChip(item: NowItem, onClick: () -> Unit = {}) {
  val label = when (item.reasonKind) {
    ReasonKind.GEO -> "Matched on your device"
    ReasonKind.COUNTDOWN, ReasonKind.MILESTONE, ReasonKind.CHECKLIST, ReasonKind.WHEN -> "On your device"
    ReasonKind.WEATHER -> "Weather"
    ReasonKind.EMAIL -> "From your email"
    ReasonKind.CLAUDE -> "Added by Claude"
    // ADR 0063 §5 — defensive: the aggregate unit gets its own dedicated card (RenderItem), never
    // this generic chip, but a CALENDAR_CHECK item reaching here must never mislabel as AI-authored.
    ReasonKind.CALENDAR_CHECK -> "On your device"
    else -> "Added by Claude"
  }
  val icon = when (item.reasonKind) {
    ReasonKind.GEO -> DayfoldIcons.Location
    ReasonKind.WHEN -> DayfoldIcons.Today
    else -> DayfoldIcons.Event
  }
  AssistChip(
    onClick = onClick,
    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
    leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
    colors = AssistChipDefaults.assistChipColors(),
  )
}

// The calm live-presence cue for a geo-active item — a small pulsing dot + "Nearby" (no count, no
// badge). The M3E pulse animation lives here; emphasis stays to the ring/elevation, never error-red.
@Composable
private fun NearbyPulse() {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Surface(Modifier.size(8.dp).clip(CircleShape), color = MaterialTheme.colorScheme.primary) {}
    Text("Nearby", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
  }
}

@Composable
private fun CaughtUpHeader() {
  val cs = MaterialTheme.colorScheme
  Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text("You're all caught up", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
    Text(
      "Nothing needs you right now — here's what's coming up.",
      style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
    )
  }
}


// ── ADR 0064 — the response entry points on the Now surface ──────────────────────────────
//
// Entry point A: the ⋮ overflow. Baseline and keyboard/TalkBack-reachable, which is why it
// exists alongside the (more discoverable) provenance chip rather than instead of it.
@Composable
private fun RespondOverflow(item: NowItem, onAction: (CardAction) -> Unit) {
  IconButton(
    onClick = { onAction(item.respondAction()) },
    modifier = Modifier.size(32.dp).semantics {
      contentDescription = "Respond to ${item.title}"
    },
  ) {
    Icon(
      DayfoldIcons.MoreVert,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.size(20.dp),
    )
  }
}

/** The subject a response is about, taken from the item the engine already ranked. */
internal fun NowItem.respondAction(atScope: Boolean = false): CardAction.Respond =
  CardAction.Respond(
    subjectRef = subjectKey,
    subjectTitle = title,
    reasonKind = reasonKind,
    source = authoredSource,
    atScope = atScope,
  )


