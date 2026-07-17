package com.sloopworks.dayfold.client

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.backhandler.PredictiveBackHandler
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.sloopworks.dayfold.client.ui.loading.rememberReduceMotion
import kotlin.coroutines.cancellation.CancellationException
import com.sloopworks.dayfold.client.cards.CardAction
import com.sloopworks.dayfold.client.cards.PlatformUriHandler
import com.sloopworks.dayfold.client.cards.DetailScreen
import com.sloopworks.dayfold.client.cards.LocalAnimatedVisibilityScope
import com.sloopworks.dayfold.client.cards.LocalSharedTransitionScope
import com.sloopworks.dayfold.client.theme.DayfoldTheme
import org.reduxkotlin.compose.SelectorStore
import org.reduxkotlin.compose.selectorState

@Composable
internal fun HubsHost(
  store: SelectorStore<AppState>,
  commands: DayfoldCommandPort,
  onCardAction: (CardAction) -> Unit = {},
  hubListState: LazyListState = rememberLazyListState(),
) {
  val route by store.selectorState(::hubRouteState)
  // ADR 0045: timeline open/close callbacks dispatch to the store; the detail scale is state
  val onOpenTimeline: (TimelineScale) -> Unit = { scale -> store.dispatch(OpenTimelineDetail(scale)) }
  val onCloseTimeline: () -> Unit = { store.dispatch(CloseTimelineDetail) }
  val reduceMotion = rememberReduceMotion()
  val slidePx = with(LocalDensity.current) { 30.dp.roundToPx() }
  androidx.compose.foundation.layout.Box {
    AnimatedContent(
      targetState = route.currentHubId != null,   // false = list, true = detail
      transitionSpec = {
        val anim = if (reduceMotion) NavAnim.Snap
          else if (targetState) NavAnim.SharedZForward else NavAnim.SharedZBackward
        anim.toContentTransform(slidePx)
      },
      contentKey = { it },
      label = "hub-list-detail",
    ) { showDetail ->
      if (showDetail) {
        val state by store.selectorState(::hubDetailViewState)
        HubDetailScreen(
          // the on-screen back arrow mirrors system back: cross back to the originating detail when deep-linked.
          state,
          onBack = {
            route.currentHubId?.let { id ->
              commands.closeHub(
                id,
                if (route.fromFeedDetail) HubReturnDestination.FEED_DETAIL else HubReturnDestination.HUB_LIST,
              )
            }
          },
          onOpenAudience = { route.currentHubId?.let { hubId ->
            store.dispatch(OpenAudienceSheet)
            route.activeFamilyId?.let { familyId ->
              commands.loadAudience(familyId, hubId)
            }
          } },
          onRetry = { route.currentHubId?.let { id ->
            route.activeFamilyId?.let { familyId ->
              commands.openHub(familyId, id)
            }
          } },
          onToggleItem = { blockId, itemId, done -> route.activeFamilyId?.let { familyId ->
            commands.toggleItem(familyId, blockId, itemId, done)
          } },
          onRetryBlock = { blockId -> route.activeFamilyId?.let { familyId ->
            commands.retryBlock(familyId, blockId)
          } },
          onSyncNow = commands::refresh,
          onDeleteBlock = { blockId -> route.activeFamilyId?.let { familyId ->
            commands.deleteBlock(familyId, blockId)
          } },
          onHideBlock = { blockId -> route.activeFamilyId?.let { familyId ->
            commands.hideBlock(familyId, blockId)
          } },
          onUnhideBlock = { blockId -> route.activeFamilyId?.let { familyId ->
            commands.unhideBlock(familyId, blockId)
          } },
          onSetShowHidden = { store.dispatch(SetShowHidden(it)) },
          onOpenTimeline = onOpenTimeline, onCloseTimeline = onCloseTimeline, onCardAction = onCardAction,
        )
      } else {
        val state = rememberHubListViewState(store)
        LaunchedEffect(Unit) {
          if (!state.hasAnyHubs) commands.loadHubs()
        }
        HubListScreen(
          state,
          onOpenHub = { hubId -> route.activeFamilyId?.let { familyId ->
            commands.openHub(familyId, hubId)
          } },
          onFilter = { store.dispatch(SetHubFilter(it)) },
          onRetry = commands::loadHubs,
          hubListState = hubListState,
        )
      }
    }
    // ADR 0053 DC5: once the audience loads, an owner/co-owner (canManage) gets the full
    // People management sheet; everyone else (incl. while aud is still loading/erroring)
    // keeps the existing read-only WhoCanSeeSheet — unchanged.
    if (route.audienceSheetOpen) {
      val audienceState by store.selectorState(::hubAudienceViewState)
      val aud = audienceState.audience
      if (aud != null && aud.canManage) {
        HubPeopleSheet(
          audience = aud,
          onSetRole = { uid, role -> route.currentHubId?.let { hubId -> route.activeFamilyId?.let { familyId ->
            commands.setHubRole(familyId, hubId, uid, role)
          } } },
          onRemove = { uid -> route.currentHubId?.let { hubId -> route.activeFamilyId?.let { familyId ->
            commands.removeHubParticipant(familyId, hubId, uid)
          } } },
          onSetVisibility = { vis -> route.currentHubId?.let { hubId -> route.activeFamilyId?.let { familyId ->
            commands.setHubVisibility(familyId, hubId, vis)
          } } },
          onDismiss = { store.dispatch(CloseAudienceSheet) },
          // ADR 0053 DC5 code-review fix — surface a failed role/remove/visibility write
          // (HubEngine dispatches HubManageFailed onto this same slot) instead of silently
          // dropping it; the sheet stays pure, this is just an input string.
          errorMessage = audienceState.error,
        )
      } else {
        WhoCanSeeSheet(audienceState, onClose = { store.dispatch(CloseAudienceSheet) }, onRetryAudience = {
          route.currentHubId?.let { hubId -> route.activeFamilyId?.let { familyId ->
            commands.loadAudience(familyId, hubId)
          } }
        })  // overlay
      }
    }
  }
}
