package com.familyai.client

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.familyai.client.cards.CardAction
import com.familyai.client.theme.DayfoldTheme
import org.reduxkotlin.Store
import org.reduxkotlin.compose.selectorState

// f(store.state) -> UI via redux-kotlin-compose `store.selectorState { }` — a
// reactive Compose projection of the single state source (the whole AppState
// here; swap to per-field `fieldState`/narrower selectors to scope recomposition).
// Every shell (desktop, Android, iOS) renders this one connected composable,
// wrapped once in the Dayfold theme (ADR 0022 D5).
@Composable
fun FeedApp(store: Store<AppState>, onAction: (CardAction) -> Unit = {}) {
  val state by store.selectorState { it }
  DayfoldTheme {
    // CL-PLAT: each shell passes a PlatformActions::perform that turns card
    // CardActions into OS handoffs. OpenDetail (in-app nav) is routed via the
    // redux nav layer in CL-6.
    FeedScreen(state, onAction = onAction)
  }
}
