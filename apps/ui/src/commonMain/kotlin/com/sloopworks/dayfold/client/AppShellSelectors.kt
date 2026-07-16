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
    state.devices.resuming -> null
    state.hubs.audienceSheetOpen -> BackTarget.Audience
    state.navigation.route == Route.Feed && detailCardId != null -> BackTarget.FeedDetail
    state.navigation.route == Route.Hubs && state.hubs.timelineDetail != null -> BackTarget.Timeline
    state.navigation.route == Route.Hubs && state.hubs.currentHubId != null && state.hubs.fromFeedDetail -> BackTarget.FeedDetailFromHub
    state.navigation.route == Route.Hubs && state.hubs.currentHubId != null -> BackTarget.HubList
    state.navigation.route == Route.Account -> BackTarget.Account
    state.navigation.route == Route.Members || state.navigation.route == Route.Devices || state.navigation.route == Route.Proximity -> BackTarget.Members
    state.navigation.route == Route.Invite -> BackTarget.Members
    state.navigation.route == Route.AuthorizeDevice || state.navigation.route == Route.EnterCode ||
      state.navigation.route == Route.ScanPrimer || state.navigation.route == Route.ScanDevice || state.navigation.route == Route.ScanDenied -> BackTarget.DeviceFlow
    state.navigation.route == Route.JoinInvite -> BackTarget.JoinInvite
    else -> null
  }
  return AppShellState(
    route = state.navigation.route,
    detailCardId = detailCardId,
    currentHubId = state.hubs.currentHubId,
    deviceResuming = state.devices.resuming,
    timelineDetailOpen = state.hubs.timelineDetail != null,
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
  activeFamilyId = state.session.activeFamilyId,
  currentHubId = state.hubs.currentHubId,
  fromFeedDetail = state.hubs.fromFeedDetail,
  audienceSheetOpen = state.hubs.audienceSheetOpen,
)
