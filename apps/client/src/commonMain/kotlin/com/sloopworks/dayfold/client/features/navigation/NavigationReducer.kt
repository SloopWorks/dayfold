package com.sloopworks.dayfold.client

/** Feed detail navigation and session-gate routes not owned by another feature flow. */
fun reduceNavigation(state: AppState, action: Any): AppState = when (action) {
  is NavToDetail -> if (state.navigation.detailStack.lastOrNull() == action.cardId || state.cards.none { it.id == action.cardId }) state
    else state.copy(navigation = state.navigation.copy(detailStack = state.navigation.detailStack + action.cardId))
  is NavBack -> state.copy(navigation = state.navigation.copy(detailStack = state.navigation.detailStack.dropLast(1)))
  is RestoreDetailStack -> state.copy(navigation = state.navigation.copy(detailStack = action.ids))
  is AuthRestoring -> state.copy(navigation = state.navigation.copy(route = Route.Loading))
  is SessionRestored -> state.copy(navigation = state.navigation.copy(route = if (action.session == null) Route.SignIn else Route.Loading))
  is SignInSucceeded -> state.copy(navigation = state.navigation.copy(route = Route.Loading))
  is MembershipsLoaded -> state.copy(navigation = state.navigation.copy(route = routeFor(state.session.session, state.session.families)))
  is FamilyCreated -> state.copy(navigation = state.navigation.copy(route = routeFor(state.session.session, state.session.families)))
  is RestoreFailed -> state.copy(navigation = state.navigation.copy(route = Route.AuthError))
  is OpenAccount -> state.copy(navigation = state.navigation.copy(route = Route.Account))
  is CloseAccount -> state.copy(navigation = state.navigation.copy(route = routeFor(state.session.session, state.session.families)))
  is OpenProximity -> state.copy(navigation = state.navigation.copy(route = Route.Proximity))
  is CloseProximity -> state.copy(navigation = state.navigation.copy(route = Route.Account))
  is OpenJoinInvite, is RedeemRequested, is InviteRedeemed, is InviteRejected -> state.copy(navigation = state.navigation.copy(route = Route.JoinInvite))
  is JoinDismissed -> state.copy(navigation = state.navigation.copy(route = routeFor(state.session.session, state.session.families)))
  is OpenHubs, is OpenHub -> state.copy(navigation = state.navigation.copy(route = Route.Hubs))
  is OpenFeed, is CloseHubToFeed -> state.copy(navigation = state.navigation.copy(route = Route.Feed))
  is OpenMembers -> state.copy(navigation = state.navigation.copy(route = Route.Members))
  is OpenInvite -> state.copy(navigation = state.navigation.copy(route = Route.Invite))
  is InviteDismissed -> state.copy(navigation = state.navigation.copy(route = Route.Members))
  is OpenDevices -> state.copy(navigation = state.navigation.copy(route = Route.Devices))
  is OpenEnterCode -> state.copy(navigation = state.navigation.copy(route = Route.EnterCode))
  is OpenScan -> state.copy(navigation = state.navigation.copy(route = Route.ScanPrimer))
  is ScanPermissionGranted -> state.copy(navigation = state.navigation.copy(route = Route.ScanDevice))
  is ScanPermissionDenied -> state.copy(navigation = state.navigation.copy(route = Route.ScanDenied))
  is DevicePendingLoaded, is DeviceLookupNotFound -> state.copy(navigation = state.navigation.copy(route = Route.AuthorizeDevice))
  is CloseDeviceFlow -> state.copy(navigation = state.navigation.copy(route = routeFor(state.session.session, state.session.families)))
  else -> state
}
