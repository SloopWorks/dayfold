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
  // ADR 0064 — the open response sheet + the rules the derived lane is suppressed by. Both are
  // read here so the feed host can mount ONE sheet for both Now lanes.
  val responseSheet: ResponseSheetState?,
  val responseRules: List<ContentResponse>,
  val responseReceipt: ResponseReceipt?,
  val viewerUserId: String?,
  // WI-461 (ADR 0063 §5) — nowFeed() reads these to surface the aggregate Calendar Check card;
  // omitting them here would silently keep that card unreachable, since the ranker only sees what
  // this projection carries (exactly the responseRules comment below, for the same reason).
  val calendarCheck: CalendarCheckState,
  val calendarLastCheckAt: String?,
)

fun feedViewState(state: AppState): FeedViewState = FeedViewState(
  cards = state.content.cards,
  hubs = state.hubs.hubs,
  memberCount = state.familyAdmin.members.size,
  syncing = state.content.syncing,
  error = state.content.error,
  displayName = state.profile.displayName,
  avatarColor = state.profile.avatarColor,
  avatarRef = state.profile.avatarRef,
  nowContent = state.now.content,
  surfacing = state.now.surfacing,
  responseSheet = state.responses.sheet,
  responseRules = state.responses.rules,
  responseReceipt = state.responses.lastReceipt,
  viewerUserId = state.session.session?.userId,
  calendarCheck = state.calendar.check,
  calendarLastCheckAt = state.calendar.settings.lastCheckAt,
)

/** Small input used to memoize ranking away from store notification delivery. */
internal fun FeedViewState.rankingState(): AppState = AppState(
  content = ContentState(cards = cards),
  hubs = HubState(hubs = hubs),
  now = NowState(content = nowContent, surfacing = surfacing),
  // ADR 0064 — nowFeed() suppresses against these; omitting them here would silently render a
  // muted subject, since the ranker only sees what this projection carries.
  responses = ResponseState(rules = responseRules),
  session = SessionState(session = viewerUserId?.let { Session("", "", userId = it) }),
  calendar = CalendarState(check = calendarCheck, settings = CalendarSettings(lastCheckAt = calendarLastCheckAt)),
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
