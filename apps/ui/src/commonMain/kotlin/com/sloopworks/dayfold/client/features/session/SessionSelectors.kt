package com.sloopworks.dayfold.client

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import org.reduxkotlin.compose.SelectorStore
import org.reduxkotlin.compose.selectorState
import org.reduxkotlin.granular.memoizedSelector
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
