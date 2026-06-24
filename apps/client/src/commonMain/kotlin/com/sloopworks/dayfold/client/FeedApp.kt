package com.sloopworks.dayfold.client

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
import com.sloopworks.dayfold.client.cards.CardAction
import com.sloopworks.dayfold.client.cards.DetailScreen
import com.sloopworks.dayfold.client.cards.LocalAnimatedVisibilityScope
import com.sloopworks.dayfold.client.cards.LocalSharedTransitionScope
import com.sloopworks.dayfold.client.theme.DayfoldTheme
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
  onLoadHubs: () -> Unit = {},          // Hubs (ADR 0006): list fetch (HubEngine.loadHubs)
  onOpenHub: (String) -> Unit = {},     // tap a hub → load its tree (HubEngine.openHub)
) {
  val state by store.selectorState { it }
  // One stable handler (remembered so feed/detail stay skippable): OpenDetail is
  // in-app nav → dispatched to the store; every other CardAction is an OS handoff
  // → the shell's PlatformActions.
  val handle = remember(store, onPlatformAction) {
    fun(action: CardAction) = routeCardAction(store, onPlatformAction, action)
  }
  DayfoldTheme {
    // Deep-link resume beat: after sign-in, MembershipsLoaded has already set the
    // gate route, so show "Finishing…" over it while the stashed code is looked up.
    if (state.deviceResuming) { DeviceFinishingScreen(); return@DayfoldTheme }
    when (state.route) {
      Route.Loading -> SplashScreen()
      // A deep-link tapped before sign-in shows the branded resume screen instead
      // of the plain sign-in (same providers; resumes onto AuthorizeDevice after).
      Route.SignIn ->
        if (state.pendingDeviceLink != null) DeviceResumeScreen(onProvider = onSignIn)
        else SignInScreen(busy = state.authBusy, error = state.authError, onProvider = onSignIn)
      Route.AuthError -> AuthErrorScreen(message = state.authError, onRetry = onRetry, onSignOut = onSignOut)
      Route.CreateFamily -> CreateFamilyScreen(
        busy = state.authBusy, error = state.authError,
        onCreate = onCreateFamily, onJoinInvite = { store.dispatch(OpenJoinInvite) },
      )
      Route.JoinInvite -> JoinInviteScreen(state, onJoin = onRedeemInvite, onDismiss = { store.dispatch(JoinDismissed) })
      Route.Feed -> ContentHost(
        store, state, handle,
        onConnectDevice = { store.dispatch(OpenEnterCode) },
        onNavHubs = { store.dispatch(OpenHubs); onLoadHubs() },
      )
      Route.Hubs -> HubsHost(store, state, onLoadHubs = onLoadHubs, onOpenHub = onOpenHub)
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
        state, onSignOut = onSignOut, onClose = { store.dispatch(CloseAccount) },
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
    }
  }
}

// CL-7b container transform: SharedTransitionLayout shares the tapped card's
// bounds (key "card-$id") with the detail container → the card morphs into the
// detail (and back). AnimatedContent keyed on the open id (null = feed) drives the
// cross-fade; the shared element drives the bounds morph. Asymmetric timing.
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun ContentHost(store: Store<AppState>, state: AppState, handle: (CardAction) -> Unit, onConnectDevice: () -> Unit = {}, onNavHubs: () -> Unit = {}) {
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
        else FeedScreen(state, onAction = handle, onOpenAccount = { store.dispatch(OpenAccount) }, onConnectDevice = onConnectDevice, onNavHubs = onNavHubs)
      }
    }
  }
}

// Hubs surface host (ADR 0006): list ↔ detail substate driven by currentHubId.
// A LaunchedEffect fetches the list on entry; the bottom nav flips back to Feed.
@Composable
private fun HubsHost(store: Store<AppState>, state: AppState, onLoadHubs: () -> Unit, onOpenHub: (String) -> Unit) {
  androidx.compose.runtime.LaunchedEffect(Unit) { if (state.hubs.isEmpty()) onLoadHubs() }
  if (state.currentHubId != null) {
    HubDetailScreen(state, onBack = { store.dispatch(CloseHub) }, onNow = { store.dispatch(OpenFeed) })
  } else {
    HubListScreen(state, onOpenHub = onOpenHub, onNow = { store.dispatch(OpenFeed) })
  }
}
