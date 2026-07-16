package com.sloopworks.dayfold.client

/** Hub-list, hub-detail, timeline, and audience-sheet transitions. */
fun reduceHubs(state: AppState, action: Any): AppState = when (action) {
  is OpenHubs -> state.copy(route = Route.Hubs, currentHubId = null, currentHubTree = null,
    currentHubRequest = null, hubError = null,
    hubFromDetail = action.returnDestination == HubReturnDestination.FEED_DETAIL,
    audienceSheetOpen = false, currentHubAudience = null, currentHubAudienceRequest = null, audienceError = null)
  is OpenFeed -> state.copy(route = Route.Feed, hubFromDetail = false)
  is HubsLoaded -> {
    val openHubRemoved = state.currentHubId != null && action.hubs.none { it.id == state.currentHubId }
    state.copy(hubs = action.hubs, hubsBusy = false,
      currentHubId = state.currentHubId?.takeUnless { openHubRemoved },
      currentHubTree = state.currentHubTree.takeUnless { openHubRemoved },
      currentHubRequest = state.currentHubRequest.takeUnless { openHubRemoved },
      timelineDetail = state.timelineDetail.takeUnless { openHubRemoved },
      audienceSheetOpen = state.audienceSheetOpen && !openHubRemoved,
      currentHubAudience = state.currentHubAudience.takeUnless { openHubRemoved },
      currentHubAudienceRequest = state.currentHubAudienceRequest.takeUnless { openHubRemoved },
      audienceError = state.audienceError.takeUnless { openHubRemoved })
  }
  is HubsFailed -> state.copy(hubsBusy = false, hubError = action.message)
  is OpenHub -> state.copy(route = Route.Hubs, currentHubId = action.hubId, currentHubTree = null,
    currentHubRequest = action.request, hubsBusy = true, hubError = null, hubFocusBlockId = action.focusBlockId,
    hubFromDetail = action.returnDestination == HubReturnDestination.FEED_DETAIL, showHidden = false,
    timelineDetail = null, audienceSheetOpen = false, currentHubAudience = null,
    currentHubAudienceRequest = null, audienceError = null)
  is HubTreeLoaded -> if (state.currentHubId == action.hubId && state.currentHubRequest == action.request)
    state.copy(hubsBusy = false, currentHubTree = action.tree, hubError = null) else state
  is HubNotFound -> state.copy(hubsBusy = false, currentHubId = null, currentHubTree = null,
    currentHubRequest = null, hubError = "That hub is no longer available.", timelineDetail = null,
    audienceSheetOpen = false, currentHubAudience = null, currentHubAudienceRequest = null, audienceError = null)
  is CloseHub -> state.copy(currentHubId = null, currentHubTree = null, currentHubRequest = null,
    hubFocusBlockId = null, showHidden = false, timelineDetail = null, hubFromDetail = false,
    audienceSheetOpen = false, currentHubAudience = null, currentHubAudienceRequest = null, audienceError = null)
  is CloseHubToFeed -> state.copy(route = Route.Feed, currentHubId = null, currentHubTree = null,
    currentHubRequest = null, hubFocusBlockId = null, showHidden = false, timelineDetail = null,
    hubFromDetail = false, audienceSheetOpen = false, currentHubAudience = null,
    currentHubAudienceRequest = null, audienceError = null)
  is OpenTimelineDetail -> state.copy(timelineDetail = action.scale)
  is CloseTimelineDetail -> state.copy(timelineDetail = null)
  is SetHubFilter -> state.copy(hubFilter = action.filter)
  is HiddenLoaded -> state.copy(hiddenIds = action.ids)
  is SetShowHidden -> state.copy(showHidden = action.show)
  is OpenAudienceSheet -> state.copy(audienceSheetOpen = true, currentHubAudience = null,
    currentHubAudienceRequest = null, audienceError = null)
  is HubAudienceRequested -> if (state.audienceSheetOpen && state.currentHubId == action.hubId &&
      state.currentHubRequest?.generation == action.request.generation)
    state.copy(currentHubAudienceRequest = action.request, audienceError = null) else state
  is HubAudienceLoaded -> if (state.audienceSheetOpen && state.currentHubId == action.hubId &&
      state.currentHubAudienceRequest == action.request)
    state.copy(currentHubAudience = action.audience, audienceError = null) else state
  is CloseAudienceSheet -> state.copy(audienceSheetOpen = false, currentHubAudience = null,
    currentHubAudienceRequest = null, audienceError = null)
  is AudienceFailed -> if (state.audienceSheetOpen && state.currentHubId == action.hubId &&
      state.currentHubAudienceRequest == action.request) state.copy(audienceError = action.message) else state
  is HubManageFailed -> if (state.audienceSheetOpen && state.currentHubId == action.hubId &&
      state.currentHubAudienceRequest == action.request) state.copy(audienceError = action.message) else state
  else -> state
}
