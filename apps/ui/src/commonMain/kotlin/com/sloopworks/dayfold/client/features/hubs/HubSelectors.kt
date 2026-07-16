package com.sloopworks.dayfold.client

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import org.reduxkotlin.compose.SelectorStore
import org.reduxkotlin.compose.selectorState
import org.reduxkotlin.granular.memoizedSelector
@Immutable
data class HubListViewState(
  val hasAnyHubs: Boolean,
  val shownHubs: List<Hub>,
  val filter: String,
  val busy: Boolean,
  val error: String?,
)

/**
 * Builds a selector whose expensive filtering is cached by the immutable hubs
 * list and filter value, rather than by every AppState notification.
 */
internal fun memoizedHubListViewState(
  onFilter: () -> Unit = {},
): (AppState) -> HubListViewState {
  val shownHubs = memoizedSelector(
    { state: AppState -> state.hubs },
    { state: AppState -> state.hubFilter },
  ) { hubs, filter ->
    onFilter()
    when (filter) {
      "active" -> hubs.filter { it.status == "active" }
      "planning" -> hubs.filter { it.status == "planning" }
      else -> hubs
      }
  }
  return { state ->
    HubListViewState(
      hasAnyHubs = state.hubs.isNotEmpty(),
      shownHubs = shownHubs(state),
      filter = state.hubFilter,
      busy = state.hubsBusy,
      error = state.hubError,
    )
  }
}

/** One root-hoisted selector instance keeps its memoized result across route recompositions. */
private val selectHubListViewState = memoizedHubListViewState()

fun hubListViewState(state: AppState): HubListViewState = selectHubListViewState(state)

@Composable
internal fun rememberHubListViewState(store: SelectorStore<AppState>): HubListViewState {
  val state by store.selectorState(::hubListViewState)
  return state
}

@Immutable
data class HubDetailViewState(
  val tree: HubTree?,
  val busy: Boolean,
  val hubError: String?,
  val syncError: String?,
  val focusBlockId: String?,
  val hiddenIds: Set<String>,
  val showHidden: Boolean,
  val timelineDetail: TimelineScale?,
  val members: List<FamilyMember>,
  val currentUserId: String?,
)

fun hubDetailViewState(state: AppState): HubDetailViewState = HubDetailViewState(
  tree = state.currentHubTree,
  busy = state.hubsBusy,
  hubError = state.hubError,
  syncError = state.error,
  focusBlockId = state.hubFocusBlockId,
  hiddenIds = state.hiddenIds,
  showHidden = state.showHidden,
  timelineDetail = state.timelineDetail,
  members = state.members,
  currentUserId = state.session?.userId,
)

@Immutable
data class HubAudienceViewState(
  val audience: HubAudience?,
  val error: String?,
  val currentUserId: String?,
)

fun hubAudienceViewState(state: AppState): HubAudienceViewState = HubAudienceViewState(
  audience = state.currentHubAudience,
  error = state.audienceError,
  currentUserId = state.session?.userId,
)
