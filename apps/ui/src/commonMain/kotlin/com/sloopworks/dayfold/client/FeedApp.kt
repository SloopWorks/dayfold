package com.sloopworks.dayfold.client

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.backhandler.PredictiveBackHandler
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.sloopworks.dayfold.client.ui.loading.rememberReduceMotion
import kotlin.coroutines.cancellation.CancellationException
import com.sloopworks.dayfold.client.cards.CardAction
import com.sloopworks.dayfold.client.cards.PlatformUriHandler
import com.sloopworks.dayfold.client.cards.DetailScreen
import com.sloopworks.dayfold.client.cards.LocalAnimatedVisibilityScope
import com.sloopworks.dayfold.client.cards.LocalSharedTransitionScope
import com.sloopworks.dayfold.client.theme.DayfoldTheme
import org.reduxkotlin.compose.SelectorStore
import org.reduxkotlin.compose.selectorState

// Feed & Hubs share ONE key so the persistent TabShell (bar) does not cross-fade on a tab
// switch; the tab slide is TabShell's inner AnimatedContent. Every other route is its own key.
private fun navGroupKey(route: Route): String =
  if (route == Route.Feed || route == Route.Hubs) "tabs" else route.name

// Route a card's CardAction: OpenDetail = in-app nav → store; everything else =
// an OS handoff → the shell's PlatformActions. Extracted (non-Composable) so the
// split is unit-testable. Returns Unit (store.dispatch returns the action).
internal fun routeCardAction(
  store: SelectorStore<AppState>,
  commands: DayfoldCommandPort,
  platformActions: StablePlatformActions,
  activeFamilyId: String?,
  fromFeedDetail: Boolean,
  action: CardAction,
) {
  when (action) {
    is CardAction.OpenDetail -> store.dispatch(NavToDetail(action.cardId))
    is CardAction.OpenHub -> {  // cross-surface deep-link arrival
      // remember we came from a Feed card detail so back returns there (not the hub list)
      val destination = if (fromFeedDetail) HubReturnDestination.FEED_DETAIL else HubReturnDestination.HUB_LIST
      if (activeFamilyId != null) {
        commands.openHub(activeFamilyId, action.hubId, action.focusBlockId, destination)
      }
    }
    else -> platformActions.perform(action)
  }
}

