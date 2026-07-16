package com.sloopworks.dayfold.client

/** Session and invite-flow fields; routes are derived by NavigationReducer after this transition. */
fun reduceSession(state: AppState, action: Any): AppState = when (action) {
  is SessionRestored -> state.copy(session = state.session.copy(session = action.session))
  is SignInRequested -> state.copy(session = state.session.copy(authBusy = true, authError = null, pendingProvider = action.provider))
  is SignInSucceeded -> state.copy(session = state.session.copy(session = action.session, authBusy = false, authError = null, pendingProvider = null))
  is SignInFailed -> state.copy(session = state.session.copy(authBusy = false, authError = action.message, pendingProvider = null))
  is SessionRotated -> state.copy(session = state.session.copy(session = action.session))
  is MembershipsLoaded -> state.copy(session = state.session.copy(families = action.families, activeFamilyId = activeFamilyIdFor(action.families)))
  is CreateFamilyRequested -> state.copy(session = state.session.copy(authBusy = true, authError = null))
  is FamilyCreated -> {
    val families = state.session.families + FamilyMembership(action.familyId, action.name, role = "owner", status = "active")
    state.copy(session = state.session.copy(families = families, activeFamilyId = action.familyId, authBusy = false, authError = null))
  }
  is AuthOpFailed -> state.copy(session = state.session.copy(authBusy = false, authError = action.message))
  is RestoreFailed -> state.copy(session = state.session.copy(authBusy = false, authError = action.message))
  is SignOutRequested -> state.copy(session = state.session.copy(signOutBusy = true))
  is OpenJoinInvite -> state.copy(session = state.session.copy(joinBusy = false, joinOutcome = null, joinFamilyName = null))
  is RedeemRequested -> state.copy(session = state.session.copy(joinBusy = true, joinOutcome = null))
  is InviteRedeemed -> state.copy(session = state.session.copy(joinBusy = false, joinOutcome = "waiting", joinFamilyName = action.familyName))
  is InviteRejected -> state.copy(session = state.session.copy(joinBusy = false, joinOutcome = action.reason))
  is JoinDismissed -> state.copy(session = state.session.copy(joinBusy = false, joinOutcome = null, joinFamilyName = null))
  else -> state
}
