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
import org.reduxkotlin.Store
import org.reduxkotlin.compose.StableStore
import org.reduxkotlin.compose.selectorState

// Feed & Hubs share ONE key so the persistent TabShell (bar) does not cross-fade on a tab
// switch; the tab slide is TabShell's inner AnimatedContent. Every other route is its own key.
private fun navGroupKey(route: Route): String =
  if (route == Route.Feed || route == Route.Hubs) "tabs" else route.name

// Route a card's CardAction: OpenDetail = in-app nav → store; everything else =
// an OS handoff → the shell's PlatformActions. Extracted (non-Composable) so the
// split is unit-testable. Returns Unit (store.dispatch returns the action).
internal fun routeCardAction(
  store: Store<AppState>,
  commands: StableDayfoldCommands,
  platformActions: StablePlatformActions,
  action: CardAction,
) {
  when (action) {
    is CardAction.OpenDetail -> commands.dispatch(NavToDetail(action.cardId))
    is CardAction.OpenHub -> {  // cross-surface deep-link arrival
      // remember we came from a Feed card detail so back returns there (not the hub list)
      val fromDetail = store.state.route == Route.Feed && store.state.detailStack.isNotEmpty()
      val destination = if (fromDetail) HubReturnDestination.FEED_DETAIL else HubReturnDestination.HUB_LIST
      val familyId = store.state.activeFamilyId
      if (familyId != null) {
        commands.openHub(familyId, action.hubId, action.focusBlockId, destination)
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
// when(route) gate (no nav library, ADR 0013). The host supplies one stable store,
// one stable application-command boundary, and one stable native handoff boundary.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FeedApp(
  store: StableStore<AppState>,
  commands: StableDayfoldCommands,
  platformActions: StablePlatformActions,
) {
  // ADR 0036: one-time Coil image-loader setup (Ktor network fetcher + crossfade).
  // Idempotent; runs before the first AsyncImage composes. URLs are still gated by
  // MediaValidation before Coil sees them.
  remember { setupImageLoader(); 0 }
  val rawStore = store.value
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
  val handle = remember(rawStore, commands, platformActions) {
    fun(action: CardAction) = routeCardAction(rawStore, commands, platformActions, action)
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
        BackTarget.Audience -> rawStore.dispatch(CloseAudienceSheet)
        BackTarget.Timeline -> rawStore.dispatch(CloseTimelineDetail)
        BackTarget.Account -> rawStore.dispatch(CloseAccount)
        BackTarget.Members -> {
          if (shell.route == Route.Invite) rawStore.dispatch(InviteDismissed)
          else rawStore.dispatch(OpenAccount)
        }
        BackTarget.DeviceFlow -> rawStore.dispatch(CloseDeviceFlow)
        BackTarget.JoinInvite -> rawStore.dispatch(JoinDismissed)
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
        onNow = { commands.dispatch(OpenFeed) },
        onHubs = { commands.openHubs() },
        feedContent = {
          ContentHost(
            store = store,
            detailCardId = shell.detailCardId,
            handle = handle,
            onConnectDevice = { commands.dispatch(OpenEnterCode) },
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

/** Installs exactly one feature subscription for the active non-tab route. */
@Composable
private fun RouteHost(
  route: Route,
  store: StableStore<AppState>,
  commands: StableDayfoldCommands,
  platformActions: StablePlatformActions,
  devSignIn: (() -> Unit)?,
) {
  val rawStore = store.value
  when (route) {
    Route.Loading -> SplashScreen()
    Route.SignIn -> {
      val state by rawStore.selectorState(::signInViewState)
      if (state.pendingDeviceLink != null) {
        DeviceResumeScreen(onProvider = platformActions::signIn)
      } else {
        SignInScreen(
          pendingProvider = state.pendingProvider,
          error = state.error,
          onProvider = platformActions::signIn,
          onDevSignIn = devSignIn,
        )
      }
    }
    Route.AuthError -> {
      val error by rawStore.selectorState(::authErrorMessage)
      AuthErrorScreen(
        message = error,
        onRetry = commands::retryAuth,
        onSignOut = commands::signOut,
      )
    }
    Route.CreateFamily -> {
      val state by rawStore.selectorState(::createFamilyViewState)
      CreateFamilyScreen(
        busy = state.busy,
        error = state.error,
        onCreate = commands::createFamily,
        onJoinInvite = { commands.dispatch(OpenJoinInvite) },
      )
    }
    Route.JoinInvite -> {
      val state by rawStore.selectorState(::joinInviteViewState)
      JoinInviteScreen(
        state,
        onJoin = commands::redeemInvite,
        onDismiss = { commands.dispatch(JoinDismissed) },
      )
    }
    Route.EnterCode -> {
      val state by rawStore.selectorState(::enterCodeViewState)
      EnterCodeScreen(
        state,
        onLookup = commands::lookupDevice,
        onBack = { commands.dispatch(CloseDeviceFlow) },
        onScan = if (qrScanSupported) ({ commands.dispatch(OpenScan) }) else null,
      )
    }
    Route.ScanPrimer -> {
      val requestCamera = rememberCameraPermissionRequester { granted ->
        commands.dispatch(if (granted) ScanPermissionGranted else ScanPermissionDenied)
      }
      ScanPrimerScreen(
        onAllow = requestCamera,
        onEnterCode = { commands.dispatch(OpenEnterCode) },
        onClose = { commands.dispatch(CloseDeviceFlow) },
      )
    }
    Route.ScanDevice -> ScanDeviceScreen(
      onCode = commands::lookupDevice,
      onEnterManually = { commands.dispatch(OpenEnterCode) },
      onClose = { commands.dispatch(CloseDeviceFlow) },
    )
    Route.ScanDenied -> ScanDeniedScreen(
      onOpenSettings = platformActions::openAppSettings,
      onEnterCode = { commands.dispatch(OpenEnterCode) },
      onClose = { commands.dispatch(CloseDeviceFlow) },
    )
    Route.AuthorizeDevice -> {
      val state by rawStore.selectorState(::authorizeDeviceViewState)
      when (state.outcome) {
        "denied" -> DeviceDeniedScreen(onDone = { commands.dispatch(CloseDeviceFlow) })
        "expired" -> DeviceExpiredScreen(
          onRetry = { commands.dispatch(OpenEnterCode) },
          onDone = { commands.dispatch(CloseDeviceFlow) },
        )
        "approved" -> DeviceApprovedConfirm(onDone = { commands.dispatch(CloseDeviceFlow) })
        else -> AuthorizeDeviceScreen(
          state,
          onApprove = { familyId, hubIds ->
            state.pendingDevice?.userCode?.let { code -> commands.approveDevice(familyId, code, hubIds) }
          },
          onDeny = { familyId ->
            state.pendingDevice?.userCode?.let { code -> commands.denyDevice(familyId, code) }
          },
          onCancel = { commands.dispatch(CloseDeviceFlow) },
        )
      }
    }
    Route.Account -> {
      val state by rawStore.selectorState(::accountViewState)
      AccountScreen(
        state,
        onSignOut = commands::signOut,
        onClose = { commands.dispatch(CloseAccount) },
        onOpenMembers = { commands.dispatch(OpenMembers) },
        onOpenDevices = { commands.dispatch(OpenDevices) },
        onOpenProximity = { commands.dispatch(OpenProximity) },
        onUpdateAvatar = commands::updateAvatar,
        onUpdateName = commands::updateDisplayName,
      )
    }
    Route.Proximity -> {
      val state by rawStore.selectorState(::proximityViewState)
      ProximitySettingsHost(
        config = state.config,
        permission = state.permission,
        onSetNotifConfig = {
          commands.setNotificationConfig(it)
          if (it.enabled) platformActions.requestProximityPermissions()
        },
        onOpenPermission = platformActions::openAppSettings,
        onBack = { commands.dispatch(CloseProximity) },
      )
    }
    Route.Devices -> {
      val state by rawStore.selectorState(::devicesViewState)
      DevicesScreen(
        state,
        onLoad = commands::loadDevices,
        onRevoke = commands::revokeDevice,
        onBack = { commands.dispatch(OpenAccount) },
        onConnectDevice = { commands.dispatch(OpenEnterCode) },
      )
    }
    Route.Members -> {
      val state by rawStore.selectorState(::membersViewState)
      MembersScreen(
        state,
        onApprove = { uid -> state.activeFamilyId?.let { commands.approveMember(it, uid) } },
        onDecline = { uid -> state.activeFamilyId?.let { commands.declineMember(it, uid) } },
        onLoad = { state.activeFamilyId?.let(commands::loadApprovals) },
        onLoadMembers = { state.activeFamilyId?.let(commands::loadMembers) },
        onRemoveMember = { uid -> state.activeFamilyId?.let { commands.removeMember(it, uid) } },
        onInvite = { commands.dispatch(OpenInvite) },
        onBack = { commands.dispatch(OpenAccount) },
      )
    }
    Route.Invite -> {
      val state by rawStore.selectorState(::inviteViewState)
      LaunchedEffect(state.activeFamilyId) {
        state.activeFamilyId?.let(commands::loadApprovals)
      }
      InviteScreen(
        state,
        onMode = { commands.dispatch(InviteModeSelected(it)) },
        onMint = { mode -> state.activeFamilyId?.let { commands.mintInvite(it, mode) } },
        onRevoke = { id -> state.activeFamilyId?.let { commands.revokeInvite(it, id) } },
        onApprove = { uid -> state.activeFamilyId?.let { commands.approveMember(it, uid) } },
        onDecline = { uid -> state.activeFamilyId?.let { commands.declineMember(it, uid) } },
        onBack = { commands.dispatch(InviteDismissed) },
      )
    }
    Route.Feed, Route.Hubs -> Unit
  }
}

// Insets the Scaffold-less routes into the safe area. safeDrawing is the union of
// the status/navigation bars, the display cutout, and the IME — so one wrapper keeps
// content off the system bars AND lifts text fields above the keyboard. Requires
// edge-to-edge (Android: enableEdgeToEdge in MainActivity; iOS: ComposeUIViewController
// reports the safe area) for the insets to be non-zero.
@Composable
private fun SafeArea(content: @Composable () -> Unit) {
  Box(Modifier.fillMaxSize().safeDrawingPadding()) { content() }
}

// CL-7b container transform, gesture-driven (predictive back, P1). The card morphs
// into the detail via SharedTransitionLayout (key "card-$id"); a SeekableTransitionState
// is the single animator. Non-gesture transitions (open detail, hero-arrow back, deep
// pops) are driven by the redux→seekable LaunchedEffect; the back GESTURE scrubs the
// seekable with the finger and commits (NavBack) or cancels (animate back).
// The morph is edge-INDEPENDENT by design: it targets the card's fixed feed position,
// so BackEventCompat.swipeEdge / RTL do not change it (no edge-signed shift to invert).
// Threading swipeEdge is a P2 prerequisite only for the deferred full-screen route anim.
// CMP 1.11.1 deprecates this API in favor of NavigationEvent; retained per design D2.
@Suppress("DEPRECATION")
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun ContentHost(
  store: StableStore<AppState>,
  detailCardId: String?,
  handle: (CardAction) -> Unit,
  onConnectDevice: () -> Unit = {},
  onNavHubs: () -> Unit = {},
  onRefresh: () -> Unit = {},
  onNowShown: (Set<String>) -> Unit = {},
  feedListState: LazyListState = rememberLazyListState(),
) {
  val rawStore = store.value
  val targetKey: String? = detailCardId            // top of the detail stack (null = feed)
  val reduceMotion = rememberReduceMotion()
  // feedListState is hoisted to FeedApp (survives the tab swap too) and threaded in; the default
  // keeps ContentHost usable standalone in tests. Passed to FeedScreen's Now-feed LazyColumn.

  // Back GESTURE (predictive back): the OS drives the window "peek" during the drag; we do
  // NOT scrub the transition ourselves. Driving AnimatedContent from a SeekableTransitionState
  // silently drops the sharedBounds container-transform (the bounds snap to target → a flat
  // crossfade) in androidx.compose.animation 1.11.x–1.12. So we commit on release → NavBack,
  // and the plain AnimatedContent below plays the reverse morph. Cancel = no-op (rethrow).
  PredictiveBackHandler(enabled = detailCardId != null) { progress ->
    try {
      progress.collect { /* OS renders the peek; the container-transform plays on commit */ }
      rawStore.dispatch(NavBack)                 // COMMIT
    } catch (e: CancellationException) {
      throw e                                    // CANCEL — no state change to undo; must rethrow
    }
  }

  // CL-7b container transform via a plain, state-driven AnimatedContent keyed on `targetKey`
  // (the top of the detail stack). A SeekableTransitionState here does NOT render the
  // sharedBounds morph (see above) — the plain API does. Tap-open, hero-arrow-back, deep
  // pops, and predictive-back-commit all flow through `targetKey`. The card morphs into the
  // detail via SharedTransitionLayout (key "card-$id"); reduced-motion → snap (dur 0).
  SharedTransitionLayout {
    AnimatedContent(
      targetState = targetKey,
      contentKey = { it },
      transitionSpec = {
        // asymmetric fade+slide (NavMotion.HeroMs open / NavMotion.StandardMs close); the default AnimatedContent spec
        // adds a scaleIn that fights the shared morph, so specify it explicitly.
        val opening = targetState != null
        val dur = if (reduceMotion) NavMotion.ReducedMs else if (opening) NavMotion.HeroMs else NavMotion.StandardMs
        (fadeIn(tween(dur)) + slideInVertically(tween(dur)) { h -> h / 16 }) togetherWith fadeOut(tween(dur))
      },
    ) { id ->
      CompositionLocalProvider(
        LocalSharedTransitionScope provides this@SharedTransitionLayout,
        LocalAnimatedVisibilityScope provides this@AnimatedContent,
      ) {
        if (id != null) {
          val detail by rawStore.selectorState(::feedDetailViewState)
          detail?.let {
            DetailScreen(
              it.card,
              hubName = it.hubName,
              onBack = { rawStore.dispatch(NavBack) },
              onAction = handle,
            )
          }
        } else {
          val feed by rawStore.selectorState(::feedViewState)
          FeedScreen(
            feed,
            onAction = handle,
            onOpenAccount = { rawStore.dispatch(OpenAccount) },
            onConnectDevice = onConnectDevice,
            onNavHubs = onNavHubs,
            onRefresh = onRefresh,
            onShown = onNowShown,
            listState = feedListState,
          )
        }
      }
    }
  }
}

// Hubs surface host (ADR 0006): list ↔ detail substate driven by currentHubId.
// A LaunchedEffect fetches the list on entry; the bottom nav flips back to Feed.
@Composable
private fun HubsHost(
  store: StableStore<AppState>,
  commands: StableDayfoldCommands,
  onCardAction: (CardAction) -> Unit = {},
  hubListState: LazyListState = rememberLazyListState(),
) {
  val rawStore = store.value
  val route by rawStore.selectorState(::hubRouteState)
  // ADR 0045: timeline open/close callbacks dispatch to the store; the detail scale is state
  val onOpenTimeline: (TimelineScale) -> Unit = { scale -> rawStore.dispatch(OpenTimelineDetail(scale)) }
  val onCloseTimeline: () -> Unit = { rawStore.dispatch(CloseTimelineDetail) }
  val reduceMotion = rememberReduceMotion()
  val slidePx = with(LocalDensity.current) { 30.dp.roundToPx() }
  androidx.compose.foundation.layout.Box {
    AnimatedContent(
      targetState = route.currentHubId != null,   // false = list, true = detail
      transitionSpec = {
        val anim = if (reduceMotion) NavAnim.Snap
          else if (targetState) NavAnim.SharedZForward else NavAnim.SharedZBackward
        anim.toContentTransform(slidePx)
      },
      contentKey = { it },
      label = "hub-list-detail",
    ) { showDetail ->
      if (showDetail) {
        val state by rawStore.selectorState(::hubDetailViewState)
        HubDetailScreen(
          // the on-screen back arrow mirrors system back: cross back to the originating detail when deep-linked.
          state,
          onBack = {
            route.currentHubId?.let { id ->
              commands.closeHub(
                id,
                if (route.fromFeedDetail) HubReturnDestination.FEED_DETAIL else HubReturnDestination.HUB_LIST,
              )
            }
          },
          onOpenAudience = { route.currentHubId?.let { hubId ->
            rawStore.dispatch(OpenAudienceSheet)
            route.activeFamilyId?.let { familyId ->
              commands.loadAudience(familyId, hubId)
            }
          } },
          onRetry = { route.currentHubId?.let { id ->
            route.activeFamilyId?.let { familyId ->
              commands.openHub(familyId, id)
            }
          } },
          onToggleItem = { blockId, itemId, done -> route.activeFamilyId?.let { familyId ->
            commands.toggleItem(familyId, blockId, itemId, done)
          } },
          onRetryBlock = { blockId -> route.activeFamilyId?.let { familyId ->
            commands.retryBlock(familyId, blockId)
          } },
          onSyncNow = commands::refresh,
          onDeleteBlock = { blockId -> route.activeFamilyId?.let { familyId ->
            commands.deleteBlock(familyId, blockId)
          } },
          onHideBlock = { blockId -> route.activeFamilyId?.let { familyId ->
            commands.hideBlock(familyId, blockId)
          } },
          onUnhideBlock = { blockId -> route.activeFamilyId?.let { familyId ->
            commands.unhideBlock(familyId, blockId)
          } },
          onSetShowHidden = { rawStore.dispatch(SetShowHidden(it)) },
          onOpenTimeline = onOpenTimeline, onCloseTimeline = onCloseTimeline, onCardAction = onCardAction,
        )
      } else {
        val state = rememberHubListViewState(store)
        LaunchedEffect(Unit) {
          if (!state.hasAnyHubs) commands.loadHubs()
        }
        HubListScreen(
          state,
          onOpenHub = { hubId -> route.activeFamilyId?.let { familyId ->
            commands.openHub(familyId, hubId)
          } },
          onFilter = { rawStore.dispatch(SetHubFilter(it)) },
          onRetry = commands::loadHubs,
          hubListState = hubListState,
        )
      }
    }
    // ADR 0053 DC5: once the audience loads, an owner/co-owner (canManage) gets the full
    // People management sheet; everyone else (incl. while aud is still loading/erroring)
    // keeps the existing read-only WhoCanSeeSheet — unchanged.
    if (route.audienceSheetOpen) {
      val audienceState by rawStore.selectorState(::hubAudienceViewState)
      val aud = audienceState.audience
      if (aud != null && aud.canManage) {
        HubPeopleSheet(
          audience = aud,
          onSetRole = { uid, role -> route.currentHubId?.let { hubId -> route.activeFamilyId?.let { familyId ->
            commands.setHubRole(familyId, hubId, uid, role)
          } } },
          onRemove = { uid -> route.currentHubId?.let { hubId -> route.activeFamilyId?.let { familyId ->
            commands.removeHubParticipant(familyId, hubId, uid)
          } } },
          onSetVisibility = { vis -> route.currentHubId?.let { hubId -> route.activeFamilyId?.let { familyId ->
            commands.setHubVisibility(familyId, hubId, vis)
          } } },
          onDismiss = { rawStore.dispatch(CloseAudienceSheet) },
          // ADR 0053 DC5 code-review fix — surface a failed role/remove/visibility write
          // (HubEngine dispatches HubManageFailed onto this same slot) instead of silently
          // dropping it; the sheet stays pure, this is just an input string.
          errorMessage = audienceState.error,
        )
      } else {
        WhoCanSeeSheet(audienceState, onClose = { rawStore.dispatch(CloseAudienceSheet) }, onRetryAudience = {
          route.currentHubId?.let { hubId -> route.activeFamilyId?.let { familyId ->
            commands.loadAudience(familyId, hubId)
          } }
        })  // overlay
      }
    }
  }
}
