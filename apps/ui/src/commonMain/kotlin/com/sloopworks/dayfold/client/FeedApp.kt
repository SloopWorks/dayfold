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
import org.reduxkotlin.compose.selectorState

// Feed & Hubs share ONE key so the persistent TabShell (bar) does not cross-fade on a tab
// switch; the tab slide is TabShell's inner AnimatedContent. Every other route is its own key.
private fun navGroupKey(route: Route): String =
  if (route == Route.Feed || route == Route.Hubs) "tabs" else route.name

// Route a card's CardAction: OpenDetail = in-app nav → store; everything else =
// an OS handoff → the shell's PlatformActions. Extracted (non-Composable) so the
// split is unit-testable. Returns Unit (store.dispatch returns the action).
internal fun routeCardAction(
  store: Store<AppState>, onPlatformAction: (CardAction) -> Unit, action: CardAction,
  onOpenHub: (String, String?) -> Unit = { _, _ -> },
) {
  when (action) {
    is CardAction.OpenDetail -> store.dispatch(NavToDetail(action.cardId))
    is CardAction.OpenHub -> {  // cross-surface deep-link arrival
      // remember we came from a Feed card detail so back returns there (not the hub list)
      val fromDetail = store.state.route == Route.Feed && store.state.detailStack.isNotEmpty()
      store.dispatch(OpenHubs)
      if (fromDetail) store.dispatch(SetHubReturnToDetail(true))
      onOpenHub(action.hubId, action.focusBlockId)
    }
    else -> onPlatformAction(action)
  }
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
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FeedApp(
  store: Store<AppState>,
  onPlatformAction: (CardAction) -> Unit = {},
  onOpenUri: (String) -> Unit = {},     // inline body-link tap → shell PlatformActions.openUri

  onSignIn: (String) -> Unit = {},
  onDevSignIn: (() -> Unit)? = null,    // debug-only fake sign-in (null → hidden, e.g. release/iOS)
  onCreateFamily: (String) -> Unit = {},
  onSignOut: () -> Unit = {},
  onRetry: () -> Unit = {},
  onRedeemInvite: (String) -> Unit = {},
  onLoadApprovals: () -> Unit = {},
  onApproveMember: (String) -> Unit = {},
  onDeclineMember: (String) -> Unit = {},
  onLoadMembers: () -> Unit = {},
  onRemoveMember: (String) -> Unit = {},
  onMintInvite: (String) -> Unit = {},  // owner mint (qr|link) → AuthEngine.mintInvite
  onRevokeInvite: (String) -> Unit = {},// owner revoke an outstanding invite
  onUpdateAvatar: (String?, String?) -> Unit = { _, _ -> },  // Delta A / Task 5 — AccountScreen sheet Save → AuthEngine.updateAvatar
  onLoadDevices: () -> Unit = {},
  onRevokeDevice: (String) -> Unit = {},
  onLookupDevice: (String) -> Unit = {},
  onApproveDevice: (String) -> Unit = {},
  onDenyDevice: (String) -> Unit = {},
  onOpenAppSettings: () -> Unit = {},   // Tier 2: deep-link to the OS app-settings (camera permission)
  onRefresh: () -> Unit = {},           // feed pull/retry → syncEngine.syncNow()
  // ADR 0043 §2b carryover: the Now feed reports its currently-surfaced subjects → NowEngine
  // starts each one's anti-nag decay clock (record-shown effect). Default no-op keeps screens
  // snapshot-testable; the surfacing write NEVER happens on the render path (unidirectional).
  onNowShown: (Set<String>) -> Unit = {},
  onLoadHubs: () -> Unit = {},          // Hubs (ADR 0006): list fetch (HubEngine.loadHubs)
  onOpenHub: (String, String?) -> Unit = { _, _ -> },  // tap/deep-link a hub → load tree (+ focus block)
  onCloseHub: () -> Unit = {},          // detail → list: cancel the DB tree subscription (HubEngine.closeHub)
  onLoadAudience: (String) -> Unit = {},// "who can see" sheet → load the audience (HubEngine.loadAudience)
  // ADR 0053 DC5 — the People management sheet (owner/co-owner only, HubEngine.setParticipant/
  // removeParticipant/setVisibility). onOpenAddPeople is a stub — the "Add people" member-picker
  // sub-flow (designs/Account-ACL-Phone.dc.html view=add-people) is a DC5 follow-up, not yet built.
  onSetHubRole: (hubId: String, uid: String, role: String) -> Unit = { _, _, _ -> },
  onRemoveHubParticipant: (hubId: String, uid: String) -> Unit = { _, _ -> },
  onSetHubVisibility: (hubId: String, visibility: String) -> Unit = { _, _ -> },
  onOpenAddPeople: () -> Unit = {},
  // Slice 4 (ADR 0038): member checklist writes. onToggleItem(blockId,itemId,done) →
  // HubEngine.toggleItem → ContentStore.enqueueBlockToggle; onRetryBlock re-queues a
  // block parked 'failed'. Default no-ops keep screens snapshot-testable in isolation.
  onToggleItem: (String, String, Boolean) -> Unit = { _, _, _ -> },
  onRetryBlock: (String) -> Unit = {},
  // Slice 5b (ADR 0038 §W4/§W5): onDeleteBlock → HubEngine.deleteBlock (author-gated egress);
  // onHideBlock/onUnhideBlock → local-only HubEngine.hide/unhideBlock. The "Show hidden" toggle
  // is pure store state (like the hub filter) — dispatched directly in HubsHost, no shell seam.
  onDeleteBlock: (String) -> Unit = {},
  onHideBlock: (String) -> Unit = {},
  onUnhideBlock: (String) -> Unit = {},
  // ADR 0044 Phase B: device-local background-proximity config write (toggle / quiet-hours / daily-cap).
  // UI → onSetNotifConfig → ContentStore.setNotifConfig → flow → NotifConfigLoaded (no optimistic
  // UI→store path); the shell's notifConfigFlow reaction arms/disarms geofences + exact alarms.
  onSetNotifConfig: (NotifConfig) -> Unit = {},
) {
  // ADR 0036: one-time Coil image-loader setup (Ktor network fetcher + crossfade).
  // Idempotent; runs before the first AsyncImage composes. URLs are still gated by
  // MediaValidation before Coil sees them.
  remember { setupImageLoader(); 0 }
  val state by store.selectorState { it }
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
  val handle = remember(store, onPlatformAction, onOpenHub) {
    fun(action: CardAction) = routeCardAction(store, onPlatformAction, action, onOpenHub)
  }
  // Inline body-link taps (LinkAnnotation.Url, no listener) open via LocalUriHandler
  // — route them through the shell's vetted PlatformActions.openUri instead of the
  // default system handler. Provided OUTSIDE DayfoldTheme so its return@DayfoldTheme
  // labels stay valid; covers feed, detail, AND hubs (one composition subtree).
  val uriHandler = remember(onOpenUri) { PlatformUriHandler(onOpenUri) }
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
    BackHandler(enabled = appHandlesBack(state) && !(state.route == Route.Feed && currentDetailCard(state) != null)) {
      when (backAction(state)) {
        CloseHub -> { onCloseHub(); store.dispatch(CloseHub) }
        CloseHubToFeed -> { onCloseHub(); store.dispatch(CloseHubToFeed) }   // cancel the tree sub, then cross back
        else -> store.dispatch(Back)
      }
    }
    // Deep-link resume beat: after sign-in, MembershipsLoaded has already set the
    // gate route, so show "Finishing…" over it while the stashed code is looked up.
    if (state.deviceResuming) { SafeArea { DeviceFinishingScreen() }; return@DayfoldTheme }
    // Feed/Hubs render their own Scaffold (TopAppBar + NavigationBar consume the
    // system-bar insets) and intentionally bleed edge-to-edge → render them bare.
    // Every other route is a plain, Scaffold-less screen, so wrap it once in SafeArea
    // (safeDrawing = status/nav bars + display cutout + IME) instead of touching each.
    val reduceMotion = rememberReduceMotion()
    val slidePx = with(LocalDensity.current) { 30.dp.roundToPx() }
    AnimatedContent(
      targetState = state.route,
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
        barVisible = if (route == Route.Feed) currentDetailCard(state) == null else state.timelineDetail == null,
        onNow = { store.dispatch(OpenFeed) },
        onHubs = { store.dispatch(OpenHubs); onLoadHubs() },
        feedContent = {
          ContentHost(
            store, state, handle,
            onConnectDevice = { store.dispatch(OpenEnterCode) },
            onNavHubs = { store.dispatch(OpenHubs); onLoadHubs() },
            onRefresh = onRefresh,
            onNowShown = onNowShown,
            feedListState = feedListState,
          )
        },
        hubsContent = {
          HubsHost(store, state, onLoadHubs = onLoadHubs, onOpenHub = onOpenHub, onCloseHub = onCloseHub, onLoadAudience = onLoadAudience, onToggleItem = onToggleItem, onRetryBlock = onRetryBlock, onSyncNow = onRefresh, onDeleteBlock = onDeleteBlock, onHideBlock = onHideBlock, onUnhideBlock = onUnhideBlock, onCardAction = handle, hubListState = hubListState,
            onSetHubRole = onSetHubRole, onRemoveHubParticipant = onRemoveHubParticipant, onSetHubVisibility = onSetHubVisibility, onOpenAddPeople = onOpenAddPeople)
        },
      )
      else -> SafeArea { when (route) {
      Route.Loading -> SplashScreen()
      // A deep-link tapped before sign-in shows the branded resume screen instead
      // of the plain sign-in (same providers; resumes onto AuthorizeDevice after).
      Route.SignIn ->
        if (state.pendingDeviceLink != null) DeviceResumeScreen(onProvider = onSignIn)
        else SignInScreen(pendingProvider = state.pendingProvider, error = state.authError, onProvider = onSignIn, onDevSignIn = onDevSignIn)
      Route.AuthError -> AuthErrorScreen(message = state.authError, onRetry = onRetry, onSignOut = onSignOut)
      Route.CreateFamily -> CreateFamilyScreen(
        busy = state.authBusy, error = state.authError,
        onCreate = onCreateFamily, onJoinInvite = { store.dispatch(OpenJoinInvite) },
      )
      Route.JoinInvite -> JoinInviteScreen(state, onJoin = onRedeemInvite, onDismiss = { store.dispatch(JoinDismissed) })
      // Feed/Hubs handled above (bare, edge-to-edge); listed here only to keep the
      // inner `when` exhaustive over Route.
      Route.Feed, Route.Hubs -> {}
      Route.EnterCode -> EnterCodeScreen(
        state, onLookup = onLookupDevice, onBack = { store.dispatch(CloseDeviceFlow) },
        // Scan toggle only where a camera exists (qrScanSupported) — null hides it
        // on desktop / until the camera actuals land (Tier 2).
        onScan = if (qrScanSupported) ({ store.dispatch(OpenScan) }) else null,
      )
      Route.ScanPrimer -> {
        // Allow → request the OS camera permission; route by the outcome.
        val requestCamera = rememberCameraPermissionRequester { granted ->
          store.dispatch(if (granted) ScanPermissionGranted else ScanPermissionDenied)
        }
        ScanPrimerScreen(
          onAllow = requestCamera,
          onEnterCode = { store.dispatch(OpenEnterCode) },
          onClose = { store.dispatch(CloseDeviceFlow) },
        )
      }
      Route.ScanDevice -> ScanDeviceScreen(
        onCode = onLookupDevice,                       // scanned code → lookup → AuthorizeDevice
        onEnterManually = { store.dispatch(OpenEnterCode) },
        onClose = { store.dispatch(CloseDeviceFlow) },
      )
      Route.ScanDenied -> ScanDeniedScreen(
        onOpenSettings = onOpenAppSettings,            // Tier 2 platform deep-link to app settings
        onEnterCode = { store.dispatch(OpenEnterCode) },
        onClose = { store.dispatch(CloseDeviceFlow) },
      )
      Route.AuthorizeDevice -> when (state.deviceOutcome) {
        "denied" -> DeviceDeniedScreen(onDone = { store.dispatch(CloseDeviceFlow) })
        "expired" -> DeviceExpiredScreen(onRetry = { store.dispatch(OpenEnterCode) }, onDone = { store.dispatch(CloseDeviceFlow) })
        "approved" -> DeviceApprovedConfirm(onDone = { store.dispatch(CloseDeviceFlow) })
        else -> AuthorizeDeviceScreen(state, onApprove = onApproveDevice, onDeny = onDenyDevice, onCancel = { store.dispatch(CloseDeviceFlow) })
      }
      Route.Account -> AccountScreen(
        state, signOutBusy = state.signOutBusy, onSignOut = onSignOut, onClose = { store.dispatch(CloseAccount) },
        onOpenMembers = { store.dispatch(OpenMembers) },
        onOpenDevices = { store.dispatch(OpenDevices) },
        onOpenProximity = { store.dispatch(OpenProximity) },
        onUpdateAvatar = onUpdateAvatar,
      )
      Route.Proximity -> ProximitySettingsHost(
        config = state.notifConfig,
        permission = state.locationPermission,
        onSetNotifConfig = onSetNotifConfig,
        onOpenPermission = onOpenAppSettings,
        onBack = { store.dispatch(CloseProximity) },
      )
      Route.Devices -> DevicesScreen(
        state, onLoad = onLoadDevices, onRevoke = onRevokeDevice, onBack = { store.dispatch(OpenAccount) },
        onConnectDevice = { store.dispatch(OpenEnterCode) },
      )
      Route.Members -> MembersScreen(
        state, onApprove = onApproveMember, onDecline = onDeclineMember,
        onLoad = onLoadApprovals, onLoadMembers = onLoadMembers, onRemoveMember = onRemoveMember,
        onInvite = { store.dispatch(OpenInvite) },
        onBack = { store.dispatch(OpenAccount) },
      )
      Route.Invite -> {
        // One GET /families/{fid}/invites feeds BOTH the outstanding-invite list and the
        // pending-joiner rows (reuses loadApprovals). InviteScreen owns its own clock.
        LaunchedEffect(Unit) { onLoadApprovals() }
        InviteScreen(
          state,
          onMode = { store.dispatch(InviteModeSelected(it)) },
          onMint = onMintInvite,
          onRevoke = onRevokeInvite,
          onApprove = onApproveMember, onDecline = onDeclineMember,
          onBack = { store.dispatch(InviteDismissed) },
        )
      }
      } }
    }
    }
  }
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
@Suppress("DEPRECATION")   // CMP 1.11.1: PredictiveBackHandler/BackEventCompat are @Deprecated (→ NavigationEvent); intentional per design D2
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun ContentHost(store: Store<AppState>, state: AppState, handle: (CardAction) -> Unit, onConnectDevice: () -> Unit = {}, onNavHubs: () -> Unit = {}, onRefresh: () -> Unit = {}, onNowShown: (Set<String>) -> Unit = {}, feedListState: LazyListState = rememberLazyListState()) {
  val detail = currentDetailCard(state)
  val targetKey: String? = detail?.id            // top of the detail stack (null = feed)
  val reduceMotion = rememberReduceMotion()
  // feedListState is hoisted to FeedApp (survives the tab swap too) and threaded in; the default
  // keeps ContentHost usable standalone in tests. Passed to FeedScreen's Now-feed LazyColumn.

  // Back GESTURE (predictive back): the OS drives the window "peek" during the drag; we do
  // NOT scrub the transition ourselves. Driving AnimatedContent from a SeekableTransitionState
  // silently drops the sharedBounds container-transform (the bounds snap to target → a flat
  // crossfade) in androidx.compose.animation 1.11.x–1.12. So we commit on release → NavBack,
  // and the plain AnimatedContent below plays the reverse morph. Cancel = no-op (rethrow).
  PredictiveBackHandler(enabled = detail != null) { progress ->
    try {
      progress.collect { /* OS renders the peek; the container-transform plays on commit */ }
      store.dispatch(NavBack)                    // COMMIT
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
        val card = id?.let { cid -> state.cards.find { it.id == cid } }
        if (card != null) {
          // Resolve the target hub's name so "PART OF THIS HUB" shows WHERE the deep-link goes
          // (target_hub_id wins over hub_ref — same precedence as hubLinkTarget). Null → fallback.
          val hubName = (card.targetHubId ?: card.hubRef)?.let { hid -> state.hubs.find { it.id == hid }?.title }
          DetailScreen(card, hubName = hubName, onBack = { store.dispatch(NavBack) }, onAction = handle)
        }
        else FeedScreen(state, onAction = handle, onOpenAccount = { store.dispatch(OpenAccount) }, onConnectDevice = onConnectDevice, onNavHubs = onNavHubs, onRefresh = onRefresh, onShown = onNowShown, listState = feedListState)
      }
    }
  }
}

