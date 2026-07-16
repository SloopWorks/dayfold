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

/** Installs exactly one feature subscription for the active non-tab route. */
@Composable
internal fun RouteHost(
  route: Route,
  store: SelectorStore<AppState>,
  commands: StableDayfoldCommands,
  platformActions: StablePlatformActions,
  devSignIn: (() -> Unit)?,
) {
  when (route) {
    Route.Loading -> SplashScreen()
    Route.SignIn -> {
      val state by store.selectorState(::signInViewState)
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
      val error by store.selectorState(::authErrorMessage)
      AuthErrorScreen(
        message = error,
        onRetry = commands::retryAuth,
        onSignOut = commands::signOut,
      )
    }
    Route.CreateFamily -> {
      val state by store.selectorState(::createFamilyViewState)
      CreateFamilyScreen(
        busy = state.busy,
        error = state.error,
        onCreate = commands::createFamily,
        onJoinInvite = { store.dispatch(OpenJoinInvite) },
      )
    }
    Route.JoinInvite -> {
      val state by store.selectorState(::joinInviteViewState)
      JoinInviteScreen(
        state,
        onJoin = commands::redeemInvite,
        onDismiss = { store.dispatch(JoinDismissed) },
      )
    }
    Route.EnterCode -> {
      val state by store.selectorState(::enterCodeViewState)
      EnterCodeScreen(
        state,
        onLookup = commands::lookupDevice,
        onBack = { store.dispatch(CloseDeviceFlow) },
        onScan = if (qrScanSupported) ({ store.dispatch(OpenScan) }) else null,
      )
    }
    Route.ScanPrimer -> {
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
      onCode = commands::lookupDevice,
      onEnterManually = { store.dispatch(OpenEnterCode) },
      onClose = { store.dispatch(CloseDeviceFlow) },
    )
    Route.ScanDenied -> ScanDeniedScreen(
      onOpenSettings = platformActions::openAppSettings,
      onEnterCode = { store.dispatch(OpenEnterCode) },
      onClose = { store.dispatch(CloseDeviceFlow) },
    )
    Route.AuthorizeDevice -> {
      val state by store.selectorState(::authorizeDeviceViewState)
      when (state.outcome) {
        "denied" -> DeviceDeniedScreen(onDone = { store.dispatch(CloseDeviceFlow) })
        "expired" -> DeviceExpiredScreen(
          onRetry = { store.dispatch(OpenEnterCode) },
          onDone = { store.dispatch(CloseDeviceFlow) },
        )
        "approved" -> DeviceApprovedConfirm(onDone = { store.dispatch(CloseDeviceFlow) })
        else -> AuthorizeDeviceScreen(
          state,
          onApprove = { familyId, hubIds ->
            state.pendingDevice?.userCode?.let { code -> commands.approveDevice(familyId, code, hubIds) }
          },
          onDeny = { familyId ->
            state.pendingDevice?.userCode?.let { code -> commands.denyDevice(familyId, code) }
          },
          onCancel = { store.dispatch(CloseDeviceFlow) },
        )
      }
    }
    Route.Account -> {
      val state by store.selectorState(::accountViewState)
      AccountScreen(
        state,
        onSignOut = commands::signOut,
        onClose = { store.dispatch(CloseAccount) },
        onOpenMembers = { store.dispatch(OpenMembers) },
        onOpenDevices = { store.dispatch(OpenDevices) },
        onOpenProximity = { store.dispatch(OpenProximity) },
        onUpdateAvatar = commands::updateAvatar,
        onUpdateName = commands::updateDisplayName,
      )
    }
    Route.Proximity -> {
      val state by store.selectorState(::proximityViewState)
      ProximitySettingsHost(
        config = state.config,
        permission = state.permission,
        onSetNotifConfig = {
          commands.setNotificationConfig(it)
          if (it.enabled) platformActions.requestProximityPermissions()
        },
        onOpenPermission = platformActions::openAppSettings,
        onBack = { store.dispatch(CloseProximity) },
      )
    }
    Route.Devices -> {
      val state by store.selectorState(::devicesViewState)
      DevicesScreen(
        state,
        onLoad = commands::loadDevices,
        onRevoke = commands::revokeDevice,
        onBack = { store.dispatch(OpenAccount) },
        onConnectDevice = { store.dispatch(OpenEnterCode) },
      )
    }
    Route.Members -> {
      val state by store.selectorState(::membersViewState)
      MembersScreen(
        state,
        onApprove = { uid -> state.activeFamilyId?.let { commands.approveMember(it, uid) } },
        onDecline = { uid -> state.activeFamilyId?.let { commands.declineMember(it, uid) } },
        onLoad = { state.activeFamilyId?.let(commands::loadApprovals) },
        onLoadMembers = { state.activeFamilyId?.let(commands::loadMembers) },
        onRemoveMember = { uid -> state.activeFamilyId?.let { commands.removeMember(it, uid) } },
        onInvite = { store.dispatch(OpenInvite) },
        onBack = { store.dispatch(OpenAccount) },
      )
    }
    Route.Invite -> {
      val state by store.selectorState(::inviteViewState)
      LaunchedEffect(state.activeFamilyId) {
        state.activeFamilyId?.let(commands::loadApprovals)
      }
      InviteScreen(
        state,
        onMode = { store.dispatch(InviteModeSelected(it)) },
        onMint = { mode -> state.activeFamilyId?.let { commands.mintInvite(it, mode) } },
        onRevoke = { id -> state.activeFamilyId?.let { commands.revokeInvite(it, id) } },
        onApprove = { uid -> state.activeFamilyId?.let { commands.approveMember(it, uid) } },
        onDecline = { uid -> state.activeFamilyId?.let { commands.declineMember(it, uid) } },
        onBack = { store.dispatch(InviteDismissed) },
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
internal fun SafeArea(content: @Composable () -> Unit) {
  Box(Modifier.fillMaxSize().safeDrawingPadding()) { content() }
}
