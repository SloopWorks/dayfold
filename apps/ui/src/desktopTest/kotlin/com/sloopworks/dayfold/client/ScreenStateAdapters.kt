package com.sloopworks.dayfold.client

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import com.sloopworks.dayfold.client.cards.CardAction
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Instant

// Snapshot and focused screen tests intentionally author complete AppState fixtures. Keep that
// convenience in test source only; shipped composables accept narrow immutable view states.

@Composable
internal fun FeedScreen(
  state: AppState,
  onAction: (CardAction) -> Unit = {},
  onOpenAccount: () -> Unit = {},
  onConnectDevice: () -> Unit = {},
  onNavHubs: () -> Unit = {},
  onRefresh: () -> Unit = {},
  onShown: (Set<String>) -> Unit = {},
  location: DeviceLocation? = null,
  now: Instant = Clock.System.now(),
  timeZone: TimeZone = TimeZone.currentSystemDefault(),
  listState: LazyListState = rememberLazyListState(),
) = FeedScreen(
  feedViewState(state), onAction, onOpenAccount, onConnectDevice, onNavHubs,
  onRefresh, onShown, location, now, timeZone, listState,
)

@Composable
internal fun HubListScreen(
  state: AppState,
  onOpenHub: (String) -> Unit = {},
  onFilter: (String) -> Unit = {},
  onRetry: () -> Unit = {},
  now: Instant = Clock.System.now(),
  hubListState: LazyListState = rememberLazyListState(),
) = HubListScreen(hubListViewState(state), onOpenHub, onFilter, onRetry, now, hubListState)

@Composable
internal fun HubDetailScreen(
  state: AppState,
  onBack: () -> Unit = {},
  onOpenAudience: () -> Unit = {},
  onRetry: () -> Unit = {},
  onToggleItem: (String, String, Boolean) -> Unit = { _, _, _ -> },
  onRetryBlock: (String) -> Unit = {},
  onSyncNow: () -> Unit = {},
  onDeleteBlock: (String) -> Unit = {},
  onHideBlock: (String) -> Unit = {},
  onUnhideBlock: (String) -> Unit = {},
  onSetShowHidden: (Boolean) -> Unit = {},
  onOpenTimeline: (TimelineScale) -> Unit = {},
  onCloseTimeline: () -> Unit = {},
  onCardAction: (CardAction) -> Unit = {},
  now: Instant = Clock.System.now(),
  timeZone: TimeZone = TimeZone.currentSystemDefault(),
) = HubDetailScreen(
  hubDetailViewState(state), onBack, onOpenAudience, onRetry, onToggleItem,
  onRetryBlock, onSyncNow, onDeleteBlock, onHideBlock, onUnhideBlock,
  onSetShowHidden, onOpenTimeline, onCloseTimeline, onCardAction, now, timeZone,
)

@Composable
internal fun WhoCanSeeSheet(
  state: AppState,
  onClose: () -> Unit = {},
  onRetryAudience: () -> Unit = {},
) = WhoCanSeeSheet(hubAudienceViewState(state), onClose, onRetryAudience)

@Composable
internal fun AccountScreen(
  state: AppState,
  signOutBusy: Boolean = false,
  onSignOut: () -> Unit = {},
  onClose: () -> Unit = {},
  onOpenMembers: () -> Unit = {},
  onOpenDevices: () -> Unit = {},
  onOpenProximity: () -> Unit = {},
  onUpdateAvatar: (String?, String?) -> Unit = { _, _ -> },
  onUpdateName: (String) -> Unit = {},
) = AccountScreen(
  accountViewState(state).copy(signOutBusy = signOutBusy),
  onSignOut, onClose, onOpenMembers, onOpenDevices, onOpenProximity, onUpdateAvatar, onUpdateName,
)

@Composable
internal fun DevicesScreen(
  state: AppState,
  onLoad: () -> Unit = {},
  onRevoke: (String) -> Unit = {},
  onBack: () -> Unit = {},
  onConnectDevice: () -> Unit = {},
) = DevicesScreen(devicesViewState(state), onLoad, onRevoke, onBack, onConnectDevice)

@Composable
internal fun MembersScreen(
  state: AppState,
  onApprove: (String) -> Unit = {},
  onDecline: (String) -> Unit = {},
  onLoad: () -> Unit = {},
  onLoadMembers: () -> Unit = {},
  onRemoveMember: (String) -> Unit = {},
  onInvite: () -> Unit = {},
  onBack: () -> Unit = {},
) = MembersScreen(
  membersViewState(state), onApprove, onDecline, onLoad, onLoadMembers, onRemoveMember, onInvite, onBack,
)

@Composable
internal fun InviteScreen(
  state: AppState,
  now: Instant = Clock.System.now(),
  onMode: (String) -> Unit = {},
  onMint: (String) -> Unit = {},
  onRevoke: (String) -> Unit = {},
  onApprove: (String) -> Unit = {},
  onDecline: (String) -> Unit = {},
  onBack: () -> Unit = {},
) = InviteScreen(inviteViewState(state), now, onMode, onMint, onRevoke, onApprove, onDecline, onBack)

@Composable
internal fun JoinInviteScreen(
  state: AppState,
  onJoin: (String) -> Unit = {},
  onDismiss: () -> Unit = {},
) = JoinInviteScreen(joinInviteViewState(state), onJoin, onDismiss)

@Composable
internal fun EnterCodeScreen(
  state: AppState,
  onLookup: (String) -> Unit = {},
  onBack: () -> Unit = {},
  onScan: (() -> Unit)? = null,
) = EnterCodeScreen(enterCodeViewState(state), onLookup, onBack, onScan)

@Composable
internal fun AuthorizeDeviceScreen(
  state: AppState,
  onApprove: (fid: String, hubIds: List<String>?) -> Unit = { _, _ -> },
  onDeny: (String) -> Unit = {},
  onCancel: () -> Unit = {},
) = AuthorizeDeviceScreen(authorizeDeviceViewState(state), onApprove, onDeny, onCancel)
