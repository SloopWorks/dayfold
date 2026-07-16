package com.sloopworks.dayfold.client

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import org.reduxkotlin.compose.SelectorStore
import org.reduxkotlin.compose.selectorState

/** Back ownership resolved by the app shell without exposing feature state to it. */
enum class BackTarget {
  FeedDetail,
  Audience,
  Timeline,
  HubList,
  FeedDetailFromHub,
  Account,
  Members,
  DeviceFlow,
  JoinInvite,
}

/** Minimal state needed by [FeedApp] to choose route, overlay, and back ownership. */
@Immutable
data class AppShellState(
  val route: Route,
  val detailCardId: String?,
  val currentHubId: String?,
  val deviceResuming: Boolean,
  val timelineDetailOpen: Boolean,
  val backTarget: BackTarget?,
)

/** Pure shell projection. It deliberately excludes all feature collections and profile data. */
fun appShellState(state: AppState): AppShellState {
  val detailCardId = currentDetailCard(state)?.id
  val backTarget = when {
    state.deviceResuming -> null
    state.audienceSheetOpen -> BackTarget.Audience
    state.route == Route.Feed && detailCardId != null -> BackTarget.FeedDetail
    state.route == Route.Hubs && state.timelineDetail != null -> BackTarget.Timeline
    state.route == Route.Hubs && state.currentHubId != null && state.hubFromDetail -> BackTarget.FeedDetailFromHub
    state.route == Route.Hubs && state.currentHubId != null -> BackTarget.HubList
    state.route == Route.Account -> BackTarget.Account
    state.route == Route.Members || state.route == Route.Devices || state.route == Route.Proximity -> BackTarget.Members
    state.route == Route.Invite -> BackTarget.Members
    state.route == Route.AuthorizeDevice || state.route == Route.EnterCode ||
      state.route == Route.ScanPrimer || state.route == Route.ScanDevice || state.route == Route.ScanDenied -> BackTarget.DeviceFlow
    state.route == Route.JoinInvite -> BackTarget.JoinInvite
    else -> null
  }
  return AppShellState(
    route = state.route,
    detailCardId = detailCardId,
    currentHubId = state.currentHubId,
    deviceResuming = state.deviceResuming,
    timelineDetailOpen = state.timelineDetail != null,
    backTarget = backTarget,
  )
}

@Composable
internal fun rememberAppShellState(store: SelectorStore<AppState>): AppShellState {
  val state by store.selectorState(::appShellState)
  return state
}

@Immutable
data class SignInViewState(
  val pendingDeviceLink: String?,
  val pendingProvider: String?,
  val error: String?,
)

fun signInViewState(state: AppState): SignInViewState = SignInViewState(
  pendingDeviceLink = state.pendingDeviceLink,
  pendingProvider = state.pendingProvider,
  error = state.authError,
)

fun authErrorMessage(state: AppState): String? = state.authError

@Immutable
data class CreateFamilyViewState(val busy: Boolean, val error: String?)

fun createFamilyViewState(state: AppState): CreateFamilyViewState =
  CreateFamilyViewState(state.authBusy, state.authError)

@Immutable
data class JoinInviteViewState(
  val busy: Boolean,
  val outcome: String?,
  val familyName: String?,
)

fun joinInviteViewState(state: AppState): JoinInviteViewState = JoinInviteViewState(
  busy = state.joinBusy,
  outcome = state.joinOutcome,
  familyName = state.joinFamilyName,
)

@Immutable
data class EnterCodeViewState(val busy: Boolean, val error: String?)

fun enterCodeViewState(state: AppState): EnterCodeViewState =
  EnterCodeViewState(busy = state.deviceBusy, error = state.deviceError)

@Immutable
data class AuthorizeDeviceViewState(
  val activeFamilyId: String?,
  val families: List<FamilyMembership>,
  val hubs: List<Hub>,
  val pendingDevice: PendingDevice?,
  val busy: Boolean,
  val error: String?,
  val outcome: String?,
)

fun authorizeDeviceViewState(state: AppState): AuthorizeDeviceViewState = AuthorizeDeviceViewState(
  activeFamilyId = state.activeFamilyId,
  families = state.families,
  hubs = state.hubs,
  pendingDevice = state.pendingDevice,
  busy = state.deviceBusy,
  error = state.deviceError,
  outcome = state.deviceOutcome,
)

@Immutable
data class AccountViewState(
  val activeFamily: FamilyMembership?,
  val pendingApprovalCount: Int,
  val displayName: String?,
  val avatarColor: String?,
  val avatarRef: String?,
  val avatarBusy: Boolean,
  val avatarError: String?,
  val nameError: String?,
  val proximityEnabled: Boolean,
  val signOutBusy: Boolean,
)

fun accountViewState(state: AppState): AccountViewState = AccountViewState(
  activeFamily = state.families.firstOrNull { it.familyId == state.activeFamilyId },
  pendingApprovalCount = state.pendingApprovals.size,
  displayName = state.myDisplayName,
  avatarColor = state.myAvatarColor,
  avatarRef = state.myAvatarRef,
  avatarBusy = state.avatarOpId != null,
  avatarError = state.avatarError,
  nameError = state.nameError,
  proximityEnabled = state.notifConfig.enabled,
  signOutBusy = state.signOutBusy,
)

@Immutable
data class ProximityViewState(
  val config: NotifConfig,
  val permission: LocationPermission,
)

fun proximityViewState(state: AppState): ProximityViewState =
  ProximityViewState(state.notifConfig, state.locationPermission)

@Immutable
data class DevicesViewState(
  val devices: List<DeviceCredential>,
  val busy: Boolean,
  val error: String?,
  val operationId: String?,
)

