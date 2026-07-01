package com.sloopworks.dayfold.client

// Pure back-navigation model (predictive back, Core P0). System back ("up one
// level") is a function of state: an OPEN OVERLAY closes first; otherwise it
// resolves to an existing nav action, or null when the app should NOT consume back
// (an auth-gate / top-level screen → the OS handles it, e.g. back-to-home). The
// reducer's `is Back ->` delegates here; the shell BackHandler enables itself via
// appHandlesBack(). One source of truth.
//
// NOTE: this is reducer-pure. One route has an extra side effect on back that the
// reducer cannot express — Route.Hubs detail also cancels the HubEngine DB tree
// subscription via onCloseHub() (FeedApp HubsHost). The shell BackHandler special-
// cases CloseHub to run that closure; see Task 4. Everything else is dispatch-only.
fun backAction(state: AppState): Action? {
  if (state.deviceResuming) return null                       // "Finishing…" resume beat → let the OS handle back
  if (state.audienceSheetOpen) return CloseAudienceSheet      // an open overlay closes FIRST (before any nav)
  return when (state.route) {
    Route.Feed -> if (state.detailStack.isNotEmpty()) NavBack else null
    Route.Hubs -> if (state.timelineDetail != null) CloseTimelineDetail else if (state.currentHubId != null) CloseHub else null
    Route.Account -> CloseAccount
    Route.Members, Route.Devices, Route.Proximity -> OpenAccount
    Route.AuthorizeDevice, Route.EnterCode, Route.ScanPrimer, Route.ScanDevice, Route.ScanDenied -> CloseDeviceFlow
    Route.JoinInvite -> JoinDismissed
    Route.SignIn, Route.Loading, Route.CreateFamily, Route.AuthError -> null
  }
}

fun appHandlesBack(state: AppState): Boolean = backAction(state) != null
