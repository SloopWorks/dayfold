package com.sloopworks.dayfold.client

/** Feed detail navigation and session-gate routes not owned by another feature flow. */
fun reduceNavigation(state: AppState, action: Any): AppState = when (action) {
  is NavToDetail -> if (state.navigation.detailStack.lastOrNull() == action.cardId || state.content.cards.none { it.id == action.cardId }) state
    else state.copy(navigation = state.navigation.copy(
      detailStack = state.navigation.detailStack + action.cardId,
      // The first normal Feed push starts a Feed-returning stack. Related-card
      // pushes preserve the stack's existing return target, including Search.
      detailReturnDestination = if (state.navigation.detailStack.isEmpty()) {
        DetailReturnDestination.FEED
      } else {
        state.navigation.detailReturnDestination
      },
    ))
  is OpenDetailFromSearch -> if (
    state.navigation.route != Route.Search || state.content.cards.none { it.id == action.cardId }
  ) state else state.copy(navigation = state.navigation.copy(
    route = Route.Feed,
    detailStack = listOf(action.cardId),
    detailReturnDestination = DetailReturnDestination.SEARCH,
  ))
  is NavBack -> state.copy(navigation = state.navigation.popDetail())
  is RestoreDetailStack -> state.copy(navigation = state.navigation.copy(
    detailStack = action.ids,
    detailReturnDestination = DetailReturnDestination.FEED,
  ))
  is OpenSearch -> if (state.canOpenSearch(action.origin)) state.copy(navigation = state.navigation.copy(
    route = Route.Search,
    detailStack = emptyList(),
    searchOrigin = action.origin,
    detailReturnDestination = DetailReturnDestination.FEED,
  )) else state
  is CloseSearch -> if (state.navigation.route != Route.Search) state else state.copy(navigation = state.navigation.copy(
    route = when (state.navigation.searchOrigin) {
      SearchOrigin.NOW -> Route.Feed
      SearchOrigin.HUBS -> Route.Hubs
    },
    detailStack = emptyList(),
    detailReturnDestination = DetailReturnDestination.FEED,
  ))
  is AuthRestoring -> state.copy(navigation = state.navigation.copy(route = Route.Loading))
  is SessionRestored -> state.copy(navigation = state.navigation.copy(route = if (action.session == null) Route.SignIn else Route.Loading))
  is SignInSucceeded -> state.copy(navigation = state.navigation.copy(route = Route.Loading))
  is MembershipsLoaded -> state.copy(navigation = state.navigation.copy(route = routeFor(state.session.session, state.session.families)))
  is FamilyCreated -> state.copy(navigation = state.navigation.copy(route = routeFor(state.session.session, state.session.families)))
  is RestoreFailed -> state.copy(navigation = state.navigation.copy(route = Route.AuthError))
  is OpenAccount -> state.copy(navigation = state.navigation.copy(route = Route.Account))
  is OpenSmartContent -> state.copy(navigation = state.navigation.copy(route = Route.SmartContent))
  is CloseSmartContent -> state.copy(navigation = state.navigation.copy(route = Route.Account))
  is CloseAccount -> state.copy(navigation = state.navigation.copy(route = routeFor(state.session.session, state.session.families)))
  is OpenSmartBriefings, is RestoreSmartBriefings -> if (
    routinePreviewAvailable(state) && state.session.activeFamilyId != null
  ) state.copy(navigation = state.navigation.copy(route = Route.SmartBriefings)) else state
  is CloseSmartBriefings -> state.copy(navigation = state.navigation.copy(route = Route.Account))
  is OpenProximity -> state.copy(navigation = state.navigation.copy(route = Route.Proximity))
  is CloseProximity -> state.copy(navigation = state.navigation.copy(route = Route.Account))
  is OpenCalendarSettings -> state.copy(navigation = state.navigation.copy(route = Route.Calendar))
  is CloseCalendarSettings -> state.copy(navigation = state.navigation.copy(route = Route.Account))
  is OpenJoinInvite, is RedeemRequested, is InviteRedeemed, is InviteRejected -> state.copy(navigation = state.navigation.copy(route = Route.JoinInvite))
  is JoinDismissed -> state.copy(navigation = state.navigation.copy(route = routeFor(state.session.session, state.session.families)))
  is OpenHubs, is OpenHub -> state.copy(navigation = state.navigation.copy(route = Route.Hubs))
  is OpenFeed, is CloseHubToFeed -> state.copy(navigation = state.navigation.copy(route = Route.Feed))
  is CloseHubToSearch -> state.copy(navigation = state.navigation.copy(route = Route.Search))
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

private fun AppState.canOpenSearch(origin: SearchOrigin): Boolean = when (origin) {
  SearchOrigin.NOW -> navigation.route == Route.Feed && navigation.detailStack.isEmpty()
  SearchOrigin.HUBS -> navigation.route == Route.Hubs && hubs.currentHubId == null
}

private fun NavigationState.popDetail(): NavigationState {
  if (detailStack.isEmpty()) return this
  val remaining = detailStack.dropLast(1)
  if (remaining.isNotEmpty()) return copy(detailStack = remaining)
  return copy(
    route = when (detailReturnDestination) {
      DetailReturnDestination.FEED -> Route.Feed
      DetailReturnDestination.SEARCH -> Route.Search
    },
    detailStack = emptyList(),
    detailReturnDestination = DetailReturnDestination.FEED,
  )
}