fun devicesViewState(state: AppState): DevicesViewState = DevicesViewState(
  devices = state.devices,
  busy = state.deviceListBusy,
  error = state.deviceListError,
  operationId = state.deviceOpId,
)

@Immutable
data class MembersViewState(
  val activeFamilyId: String?,
  val activeFamily: FamilyMembership?,
  val pendingApprovals: List<PendingMember>,
  val members: List<FamilyMember>,
  val rosterBusy: Boolean,
  val rosterError: String?,
  val operationId: String?,
)

fun membersViewState(state: AppState): MembersViewState = MembersViewState(
  activeFamilyId = state.activeFamilyId,
  activeFamily = state.families.firstOrNull { it.familyId == state.activeFamilyId },
  pendingApprovals = state.pendingApprovals,
  members = state.members,
  rosterBusy = state.rosterBusy,
  rosterError = state.rosterError,
  operationId = state.memberOpId,
)

@Immutable
data class InviteViewState(
  val activeFamilyId: String?,
  val mode: String,
  val busy: Boolean,
  val mintedInvite: MintedInvite?,
  val mintError: String?,
  val outstandingInvites: List<Invite>,
  val pendingApprovals: List<PendingMember>,
  val inviteOperationId: String?,
  val memberOperationId: String?,
)

fun inviteViewState(state: AppState): InviteViewState = InviteViewState(
  activeFamilyId = state.activeFamilyId,
  mode = state.inviteMode,
  busy = state.inviteBusy,
  mintedInvite = state.mintedInvite,
  mintError = state.mintError,
  outstandingInvites = state.outstandingInvites,
  pendingApprovals = state.pendingApprovals,
  inviteOperationId = state.inviteOpId,
  memberOperationId = state.memberOpId,
)

@Immutable
data class FeedViewState(
  val cards: List<Card>,
  val hubs: List<Hub>,
  val memberCount: Int,
  val syncing: Boolean,
  val error: String?,
  val displayName: String?,
  val avatarColor: String?,
  val avatarRef: String?,
  val nowContent: NowContent,
  val surfacing: Map<String, SurfacingRecord>,
)

fun feedViewState(state: AppState): FeedViewState = FeedViewState(
  cards = state.cards,
  hubs = state.hubs,
  memberCount = state.members.size,
  syncing = state.syncing,
  error = state.error,
  displayName = state.myDisplayName,
  avatarColor = state.myAvatarColor,
  avatarRef = state.myAvatarRef,
  nowContent = state.nowContent,
  surfacing = state.surfacing,
)

/** Small input used to memoize ranking away from store notification delivery. */
internal fun FeedViewState.rankingState(): AppState = AppState(
  cards = cards,
  hubs = hubs,
  nowContent = nowContent,
  surfacing = surfacing,
)

@Immutable
data class FeedDetailViewState(val card: Card, val hubName: String?)

fun feedDetailViewState(state: AppState): FeedDetailViewState? {
  val card = currentDetailCard(state) ?: return null
  val hubName = (card.targetHubId ?: card.hubRef)?.let { hubId ->
    state.hubs.firstOrNull { it.id == hubId }?.title
  }
  return FeedDetailViewState(card, hubName)
}

@Immutable
data class HubRouteState(
  val activeFamilyId: String?,
  val currentHubId: String?,
  val fromFeedDetail: Boolean,
  val audienceSheetOpen: Boolean,
)

fun hubRouteState(state: AppState): HubRouteState = HubRouteState(
  activeFamilyId = state.activeFamilyId,
  currentHubId = state.currentHubId,
  fromFeedDetail = state.hubFromDetail,
  audienceSheetOpen = state.audienceSheetOpen,
)

@Immutable
data class HubListViewState(
  val hasAnyHubs: Boolean,
  val shownHubs: List<Hub>,
  val filter: String,
  val busy: Boolean,
  val error: String?,
)

fun hubListViewState(state: AppState): HubListViewState {
  val shown = state.hubs.filter { hub ->
    when (state.hubFilter) {
      "active" -> hub.status == "active"
      "planning" -> hub.status == "planning"
      else -> true
    }
  }
  return HubListViewState(state.hubs.isNotEmpty(), shown, state.hubFilter, state.hubsBusy, state.hubError)
}

@Composable
internal fun rememberHubListViewState(store: SelectorStore<AppState>): HubListViewState {
  val state by store.selectorState(::hubListViewState)
  return state
}

@Immutable
data class HubDetailViewState(
  val tree: HubTree?,
  val busy: Boolean,
  val hubError: String?,
  val syncError: String?,
  val focusBlockId: String?,
  val hiddenIds: Set<String>,
  val showHidden: Boolean,
  val timelineDetail: TimelineScale?,
  val members: List<FamilyMember>,
  val currentUserId: String?,
)

fun hubDetailViewState(state: AppState): HubDetailViewState = HubDetailViewState(
  tree = state.currentHubTree,
  busy = state.hubsBusy,
  hubError = state.hubError,
  syncError = state.error,
  focusBlockId = state.hubFocusBlockId,
  hiddenIds = state.hiddenIds,
  showHidden = state.showHidden,
  timelineDetail = state.timelineDetail,
  members = state.members,
  currentUserId = state.session?.userId,
)

@Immutable
data class HubAudienceViewState(
  val audience: HubAudience?,
  val error: String?,
  val currentUserId: String?,
)

fun hubAudienceViewState(state: AppState): HubAudienceViewState = HubAudienceViewState(
  audience = state.currentHubAudience,
  error = state.audienceError,
  currentUserId = state.session?.userId,
)
