package com.sloopworks.dayfold.client

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.datetime.toLocalDateTime
import com.sloopworks.dayfold.client.cards.CardAction
import com.sloopworks.dayfold.client.cards.TypedCardItem
import com.sloopworks.dayfold.client.ui.loading.rememberStableLoading

// M0 feed-only render: the briefing-card list from redux state. Shared
// Composable (commonMain-compatible) — the Android/iOS/desktop shells host it.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(state: AppState, onAction: (CardAction) -> Unit = {}, onOpenAccount: () -> Unit = {}, onConnectDevice: () -> Unit = {}, onNavHubs: () -> Unit = {}, onRefresh: () -> Unit = {}) {
  Scaffold(
    topBar = {
    TopAppBar(
      title = {
        val today = kotlin.time.Clock.System.now()
          .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()).date
        Column {
          Text("Today")
          // calm orienting subtitle for the daily briefing
          Text(formatDayLabel(today), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      },
      actions = {
        // account entry — monogram avatar → AccountScreen (sign-out lives there).
        // Icon-only → label it "Account"; the "Y" monogram is decorative.
        Box(
          Modifier.padding(end = 12.dp).size(34.dp).clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primaryContainer).clickable(onClick = onOpenAccount)
            .semantics { contentDescription = "Account" },
          contentAlignment = Alignment.Center,
        ) {
          Text(
            "Y", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.clearAndSetSemantics {},
          )
        }
      },
    )
  },
    bottomBar = { DayfoldBottomNav(hubsActive = false, onNow = {}, onHubs = onNavHubs) },
  ) { pad ->
    if (state.cards.isEmpty()) {
      // ADR 0008 / #164: FOUR posture states, not one. The old code showed the first-run
      // onboarding for ANY empty feed — misframing an ESTABLISHED family that's simply
      // caught up (a hub authored, zero briefing cards) as "nothing set up yet". Established
      // = has hubs (from the cache flow) or >1 member (from the session roster) — both already
      // in state on the Now surface (SyncEngine watches activeHubsFlow; roster loads at session).
      val established = state.hubs.isNotEmpty() || state.members.size > 1
      when {
        // first load, no cache → calm skeleton (not a spinner-in-a-void), debounced so a
        // fast sync never flashes the skeleton or the onboarding state (loading-states PR).
        rememberStableLoading(state.syncing) -> SyncingState(Modifier.padding(pad))
        state.syncing -> Unit   // pre-debounce window: render nothing, not the onboarding state
        else -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
          when {
            state.error != null -> EmptyFeedError(state.error, onRefresh)                          // offline / sync error
            established -> CaughtUpState(hasHubs = state.hubs.isNotEmpty(), onOpenHubs = onNavHubs) // all clear (the headline fix)
            else -> FamilyNullState(onConnectDevice = onConnectDevice)                             // first run
          }
        }
      }
    } else {
      androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = state.syncing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize().padding(pad),
      ) {
        LazyColumn(
          Modifier.fillMaxSize(),
          contentPadding = PaddingValues(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          if (state.error != null) item(key = "sync-error") { RefreshErrorBanner(onRefresh) }
          items(feedCards(state, kotlin.time.Clock.System.now().toString()), key = { it.id }) { card ->
            if (card.type != null) TypedCardItem(card, onAction) else CardItem(card)
          }
        }
      }
    }
  }
}

