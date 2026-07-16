package com.sloopworks.dayfold.client

/** Feed detail navigation and session-gate routes not owned by another feature flow. */
fun reduceNavigation(state: AppState, action: Any): AppState = when (action) {
  is NavToDetail -> if (state.detailStack.lastOrNull() == action.cardId || state.cards.none { it.id == action.cardId }) state
    else state.copy(detailStack = state.detailStack + action.cardId)
  is NavBack -> state.copy(detailStack = state.detailStack.dropLast(1))
  is RestoreDetailStack -> state.copy(detailStack = action.ids)
  is AuthRestoring -> state.copy(route = Route.Loading)
  is SessionRestored -> state.copy(route = if (action.session == null) Route.SignIn else Route.Loading)
  is SignInSucceeded -> state.copy(route = Route.Loading)
  is MembershipsLoaded -> state.copy(route = routeFor(state.session, state.families))
  is FamilyCreated -> state.copy(route = routeFor(state.session, state.families))
  is RestoreFailed -> state.copy(route = Route.AuthError)
  is OpenAccount -> state.copy(route = Route.Account)
  is CloseAccount -> state.copy(route = routeFor(state.session, state.families))
  is OpenProximity -> state.copy(route = Route.Proximity)
  is CloseProximity -> state.copy(route = Route.Account)
  is OpenJoinInvite, is RedeemRequested, is InviteRedeemed, is InviteRejected -> state.copy(route = Route.JoinInvite)
  is JoinDismissed -> state.copy(route = routeFor(state.session, state.families))
  else -> state
}
