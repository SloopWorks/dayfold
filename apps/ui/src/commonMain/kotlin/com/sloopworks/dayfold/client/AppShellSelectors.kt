package com.sloopworks.dayfold.client

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import org.reduxkotlin.compose.SelectorStore
import org.reduxkotlin.compose.selectorState
enum class BackTarget {
  FeedDetail,
  Audience,
  Timeline,
  HubList,
  FeedDetailFromHub,
  Account,
  Members,
  DeviceFlow,
  JoinInvite,
}

/** Minimal state needed by [FeedApp] to choose route, overlay, and back ownership. */
@Immutable
data class AppShellState(
  val route: Route,
  val detailCardId: String?,
  val currentHubId: String?,
  val deviceResuming: Boolean,
  val timelineDetailOpen: Boolean,
  val backTarget: BackTarget?,
)

/** Pure shell projection. It deliberately excludes all feature collections and profile data. */
fun appShellState(state: AppState): AppShellState {
  val detailCardId = currentDetailCard(state)?.id
  val backTarget = when {
    state.deviceResuming -> null
    state.audienceSheetOpen -> BackTarget.Audience
    state.route == Route.Feed && detailCardId != null -> BackTarget.FeedDetail
    state.route == Route.Hubs && state.timelineDetail != null -> BackTarget.Timeline
    state.route == Route.Hubs && state.currentHubId != null && state.hubFromDetail -> BackTarget.FeedDetailFromHub
    state.route == Route.Hubs && state.currentHubId != null -> BackTarget.HubList
    state.route == Route.Account -> BackTarget.Account
    state.route == Route.Members || state.route == Route.Devices || state.route == Route.Proximity -> BackTarget.Members
    state.route == Route.Invite -> BackTarget.Members
    state.route == Route.AuthorizeDevice || state.route == Route.EnterCode ||
      state.route == Route.ScanPrimer || state.route == Route.ScanDevice || state.route == Route.ScanDenied -> BackTarget.DeviceFlow
    state.route == Route.JoinInvite -> BackTarget.JoinInvite
    else -> null
  }
  return AppShellState(
    route = state.route,
    detailCardId = detailCardId,
    currentHubId = state.currentHubId,
    deviceResuming = state.deviceResuming,
    timelineDetailOpen = state.timelineDetail != null,
    backTarget = backTarget,
  )
}

@Composable
internal fun rememberAppShellState(store: SelectorStore<AppState>): AppShellState {
  val state by store.selectorState(::appShellState)
  return state
}

@Immutable
data class HubRouteState(
  val activeFamilyId: String?,
  val currentHubId: String?,
  val fromFeedDetail: Boolean,
  val audienceSheetOpen: Boolean,
)

fun hubRouteState(state: AppState): HubRouteState = HubRouteState(
  activeFamilyId = state.activeFamilyId,
  currentHubId = state.currentHubId,
  fromFeedDetail = state.hubFromDetail,
  audienceSheetOpen = state.audienceSheetOpen,
)
