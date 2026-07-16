package com.sloopworks.dayfold.client

fun reduceFamilyAdmin(state: AppState, action: Any): AppState = when (action) {
  is OpenMembers -> state.copy(route = Route.Members)
  is RosterLoaded -> state.copy(members = action.members, rosterBusy = false, rosterError = null, memberOpId = null)
  is MemberRemoved -> state.copy(members = state.members.filterNot { it.uid == action.uid }, memberOpId = null)
  is ApprovalsRequested -> state.copy(approvalsBusy = true)
  is ApprovalsLoaded -> state.copy(approvalsBusy = false, pendingApprovals = action.pending, outstandingInvites = action.invites)
  is OpenInvite -> state.copy(route = Route.Invite, mintedInvite = null, mintError = null, inviteBusy = false)
  is InviteModeSelected -> state.copy(inviteMode = action.mode, mintedInvite = null, mintError = null)
  is MintRequested -> state.copy(inviteBusy = true, mintError = null)
  is InviteMinted -> state.copy(inviteBusy = false, mintedInvite = action.invite)
  is MintFailed -> state.copy(inviteBusy = false, mintError = action.reason)
  is InviteRevokeRequested -> state.copy(inviteOpId = action.id)
  is InviteRevoked -> state.copy(outstandingInvites = state.outstandingInvites.filterNot { it.id == action.id }, inviteOpId = null)
  is InviteRevokeFailed -> if (state.inviteOpId == action.id) state.copy(inviteOpId = null) else state
  is InviteDismissed -> state.copy(route = Route.Members, mintedInvite = null, mintError = null, inviteBusy = false)
  is MemberResolved -> state.copy(pendingApprovals = state.pendingApprovals.filterNot { it.uid == action.uid }, memberOpId = null)
  is ApprovalsFailed -> state.copy(approvalsBusy = false, memberOpId = null)
  is MemberOpRequested -> state.copy(memberOpId = action.uid)
  is RosterRequested -> state.copy(rosterBusy = true, rosterError = null)
  is RosterFailed -> state.copy(rosterBusy = false, rosterError = action.message, memberOpId = null)
  else -> state
}
