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
  pendingDeviceLink = state.devices.pendingLink,
  pendingProvider = state.session.pendingProvider,
  error = state.session.authError,
)

fun authErrorMessage(state: AppState): String? = state.session.authError

@Immutable
data class CreateFamilyViewState(val busy: Boolean, val error: String?)

fun createFamilyViewState(state: AppState): CreateFamilyViewState =
  CreateFamilyViewState(state.session.authBusy, state.session.authError)

@Immutable
data class JoinInviteViewState(
  val busy: Boolean,
  val outcome: String?,
  val familyName: String?,
)

fun joinInviteViewState(state: AppState): JoinInviteViewState = JoinInviteViewState(
  busy = state.session.joinBusy,
  outcome = state.session.joinOutcome,
  familyName = state.session.joinFamilyName,
)

@Immutable
data class EnterCodeViewState(val busy: Boolean, val error: String?)

fun enterCodeViewState(state: AppState): EnterCodeViewState =
  EnterCodeViewState(busy = state.devices.busy, error = state.devices.error)

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
  activeFamilyId = state.session.activeFamilyId,
  families = state.session.families,
  hubs = state.hubs.hubs,
  pendingDevice = state.devices.pendingDevice,
  busy = state.devices.busy,
  error = state.devices.error,
  outcome = state.devices.outcome,
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
  activeFamily = state.session.families.firstOrNull { it.familyId == state.session.activeFamilyId },
  pendingApprovalCount = state.familyAdmin.pendingApprovals.size,
  displayName = state.profile.displayName,
  avatarColor = state.profile.avatarColor,
  avatarRef = state.profile.avatarRef,
  avatarBusy = state.profile.avatarOpId != null,
  avatarError = state.profile.avatarError,
  nameError = state.profile.nameError,
  proximityEnabled = state.notifConfig.enabled,
  signOutBusy = state.session.signOutBusy,
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
  devices = state.devices.devices,
  busy = state.devices.listBusy,
  error = state.devices.listError,
  operationId = state.devices.operationId,
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
  activeFamilyId = state.session.activeFamilyId,
  activeFamily = state.session.families.firstOrNull { it.familyId == state.session.activeFamilyId },
  pendingApprovals = state.familyAdmin.pendingApprovals,
  members = state.familyAdmin.members,
  rosterBusy = state.familyAdmin.rosterBusy,
  rosterError = state.familyAdmin.rosterError,
  operationId = state.familyAdmin.memberOpId,
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
  activeFamilyId = state.session.activeFamilyId,
  mode = state.familyAdmin.inviteMode,
  busy = state.familyAdmin.inviteBusy,
  mintedInvite = state.familyAdmin.mintedInvite,
  mintError = state.familyAdmin.mintError,
  outstandingInvites = state.familyAdmin.outstandingInvites,
  pendingApprovals = state.familyAdmin.pendingApprovals,
  inviteOperationId = state.familyAdmin.inviteOpId,
  memberOperationId = state.familyAdmin.memberOpId,
)
