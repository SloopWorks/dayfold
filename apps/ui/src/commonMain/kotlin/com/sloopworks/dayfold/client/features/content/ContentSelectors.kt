package com.sloopworks.dayfold.client

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import org.reduxkotlin.compose.SelectorStore
import org.reduxkotlin.compose.selectorState
import org.reduxkotlin.granular.memoizedSelector
@Immutable
data class FeedViewState(
  val cards: List<Card>,
  val hubs: List<Hub>,
  val memberCount: Int,
  val syncing: Boolean,
  val error: String?,
  val displayName: String?,
  val avatarColor: String?,
  val avatarRef: String?,
  val nowContent: NowContent,
  val surfacing: Map<String, SurfacingRecord>,
)

fun feedViewState(state: AppState): FeedViewState = FeedViewState(
  cards = state.cards,
  hubs = state.hubs.hubs,
  memberCount = state.familyAdmin.members.size,
  syncing = state.syncing,
  error = state.error,
  displayName = state.profile.displayName,
  avatarColor = state.profile.avatarColor,
  avatarRef = state.profile.avatarRef,
  nowContent = state.nowContent,
  surfacing = state.surfacing,
)

/** Small input used to memoize ranking away from store notification delivery. */
internal fun FeedViewState.rankingState(): AppState = AppState(
  cards = cards,
  hubs = HubState(hubs = hubs),
  nowContent = nowContent,
  surfacing = surfacing,
)

@Immutable
data class FeedDetailViewState(val card: Card, val hubName: String?)

fun feedDetailViewState(state: AppState): FeedDetailViewState? {
  val card = currentDetailCard(state) ?: return null
  val hubName = (card.targetHubId ?: card.hubRef)?.let { hubId ->
    state.hubs.hubs.firstOrNull { it.id == hubId }?.title
  }
  return FeedDetailViewState(card, hubName)
}
