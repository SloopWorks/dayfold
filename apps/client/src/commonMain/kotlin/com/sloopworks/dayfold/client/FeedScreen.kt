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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.datetime.toLocalDateTime
import com.sloopworks.dayfold.client.cards.CardAction
import com.sloopworks.dayfold.client.cards.TypedCardItem
import com.sloopworks.dayfold.client.ui.loading.FeedSkeleton
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
      when {
        rememberStableLoading(state.syncing) ->
          FeedSkeleton(Modifier.padding(pad))
        state.error != null ->
          Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { EmptyFeedError(state.error, onRefresh) }
        else ->
          Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { FamilyNullState(onConnectDevice = onConnectDevice) }
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
        Text(renderBlockMarkdown(it), style = MaterialTheme.typography.bodyMedium)
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
