package com.familyai.client

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// M0 feed-only render: the briefing-card list from redux state. Shared
// Composable (commonMain-compatible) — the Android/iOS/desktop shells host it.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(state: AppState) {
  Scaffold(topBar = { TopAppBar(title = { Text("Today") }) }) { pad ->
    if (state.cards.isEmpty()) {
      Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
        Text(if (state.syncing) "Syncing…" else state.error ?: "Nothing yet")
      }
    } else {
      LazyColumn(
        Modifier.fillMaxSize().padding(pad),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        items(feedCards(state), key = { it.id }) { card -> CardItem(card) }
      }
    }
  }
}

@Composable
private fun CardItem(card: Card) {
  ElevatedCard(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      // kind chip (info = none)
      kindLabel(card.kind)?.let { label ->
        Text(
          label.uppercase(),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
        )
      }
      // countdown → emphasized title; others → titleMedium
      Text(
        card.title,
        style = if (card.kind == "countdown") MaterialTheme.typography.headlineSmall
        else MaterialTheme.typography.titleMedium,
      )
      // body with allowlisted tappable action-links
      card.bodyMd?.takeIf { it.isNotBlank() }?.let {
        Text(renderCardBody(it), style = MaterialTheme.typography.bodyMedium)
      }
      // provenance source chip (user = none)
      sourceLabel(card.provenance?.source)?.let { src ->
        Text(
          src,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}
