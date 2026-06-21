package com.familyai.client

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.familyai.client.cards.CardAction
import com.familyai.client.cards.DetailScreen
import com.familyai.client.cards.LocalAnimatedVisibilityScope
import com.familyai.client.cards.LocalSharedTransitionScope
import com.familyai.client.theme.DayfoldTheme
import org.reduxkotlin.Store
import org.reduxkotlin.compose.selectorState

// Route a card's CardAction: OpenDetail = in-app nav → store; everything else =
// an OS handoff → the shell's PlatformActions. Extracted (non-Composable) so the
// split is unit-testable. Returns Unit (store.dispatch returns the action).
internal fun routeCardAction(store: Store<AppState>, onPlatformAction: (CardAction) -> Unit, action: CardAction) {
  if (action is CardAction.OpenDetail) store.dispatch(NavToDetail(action.cardId))
  else onPlatformAction(action)
}

// f(store.state) -> UI via redux-kotlin-compose `store.selectorState { }` — a
// reactive Compose projection of the single state source (the whole AppState
// here; swap to per-field `fieldState`/narrower selectors to scope recomposition).
// Every shell (desktop, Android, iOS) renders this one connected composable,
// wrapped once in the Dayfold theme (ADR 0022 D5).
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun FeedApp(store: Store<AppState>, onPlatformAction: (CardAction) -> Unit = {}) {
  val state by store.selectorState { it }
  // One stable handler (remembered so feed/detail stay skippable): OpenDetail is
  // in-app nav → dispatched to the store; every other CardAction is an OS handoff
  // → the shell's PlatformActions. NOTE: the whole-state `selectorState { it }`
  // subscription is the pre-existing M0 pattern; scoping it (feedCards vs
  // currentDetailCard) to shrink recomposition is a tracked perf follow.
  val handle = remember(store, onPlatformAction) {
    fun(action: CardAction) = routeCardAction(store, onPlatformAction, action)
  }
  DayfoldTheme {
    // CL-7b container transform: SharedTransitionLayout shares the tapped card's
    // bounds (key "card-$id") with the detail container → the card morphs into the
    // detail (and back). AnimatedContent keyed on the open id (null = feed) drives
    // the cross-fade; the shared element drives the bounds morph. Asymmetric
    // timing: open slightly slower than back, per the design.
    val detail = currentDetailCard(state)
    SharedTransitionLayout {
      AnimatedContent(
        targetState = detail?.id,
        transitionSpec = {
          val opening = targetState != null
          val dur = if (opening) 360 else 280
          (fadeIn(tween(dur)) + slideInVertically(tween(dur)) { h -> h / 16 }) togetherWith fadeOut(tween(dur))
        },
        label = "feed-detail",
      ) { id ->
        CompositionLocalProvider(
          LocalSharedTransitionScope provides this@SharedTransitionLayout,
          LocalAnimatedVisibilityScope provides this@AnimatedContent,
        ) {
          val card = id?.let { cid -> state.cards.find { it.id == cid } }
          if (card != null) DetailScreen(card, onBack = { store.dispatch(NavBack) }, onAction = handle)
          else FeedScreen(state, onAction = handle)
        }
      }
    }
  }
}
