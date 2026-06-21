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
//
// AUTH-S5 route gate (auth) + CL content host (feed/detail) integrated: a pure
// when(route) gate (no nav library, ADR 0013); the Feed route renders the
// CL-6/7b content host (SharedTransitionLayout feed↔detail). Effect callbacks:
// onSignIn / onCreateFamily drive the AuthEngine (T6); onPlatformAction performs
// card OS-handoffs (CL-PLAT). All default to no-ops so screens stay snapshot-
// testable in isolation.
@Composable
fun FeedApp(
  store: Store<AppState>,
  onPlatformAction: (CardAction) -> Unit = {},
  onSignIn: (String) -> Unit = {},
  onCreateFamily: (String) -> Unit = {},
  onSignOut: () -> Unit = {},
  onRedeemInvite: (String) -> Unit = {},
  onLoadApprovals: () -> Unit = {},
  onApproveMember: (String) -> Unit = {},
  onDeclineMember: (String) -> Unit = {},
) {
  val state by store.selectorState { it }
  // One stable handler (remembered so feed/detail stay skippable): OpenDetail is
  // in-app nav → dispatched to the store; every other CardAction is an OS handoff
  // → the shell's PlatformActions.
  val handle = remember(store, onPlatformAction) {
    fun(action: CardAction) = routeCardAction(store, onPlatformAction, action)
  }
  DayfoldTheme {
    when (state.route) {
      Route.Loading -> SplashScreen()
      Route.SignIn -> SignInScreen(busy = state.authBusy, error = state.authError, onProvider = onSignIn)
      Route.CreateFamily -> CreateFamilyScreen(
        busy = state.authBusy, error = state.authError,
        onCreate = onCreateFamily, onJoinInvite = { store.dispatch(OpenJoinInvite) },
      )
      Route.JoinInvite -> JoinInviteScreen(state, onJoin = onRedeemInvite, onDismiss = { store.dispatch(JoinDismissed) })
      Route.Feed -> ContentHost(store, state, handle)
      Route.Account -> AccountScreen(
        state, onSignOut = onSignOut, onClose = { store.dispatch(CloseAccount) },
        onOpenMembers = { store.dispatch(OpenMembers) },
      )
      Route.Members -> MembersScreen(
        state, onApprove = onApproveMember, onDecline = onDeclineMember,
        onLoad = onLoadApprovals, onBack = { store.dispatch(OpenAccount) },
      )
    }
  }
}

// CL-7b container transform: SharedTransitionLayout shares the tapped card's
// bounds (key "card-$id") with the detail container → the card morphs into the
// detail (and back). AnimatedContent keyed on the open id (null = feed) drives the
// cross-fade; the shared element drives the bounds morph. Asymmetric timing.
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun ContentHost(store: Store<AppState>, state: AppState, handle: (CardAction) -> Unit) {
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
        else FeedScreen(state, onAction = handle, onOpenAccount = { store.dispatch(OpenAccount) })
      }
    }
  }
}
