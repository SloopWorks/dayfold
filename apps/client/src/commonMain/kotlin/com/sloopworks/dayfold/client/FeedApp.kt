package com.sloopworks.dayfold.client

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.ExperimentalTransitionApi
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.backhandler.PredictiveBackHandler
import androidx.compose.ui.platform.LocalUriHandler
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

// Route a card's CardAction: OpenDetail = in-app nav → store; everything else =
// an OS handoff → the shell's PlatformActions. Extracted (non-Composable) so the
// split is unit-testable. Returns Unit (store.dispatch returns the action).
internal fun routeCardAction(
  store: Store<AppState>, onPlatformAction: (CardAction) -> Unit, action: CardAction,
  onOpenHub: (String, String?) -> Unit = { _, _ -> },
) {
  when (action) {
    is CardAction.OpenDetail -> store.dispatch(NavToDetail(action.cardId))
    is CardAction.OpenHub -> { store.dispatch(OpenHubs); onOpenHub(action.hubId, action.focusBlockId) }  // cross-surface deep-link arrival
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
  onLoadDevices: () -> Unit = {},
  onRevokeDevice: (String) -> Unit = {},
  onLookupDevice: (String) -> Unit = {},
  onApproveDevice: (String) -> Unit = {},
  onDenyDevice: (String) -> Unit = {},
  onOpenAppSettings: () -> Unit = {},   // Tier 2: deep-link to the OS app-settings (camera permission)
  onRefresh: () -> Unit = {},           // feed pull/retry → syncEngine.syncNow()
  onLoadHubs: () -> Unit = {},          // Hubs (ADR 0006): list fetch (HubEngine.loadHubs)
  onOpenHub: (String, String?) -> Unit = { _, _ -> },  // tap/deep-link a hub → load tree (+ focus block)
  onCloseHub: () -> Unit = {},          // detail → list: cancel the DB tree subscription (HubEngine.closeHub)
  onLoadAudience: (String) -> Unit = {},// "who can see" sheet → load the audience (HubEngine.loadAudience)
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
) {
  // ADR 0036: one-time Coil image-loader setup (Ktor network fetcher + crossfade).
  // Idempotent; runs before the first AsyncImage composes. URLs are still gated by
  // MediaValidation before Coil sees them.
  remember { setupImageLoader(); 0 }
  val state by store.selectorState { it }
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
    BackHandler(enabled = appHandlesBack(state) && currentDetailCard(state) == null) {
      if (backAction(state) == CloseHub) { onCloseHub(); store.dispatch(CloseHub) }
      else store.dispatch(Back)
    }
    // Deep-link resume beat: after sign-in, MembershipsLoaded has already set the
    // gate route, so show "Finishing…" over it while the stashed code is looked up.
    if (state.deviceResuming) { SafeArea { DeviceFinishingScreen() }; return@DayfoldTheme }
    // Feed/Hubs render their own Scaffold (TopAppBar + NavigationBar consume the
    // system-bar insets) and intentionally bleed edge-to-edge → render them bare.
    // Every other route is a plain, Scaffold-less screen, so wrap it once in SafeArea
    // (safeDrawing = status/nav bars + display cutout + IME) instead of touching each.
    when (state.route) {
      Route.Feed -> ContentHost(
        store, state, handle,
        onConnectDevice = { store.dispatch(OpenEnterCode) },
        onNavHubs = { store.dispatch(OpenHubs); onLoadHubs() },
        onRefresh = onRefresh,
      )
      Route.Hubs -> HubsHost(store, state, onLoadHubs = onLoadHubs, onOpenHub = onOpenHub, onCloseHub = onCloseHub, onLoadAudience = onLoadAudience, onToggleItem = onToggleItem, onRetryBlock = onRetryBlock, onSyncNow = onRefresh, onDeleteBlock = onDeleteBlock, onHideBlock = onHideBlock, onUnhideBlock = onUnhideBlock)
      else -> SafeArea { when (state.route) {
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
      )
      Route.Devices -> DevicesScreen(
        state, onLoad = onLoadDevices, onRevoke = onRevokeDevice, onBack = { store.dispatch(OpenAccount) },
        onConnectDevice = { store.dispatch(OpenEnterCode) },
      )
      Route.Members -> MembersScreen(
        state, onApprove = onApproveMember, onDecline = onDeclineMember,
        onLoad = onLoadApprovals, onLoadMembers = onLoadMembers, onRemoveMember = onRemoveMember,
        onBack = { store.dispatch(OpenAccount) },
      )
      } }
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
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalComposeUiApi::class, ExperimentalTransitionApi::class)
@Composable
private fun ContentHost(store: Store<AppState>, state: AppState, handle: (CardAction) -> Unit, onConnectDevice: () -> Unit = {}, onNavHubs: () -> Unit = {}, onRefresh: () -> Unit = {}) {
  val detail = currentDetailCard(state)
  val targetKey: String? = detail?.id            // top of the detail stack (null = feed)
  // Where a back POP lands: the card UNDERNEATH the top (null = feed for a 1-deep stack).
  // Hardcoding null here would flash the feed on a nested detail→detail back.
  val popTarget: String? = state.detailStack.getOrNull(state.detailStack.lastIndex - 1)
  val seekable = remember { SeekableTransitionState<String?>(targetKey) }
  val reduceMotion = rememberReduceMotion()

  // Redux -> animation sync for NON-gesture transitions. The gesture path drives the
  // seekable directly and dispatches NavBack on commit; afterwards targetKey == the
  // settled state, so this no-ops.
  LaunchedEffect(targetKey) {
    if (seekable.currentState != targetKey) {
      if (reduceMotion) seekable.snapTo(targetKey)
      else seekable.animateTo(targetKey, animationSpec = tween(if (targetKey != null) 360 else 280, easing = EmphasizedDecelerate))
    }
  }

  // Back GESTURE: only when a detail is open. Scrub toward popTarget with the finger;
  // commit -> NavBack, cancel -> animate back to the current detail. Reduced-motion:
  // skip the scrub, jump to commit. The scrub is clamped < 1f so only the explicit
  // commit animateTo finishes the morph (a front-loaded decelerate can hit 1f while the
  // finger is still down — without the clamp the detail would visually vanish pre-commit).
  PredictiveBackHandler(enabled = detail != null) { progress ->
    try {
      progress.collect { e -> if (!reduceMotion) seekable.seekTo(decelerateProgress(e.progress).coerceAtMost(0.999f), targetState = popTarget) }
      if (!reduceMotion) seekable.animateTo(popTarget, animationSpec = tween(280, easing = EmphasizedDecelerate))
      store.dispatch(NavBack)                    // COMMIT
    } catch (e: CancellationException) {
      val back = detail?.id
      if (!reduceMotion && back != null) seekable.animateTo(back, animationSpec = tween(280, easing = EmphasizedDecelerate))
      throw e                                    // CANCEL — must rethrow
    }
  }

  val transition = rememberTransition(seekable, label = "feed-detail")
  SharedTransitionLayout {
    transition.AnimatedContent(
      contentKey = { it },
      transitionSpec = {
        // keep the original asymmetric fade+slide (360 open / 280 close); the default
        // Transition.AnimatedContent spec adds a scaleIn that fights the shared morph.
        val opening = targetState != null
        val dur = if (opening) 360 else 280
        (fadeIn(tween(dur)) + slideInVertically(tween(dur)) { h -> h / 16 }) togetherWith fadeOut(tween(dur))
      },
    ) { id ->
      CompositionLocalProvider(
        LocalSharedTransitionScope provides this@SharedTransitionLayout,
        LocalAnimatedVisibilityScope provides this@AnimatedContent,
      ) {
        val card = id?.let { cid -> state.cards.find { it.id == cid } }
        if (card != null) DetailScreen(card, onBack = { store.dispatch(NavBack) }, onAction = handle)
        else FeedScreen(state, onAction = handle, onOpenAccount = { store.dispatch(OpenAccount) }, onConnectDevice = onConnectDevice, onNavHubs = onNavHubs, onRefresh = onRefresh)
      }
    }
  }
}

// Hubs surface host (ADR 0006): list ↔ detail substate driven by currentHubId.
// A LaunchedEffect fetches the list on entry; the bottom nav flips back to Feed.
@Composable
private fun HubsHost(store: Store<AppState>, state: AppState, onLoadHubs: () -> Unit, onOpenHub: (String, String?) -> Unit, onCloseHub: () -> Unit = {}, onLoadAudience: (String) -> Unit, onToggleItem: (String, String, Boolean) -> Unit = { _, _, _ -> }, onRetryBlock: (String) -> Unit = {}, onSyncNow: () -> Unit = {}, onDeleteBlock: (String) -> Unit = {}, onHideBlock: (String) -> Unit = {}, onUnhideBlock: (String) -> Unit = {}) {
  androidx.compose.runtime.LaunchedEffect(Unit) { if (state.hubs.isEmpty()) onLoadHubs() }
  androidx.compose.foundation.layout.Box {
    if (state.currentHubId != null) {
      HubDetailScreen(
        state, onBack = { onCloseHub(); store.dispatch(CloseHub) }, onNow = { store.dispatch(OpenFeed) },
        onOpenAudience = { state.currentHubId?.let { store.dispatch(OpenAudienceSheet); onLoadAudience(it) } },
        onRetry = { state.currentHubId?.let { id -> onOpenHub(id, null) } },
        onToggleItem = onToggleItem, onRetryBlock = onRetryBlock, onSyncNow = onSyncNow,
        onDeleteBlock = onDeleteBlock, onHideBlock = onHideBlock, onUnhideBlock = onUnhideBlock,
        onSetShowHidden = { store.dispatch(SetShowHidden(it)) },
      )
    } else {
      HubListScreen(state, onOpenHub = { onOpenHub(it, null) }, onNow = { store.dispatch(OpenFeed) }, onFilter = { store.dispatch(SetHubFilter(it)) }, onRetry = onLoadHubs)
    }
    if (state.audienceSheetOpen) WhoCanSeeSheet(state, onClose = { store.dispatch(CloseAudienceSheet) }, onRetryAudience = { state.currentHubId?.let { onLoadAudience(it) } })  // overlay
  }
}
