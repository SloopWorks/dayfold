package com.sloopworks.dayfold.client

/** Hub-list, hub-detail, timeline, and audience-sheet transitions. */
fun reduceHubs(state: AppState, action: Any): AppState = when (action) {
  is OpenHubs -> state.copy(route = Route.Hubs, hubs = state.hubs.copy(
    currentHubId = null, currentHubTree = null, currentHubRequest = null, error = null,
    fromFeedDetail = action.returnDestination == HubReturnDestination.FEED_DETAIL,
    audienceSheetOpen = false, currentAudience = null, currentAudienceRequest = null, audienceError = null))
  is OpenFeed -> state.copy(route = Route.Feed, hubs = state.hubs.copy(fromFeedDetail = false))
  is HubsLoaded -> {
    val h = state.hubs
    val openHubRemoved = h.currentHubId != null && action.hubs.none { it.id == h.currentHubId }
    state.copy(hubs = h.copy(hubs = action.hubs, busy = false,
      currentHubId = h.currentHubId?.takeUnless { openHubRemoved },
      currentHubTree = h.currentHubTree.takeUnless { openHubRemoved },
      currentHubRequest = h.currentHubRequest.takeUnless { openHubRemoved },
      timelineDetail = h.timelineDetail.takeUnless { openHubRemoved },
      audienceSheetOpen = h.audienceSheetOpen && !openHubRemoved,
      currentAudience = h.currentAudience.takeUnless { openHubRemoved },
      currentAudienceRequest = h.currentAudienceRequest.takeUnless { openHubRemoved },
      audienceError = h.audienceError.takeUnless { openHubRemoved }))
  }
  is HubsFailed -> state.copy(hubs = state.hubs.copy(busy = false, error = action.message))
  is OpenHub -> state.copy(route = Route.Hubs, hubs = state.hubs.copy(currentHubId = action.hubId, currentHubTree = null,
    currentHubRequest = action.request, busy = true, error = null, focusBlockId = action.focusBlockId,
    fromFeedDetail = action.returnDestination == HubReturnDestination.FEED_DETAIL, showHidden = false,
    timelineDetail = null, audienceSheetOpen = false, currentAudience = null,
    currentAudienceRequest = null, audienceError = null))
  is HubTreeLoaded -> if (state.hubs.currentHubId == action.hubId && state.hubs.currentHubRequest == action.request)
    state.copy(hubs = state.hubs.copy(busy = false, currentHubTree = action.tree, error = null)) else state
  is HubNotFound -> state.copy(hubs = state.hubs.copy(busy = false, currentHubId = null, currentHubTree = null,
    currentHubRequest = null, error = "That hub is no longer available.", timelineDetail = null,
    audienceSheetOpen = false, currentAudience = null, currentAudienceRequest = null, audienceError = null))
  is CloseHub -> state.copy(hubs = state.hubs.copy(currentHubId = null, currentHubTree = null, currentHubRequest = null,
    focusBlockId = null, showHidden = false, timelineDetail = null, fromFeedDetail = false,
    audienceSheetOpen = false, currentAudience = null, currentAudienceRequest = null, audienceError = null))
  is CloseHubToFeed -> state.copy(route = Route.Feed, hubs = state.hubs.copy(currentHubId = null, currentHubTree = null,
    currentHubRequest = null, focusBlockId = null, showHidden = false, timelineDetail = null,
    fromFeedDetail = false, audienceSheetOpen = false, currentAudience = null,
    currentAudienceRequest = null, audienceError = null))
  is OpenTimelineDetail -> state.copy(hubs = state.hubs.copy(timelineDetail = action.scale))
  is CloseTimelineDetail -> state.copy(hubs = state.hubs.copy(timelineDetail = null))
  is SetHubFilter -> state.copy(hubs = state.hubs.copy(filter = action.filter))
  is HiddenLoaded -> state.copy(hubs = state.hubs.copy(hiddenIds = action.ids))
  is SetShowHidden -> state.copy(hubs = state.hubs.copy(showHidden = action.show))
  is OpenAudienceSheet -> state.copy(hubs = state.hubs.copy(audienceSheetOpen = true, currentAudience = null,
    currentAudienceRequest = null, audienceError = null))
  is HubAudienceRequested -> if (state.hubs.audienceSheetOpen && state.hubs.currentHubId == action.hubId &&
      state.hubs.currentHubRequest?.generation == action.request.generation)
    state.copy(hubs = state.hubs.copy(currentAudienceRequest = action.request, audienceError = null)) else state
  is HubAudienceLoaded -> if (state.hubs.audienceSheetOpen && state.hubs.currentHubId == action.hubId &&
      state.hubs.currentAudienceRequest == action.request)
    state.copy(hubs = state.hubs.copy(currentAudience = action.audience, audienceError = null)) else state
  is CloseAudienceSheet -> state.copy(hubs = state.hubs.copy(audienceSheetOpen = false, currentAudience = null,
    currentAudienceRequest = null, audienceError = null))
  is AudienceFailed -> if (state.hubs.audienceSheetOpen && state.hubs.currentHubId == action.hubId &&
      state.hubs.currentAudienceRequest == action.request) state.copy(hubs = state.hubs.copy(audienceError = action.message)) else state
  is HubManageFailed -> if (state.hubs.audienceSheetOpen && state.hubs.currentHubId == action.hubId &&
      state.hubs.currentAudienceRequest == action.request) state.copy(hubs = state.hubs.copy(audienceError = action.message)) else state
  else -> state
}
