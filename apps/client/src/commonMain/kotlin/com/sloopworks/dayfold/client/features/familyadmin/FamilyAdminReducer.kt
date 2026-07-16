package com.sloopworks.dayfold.client

fun reduceFamilyAdmin(state: AppState, action: Any): AppState = when (action) {
  is OpenMembers -> state
  is RosterLoaded -> state.copy(familyAdmin = state.familyAdmin.copy(members = action.members, rosterBusy = false, rosterError = null, memberOpId = null))
  is MemberRemoved -> state.copy(familyAdmin = state.familyAdmin.copy(members = state.familyAdmin.members.filterNot { it.uid == action.uid }, memberOpId = null))
  is ApprovalsRequested -> state.copy(familyAdmin = state.familyAdmin.copy(approvalsBusy = true))
  is ApprovalsLoaded -> state.copy(familyAdmin = state.familyAdmin.copy(approvalsBusy = false, pendingApprovals = action.pending, outstandingInvites = action.invites))
  is OpenInvite -> state.copy(familyAdmin = state.familyAdmin.copy(mintedInvite = null, mintError = null, inviteBusy = false))
  is InviteModeSelected -> state.copy(familyAdmin = state.familyAdmin.copy(inviteMode = action.mode, mintedInvite = null, mintError = null))
  is MintRequested -> state.copy(familyAdmin = state.familyAdmin.copy(inviteBusy = true, mintError = null))
  is InviteMinted -> state.copy(familyAdmin = state.familyAdmin.copy(inviteBusy = false, mintedInvite = action.invite))
  is MintFailed -> state.copy(familyAdmin = state.familyAdmin.copy(inviteBusy = false, mintError = action.reason))
  is InviteRevokeRequested -> state.copy(familyAdmin = state.familyAdmin.copy(inviteOpId = action.id))
  is InviteRevoked -> state.copy(familyAdmin = state.familyAdmin.copy(outstandingInvites = state.familyAdmin.outstandingInvites.filterNot { it.id == action.id }, inviteOpId = null))
  is InviteRevokeFailed -> if (state.familyAdmin.inviteOpId == action.id) state.copy(familyAdmin = state.familyAdmin.copy(inviteOpId = null)) else state
  is InviteDismissed -> state.copy(familyAdmin = state.familyAdmin.copy(mintedInvite = null, mintError = null, inviteBusy = false))
  is MemberResolved -> state.copy(familyAdmin = state.familyAdmin.copy(pendingApprovals = state.familyAdmin.pendingApprovals.filterNot { it.uid == action.uid }, memberOpId = null))
  is ApprovalsFailed -> state.copy(familyAdmin = state.familyAdmin.copy(approvalsBusy = false, memberOpId = null))
  is MemberOpRequested -> state.copy(familyAdmin = state.familyAdmin.copy(memberOpId = action.uid))
  is RosterRequested -> state.copy(familyAdmin = state.familyAdmin.copy(rosterBusy = true, rosterError = null))
  is RosterFailed -> state.copy(familyAdmin = state.familyAdmin.copy(rosterBusy = false, rosterError = action.message, memberOpId = null))
  else -> state
}