// Hubs surface host (ADR 0006): list ↔ detail substate driven by currentHubId.
// A LaunchedEffect fetches the list on entry; the bottom nav flips back to Feed.
@Composable
private fun HubsHost(store: Store<AppState>, state: AppState, onLoadHubs: () -> Unit, onOpenHub: (String, String?) -> Unit, onCloseHub: () -> Unit = {}, onLoadAudience: (String) -> Unit, onToggleItem: (String, String, Boolean) -> Unit = { _, _, _ -> }, onRetryBlock: (String) -> Unit = {}, onSyncNow: () -> Unit = {}, onDeleteBlock: (String) -> Unit = {}, onHideBlock: (String) -> Unit = {}, onUnhideBlock: (String) -> Unit = {}, onCardAction: (CardAction) -> Unit = {}, hubListState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
  // ADR 0053 DC5 — People sheet management ops (owner/co-owner-gated server-side too).
  onSetHubRole: (String, String, String) -> Unit = { _, _, _ -> },
  onRemoveHubParticipant: (String, String) -> Unit = { _, _ -> },
  onSetHubVisibility: (String, String) -> Unit = { _, _ -> },
  onOpenAddPeople: () -> Unit = {},
) {
  // ADR 0045: timeline open/close callbacks dispatch to the store; the detail scale is state
  val onOpenTimeline: (TimelineScale) -> Unit = { scale -> store.dispatch(OpenTimelineDetail(scale)) }
  val onCloseTimeline: () -> Unit = { store.dispatch(CloseTimelineDetail) }
  androidx.compose.runtime.LaunchedEffect(Unit) { if (state.hubs.isEmpty()) onLoadHubs() }
  val reduceMotion = rememberReduceMotion()
  val slidePx = with(LocalDensity.current) { 30.dp.roundToPx() }
  androidx.compose.foundation.layout.Box {
    AnimatedContent(
      targetState = state.currentHubId != null,   // false = list, true = detail
      transitionSpec = {
        val anim = if (reduceMotion) NavAnim.Snap
          else if (targetState) NavAnim.SharedZForward else NavAnim.SharedZBackward
        anim.toContentTransform(slidePx)
      },
      contentKey = { it },
      label = "hub-list-detail",
    ) { showDetail ->
      if (showDetail) {
        HubDetailScreen(
          // the on-screen back arrow mirrors system back: cross back to the originating detail when deep-linked.
          state, onBack = { onCloseHub(); store.dispatch(if (state.hubFromDetail) CloseHubToFeed else CloseHub) },
          onOpenAudience = { state.currentHubId?.let { store.dispatch(OpenAudienceSheet); onLoadAudience(it) } },
          onRetry = { state.currentHubId?.let { id -> onOpenHub(id, null) } },
          onToggleItem = onToggleItem, onRetryBlock = onRetryBlock, onSyncNow = onSyncNow,
          onDeleteBlock = onDeleteBlock, onHideBlock = onHideBlock, onUnhideBlock = onUnhideBlock,
          onSetShowHidden = { store.dispatch(SetShowHidden(it)) },
          onOpenTimeline = onOpenTimeline, onCloseTimeline = onCloseTimeline, onCardAction = onCardAction,
        )
      } else {
        HubListScreen(state, onOpenHub = { onOpenHub(it, null) }, onFilter = { store.dispatch(SetHubFilter(it)) }, onRetry = onLoadHubs, hubListState = hubListState)
      }
    }
    // ADR 0053 DC5: once the audience loads, an owner/co-owner (canManage) gets the full
    // People management sheet; everyone else (incl. while aud is still loading/erroring)
    // keeps the existing read-only WhoCanSeeSheet — unchanged.
    val aud = state.currentHubAudience
    if (state.audienceSheetOpen) {
      if (aud != null && aud.canManage) {
        HubPeopleSheet(
          audience = aud,
          onSetRole = { uid, role -> state.currentHubId?.let { onSetHubRole(it, uid, role) } },
          onRemove = { uid -> state.currentHubId?.let { onRemoveHubParticipant(it, uid) } },
          onSetVisibility = { vis -> state.currentHubId?.let { onSetHubVisibility(it, vis) } },
          onAddPeople = onOpenAddPeople,
          onDismiss = { store.dispatch(CloseAudienceSheet) },
        )
      } else {
        WhoCanSeeSheet(state, onClose = { store.dispatch(CloseAudienceSheet) }, onRetryAudience = { state.currentHubId?.let { onLoadAudience(it) } })  // overlay
      }
    }
  }
}
