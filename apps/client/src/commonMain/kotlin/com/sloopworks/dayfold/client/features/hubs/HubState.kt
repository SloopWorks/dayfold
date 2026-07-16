package com.sloopworks.dayfold.client

/**
 * The complete Redux projection for the Hubs surface. It is deliberately not
 * persisted: lists and hidden IDs are DB-backed projections, while open-tree
 * and audience fields are request-correlated transient UI state.
 */
data class HubState(
  val hubs: List<Hub> = emptyList(),
  val busy: Boolean = false,
  val error: String? = null,
  val filter: String = "all",
  val currentHubId: String? = null,
  val currentHubTree: HubTree? = null,
  val currentHubRequest: HubRequestKey? = null,
  val focusBlockId: String? = null,
  val fromFeedDetail: Boolean = false,
  val timelineDetail: TimelineScale? = null,
  val hiddenIds: Set<String> = emptySet(),
  val showHidden: Boolean = false,
  val audienceSheetOpen: Boolean = false,
  val currentAudience: HubAudience? = null,
  val currentAudienceRequest: HubRequestKey? = null,
  val audienceError: String? = null,
)