// #164 CL-A8 — the headline new state: an ESTABLISHED family with nothing pressing right
// now. Calm + positive ("all caught up"), never a nag (no badges/bait); a quiet path to
// Hubs only when the family has them. Replaces "show onboarding for any empty feed".
@Composable
private fun CaughtUpState(hasHubs: Boolean, onOpenHubs: () -> Unit) {
  val cs = MaterialTheme.colorScheme
  Column(Modifier.padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    Icon(Icons.Rounded.WbTwilight, contentDescription = null, tint = cs.onSecondaryContainer, modifier = Modifier.size(50.dp))
    Spacer(Modifier.height(20.dp))
    Text("You're all caught up", style = MaterialTheme.typography.headlineSmall, color = cs.onSurface, textAlign = TextAlign.Center)
    Spacer(Modifier.height(10.dp))
    Text(
      "Nothing needs you right now. We'll quietly surface what matters as your day fills in — no pings, no badges.",
      style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
    )
    if (hasHubs) {
      Spacer(Modifier.height(26.dp))
      Surface(shape = RoundedCornerShape(999.dp), color = cs.surfaceContainer, modifier = Modifier.clickable(onClick = onOpenHubs)) {
        Row(
          Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Icon(DayfoldIcons.Dashboard, contentDescription = null, tint = cs.secondary, modifier = Modifier.size(19.dp))
          Text("Your hubs are here", style = MaterialTheme.typography.labelLarge, color = cs.onSurface)
          Icon(DayfoldIcons.ArrowForward, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
      }
    }
  }
}

// #164 — first load with no cached cards: a calm skeleton in the loaded feed's rhythm
// (the brief: NOT a spinner-in-a-void). Three placeholder card silhouettes.
@Composable
private fun SyncingState(modifier: Modifier = Modifier) {
  Column(
    modifier.fillMaxSize().padding(16.dp).semantics { contentDescription = "Catching up on your day" },
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    repeat(3) {
      Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().height(84.dp),
      ) {}
    }
  }
}

// Empty feed + sync failure (e.g. first launch offline) was a dead-end — just the
// raw error text, no way forward. Give a calm headline, the reason, and a retry.
@Composable
private fun EmptyFeedError(error: String, onRefresh: () -> Unit) {
  Column(
    Modifier.padding(40.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("Couldn't load your day", style = MaterialTheme.typography.titleMedium)
    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Button(onClick = onRefresh) { Text("Try again") }
  }
}

// Calm refresh-failed banner — warm secondaryContainer (never alarming error-red),
// with a retry. Shown above a populated feed so a failed sync isn't silent.
@Composable
private fun RefreshErrorBanner(onRefresh: () -> Unit) {
  Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
    Row(
      Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("Couldn't refresh — showing saved cards", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.weight(1f))
      TextButton(onClick = onRefresh) { Text("Retry") }
    }
  }
}

@Composable
private fun CardItem(card: Card) {
  val m = card.media
  ElevatedCard(Modifier.fillMaxWidth()) {
    Row(Modifier.padding(16.dp)) {
      // ADR 0036: optional leading thumbnail (image → icon+accent tile fallback).
      if (m?.thumbnailUrl != null) {
        EnrichedThumbnail(m.thumbnailUrl, m.imageFit, m.icon, m.accentColor, m.imageAlt, size = 60.dp, corner = 16.dp)
        Spacer(Modifier.width(14.dp))
      }
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      // kind chip: accent chip (icon + derived accent) when enriched, else the
      // existing plain label (info = none). accentColor never touches body text.
      val label = kindLabel(card.kind)
      if (m?.accentColor != null || m?.icon != null) {
        AccentKindChip((label ?: card.kind).uppercase(), m.icon, m.accentColor)
      } else label?.let {
        Text(it.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
      }
      // countdown → emphasized title; others → titleMedium
      Text(
        card.title,
        style = if (card.kind == "countdown") MaterialTheme.typography.headlineSmall
        else MaterialTheme.typography.titleMedium,
      )
      // body: full markdown (bold/italic/lists/tables) + allowlisted tappable links —
      // same renderer as hub blocks, so a CLI-authored card body renders formatted,
      // not raw `**`/`-`. Plain text + links are byte-identical to the old link-only path.
      card.bodyMd?.takeIf { it.isNotBlank() }?.let {
        Text(rememberRenderedMarkdown(it), style = MaterialTheme.typography.bodyMedium)
      }
      // provenance source chip (user = none)
      sourceLabel(card.provenance?.source)?.let { src ->
        Text(
          src,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      }   // Column
    }     // Row
  }
}