// f(store.state) -> UI through narrow redux-kotlin-compose projections. The shell observes
// navigation/back ownership only; each active route installs its own immutable feature selector.
// Every platform renders this one connected composable under the Dayfold theme (ADR 0022 D5).
//
// AUTH-S5 route gate (auth) + CL content host (feed/detail) integrated: a pure
// when(route) gate (no nav library, ADR 0013). The host supplies one root-scoped
// selector store, one stable application-command boundary, and one stable native handoff boundary.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FeedApp(
  store: SelectorStore<AppState>,
  commands: DayfoldCommandPort,
  platformActions: StablePlatformActions,
) {
  // ADR 0036: one-time Coil image-loader setup (Ktor network fetcher + crossfade).
  // Idempotent; runs before the first AsyncImage composes. URLs are still gated by
  // MediaValidation before Coil sees them.
  remember { setupImageLoader(); 0 }
  val shell = rememberAppShellState(store)
  // Now-feed scroll position, hoisted to FeedApp (always composed) so it survives EVERY nav that
  // recomposes the feed away: the feed↔detail swap AND the Feed↔Hubs tab swap AND a Feed→Account
  // excursion. AnimatedContent has no SaveableStateHolder, so a state owned lower down (ContentHost,
  // inside TabShell's tab AnimatedContent) is discarded on a tab switch → back would land at the top.
  val feedListState = rememberLazyListState()
  // Same for the Hubs list (see feedListState): hoisted so the hub-list scroll survives the
  // Hubs↔Now tab swap, a Hubs→Account excursion, and opening a hub detail.
  val hubListState = rememberLazyListState()
  // One stable handler (remembered so feed/detail stay skippable): OpenDetail is
  // in-app nav → dispatched to the store; every other CardAction is an OS handoff
  // → the shell's PlatformActions.
  val handle = remember(
    store,
    commands,
    platformActions,
    shell.activeFamilyId,
    shell.route,
    shell.detailCardId,
  ) {
    fun(action: CardAction) = routeCardAction(
      store = store,
      commands = commands,
      platformActions = platformActions,
      activeFamilyId = shell.activeFamilyId,
      fromFeedDetail = shell.route == Route.Feed && shell.detailCardId != null,
      action = action,
    )
  }
  // Inline body-link taps (LinkAnnotation.Url, no listener) open via LocalUriHandler
  // — route them through the shell's vetted PlatformActions.openUri instead of the
  // default system handler. Provided OUTSIDE DayfoldTheme so its return@DayfoldTheme
  // labels stay valid; covers feed, detail, AND hubs (one composition subtree).
  val uriHandler = remember(platformActions) { PlatformUriHandler(platformActions::openUri) }
  val devSignIn = remember(platformActions) {
    if (platformActions.supportsDevSignIn) platformActions::devSignIn else null
  }
  CompositionLocalProvider(LocalUriHandler provides uriHandler) {
  DayfoldTheme {
    // Predictive-back P0: every non-detail screen routes system back to "up one
    // level" (without this, system back exits the app at targetSdk >= 36). Disabled
    // when a feed detail is open — ContentHost's PredictiveBackHandler owns that.
    // Hub-detail back is special-cased: it must ALSO run onCloseHub() to cancel the
    // HubEngine DB tree subscription (mirrors HubsHost's on-screen arrow); every other
    // route — incl. closing the audience overlay — is pure and goes through `Back`.
    // ContentHost owns back ONLY when a Feed card detail is showing (route==Feed). Elsewhere —
    // incl. a hub deep-linked from a detail (route==Hubs, detailStack still non-empty) — the shell
    // owns it. The old guard `currentDetailCard == null` disabled the shell whenever the stack was
    // non-empty regardless of route, so back on the deep-linked hub was unhandled → the app exited.
    BackHandler(enabled = shell.backTarget != null && shell.backTarget != BackTarget.FeedDetail) {
      when (shell.backTarget) {
        BackTarget.HubList -> shell.currentHubId?.let { id ->
          commands.closeHub(id, HubReturnDestination.HUB_LIST)
        }
        BackTarget.FeedDetailFromHub -> shell.currentHubId?.let { id ->
          commands.closeHub(id, HubReturnDestination.FEED_DETAIL)
        }
        BackTarget.Audience -> store.dispatch(CloseAudienceSheet)
        BackTarget.Timeline -> store.dispatch(CloseTimelineDetail)
        BackTarget.Account -> store.dispatch(CloseAccount)
        BackTarget.Members -> {
          if (shell.route == Route.Invite) store.dispatch(InviteDismissed)
          else store.dispatch(OpenAccount)
        }
        BackTarget.DeviceFlow -> store.dispatch(CloseDeviceFlow)
        BackTarget.JoinInvite -> store.dispatch(JoinDismissed)
        BackTarget.FeedDetail, null -> Unit
      }
    }
    // Deep-link resume beat: after sign-in, MembershipsLoaded has already set the
    // gate route, so show "Finishing…" over it while the stashed code is looked up.
    if (shell.deviceResuming) { SafeArea { DeviceFinishingScreen() }; return@DayfoldTheme }
    // Feed/Hubs render their own Scaffold (TopAppBar + NavigationBar consume the
    // system-bar insets) and intentionally bleed edge-to-edge → render them bare.
    // Every other route is a plain, Scaffold-less screen, so wrap it once in SafeArea
    // (safeDrawing = status/nav bars + display cutout + IME) instead of touching each.
    val reduceMotion = rememberReduceMotion()
    val slidePx = with(LocalDensity.current) { 30.dp.roundToPx() }
    AnimatedContent(
      targetState = shell.route,
      transitionSpec = {
        // Group Feed/Hubs so intra-tab switches don't animate at THIS layer — TabShell's
        // inner AnimatedContent owns that shared-axis-X slide.
        if (navGroupKey(initialState) == navGroupKey(targetState))
          NavAnim.Snap.toContentTransform(slidePx)
        else navAnimFor(initialState, targetState, reduceMotion).toContentTransform(slidePx)
      },
      contentKey = { navGroupKey(it) },
      label = "app-nav",
    ) { route ->
    when (route) {
      // Feed + Hubs share ONE persistent TabShell: the bottom bar stays put while the tab
      // content slides (shared-axis-X, Task 3 / ADR 0051). A card detail is a Feed substate,
      Route.Feed, Route.Hubs -> TabShell(
        route = route,
        reduceMotion = reduceMotion,
        // Bar hides for full-screen details, ROUTE-SCOPED: on Feed a card detail hides it; on Hubs
        // only the timeline overlay does (hub list + hub detail keep the bar — incl. a card-deep-
        // linked hub detail where detailStack is retained for back). They morph to full screen (ADR 0050).
        barVisible = if (route == Route.Feed) shell.detailCardId == null else !shell.timelineDetailOpen,
        onNow = { store.dispatch(OpenFeed) },
        onHubs = { commands.openHubs() },
        feedContent = {
          ContentHost(
            store = store,
            detailCardId = shell.detailCardId,
            handle = handle,
            onConnectDevice = { store.dispatch(OpenEnterCode) },
            onNavHubs = { commands.openHubs() },
            onRefresh = commands::refresh,
            onNowShown = commands::nowShown,
            feedListState = feedListState,
          )
        },
        hubsContent = {
          HubsHost(
            store = store,
            commands = commands,
            onCardAction = handle,
            hubListState = hubListState,
          )
        },
      )
      else -> SafeArea {
        RouteHost(
          route = route,
          store = store,
          commands = commands,
          platformActions = platformActions,
          devSignIn = devSignIn,
        )
      }
    }
    }
  }
  }
}
