package com.sloopworks.dayfold.client

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.sloopworks.dayfold.client.cards.CardAction
import com.sloopworks.dayfold.client.cards.TypedCardItem

// M0 feed-only render: the briefing-card list from redux state. Shared
// Composable (commonMain-compatible) — the Android/iOS/desktop shells host it.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(state: AppState, onAction: (CardAction) -> Unit = {}, onOpenAccount: () -> Unit = {}) {
  Scaffold(topBar = {
    TopAppBar(
      title = { Text("Today") },
      actions = {
        // account entry — monogram avatar → AccountScreen (sign-out lives there)
        Box(
          Modifier.padding(end = 12.dp).size(34.dp).clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primaryContainer).clickable(onClick = onOpenAccount),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            "Y", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
          )
        }
      },
    )
  }) { pad ->
    if (state.cards.isEmpty()) {
      // S5: an empty family shows the family-null onboarding (invite/connect),
      // not a bare "nothing yet". Sync/error keep their terse status.
      Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
        when {
          state.syncing -> Text("Syncing…")
          state.error != null -> Text(state.error)
          else -> FamilyNullState()
        }
      }
    } else {
      LazyColumn(
        Modifier.fillMaxSize().padding(pad),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        // CL-5: typed cards dispatch by type; kind-only/legacy cards keep the
        // generic CardItem (back-compat — unknown types render via the typed
        // dispatcher's safe generic fallback, never crash).
        items(feedCards(state), key = { it.id }) { card ->
          if (card.type != null) TypedCardItem(card, onAction) else CardItem(card)
        }
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
