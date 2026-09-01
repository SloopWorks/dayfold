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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.backhandler.PredictiveBackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sloopworks.dayfold.client.ui.loading.EmptyState
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

@Suppress("DEPRECATION")
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun ContentHost(
  store: SelectorStore<AppState>,
  detailCardId: String?,
  handle: (CardAction) -> Unit,
  // WI-461 — CalendarReviewHost needs the command port directly (ignoreCalendarItem,
  // confirmCalendarMatch, ...), unlike the other callbacks below which the shell narrows itself.
  commands: DayfoldCommandPort,
  onConnectDevice: () -> Unit = {},
  onNavHubs: () -> Unit = {},
  onRefresh: () -> Unit = {},
  onNowShown: (Set<String>) -> Unit = {},
  onSearch: () -> Unit = {},
  detailBackContentDescription: String = "Back to feed",
  // ADR 0063 §5 — the aggregate Calendar Check card's Review tap, forwarded to FeedScreen.
  onOpenCalendarReview: () -> Unit = {},
  feedListState: LazyListState = rememberLazyListState(),
) {
  val targetKey: String? = detailCardId            // top of the detail stack (null = feed)
  val reduceMotion = rememberReduceMotion()
  // feedListState is hoisted to FeedApp (survives the tab swap too) and threaded in; the default
  // keeps ContentHost usable standalone in tests. Passed to FeedScreen's Now-feed LazyColumn.

  // Back GESTURE (predictive back): the OS drives the window "peek" during the drag; we do
  // NOT scrub the transition ourselves. Driving AnimatedContent from a SeekableTransitionState
  // silently drops the sharedBounds container-transform (the bounds snap to target → a flat
  // crossfade) in androidx.compose.animation 1.11.x–1.12. So we commit on release → NavBack,
  // and the plain AnimatedContent below plays the reverse morph. Cancel = no-op (rethrow).
  PredictiveBackHandler(enabled = detailCardId != null) { progress ->
    try {
      progress.collect { /* OS renders the peek; the container-transform plays on commit */ }
      store.dispatch(NavBack)                    // COMMIT
    } catch (e: CancellationException) {
      throw e                                    // CANCEL — no state change to undo; must rethrow
    }
  }

  // CL-7b container transform via a plain, state-driven AnimatedContent keyed on `targetKey`
  // (the top of the detail stack). A SeekableTransitionState here does NOT render the
  // sharedBounds morph (see above) — the plain API does. Tap-open, hero-arrow-back, deep
  // pops, and predictive-back-commit all flow through `targetKey`. The card morphs into the
  // detail via SharedTransitionLayout (key "card-$id"); reduced-motion → snap (dur 0).
  SharedTransitionLayout {
    AnimatedContent(
      targetState = targetKey,
      contentKey = { it },
      transitionSpec = {
        // asymmetric fade+slide (NavMotion.HeroMs open / NavMotion.StandardMs close); the default AnimatedContent spec
        // adds a scaleIn that fights the shared morph, so specify it explicitly.
        val opening = targetState != null
        val dur = if (reduceMotion) NavMotion.ReducedMs else if (opening) NavMotion.HeroMs else NavMotion.StandardMs
        (fadeIn(tween(dur)) + slideInVertically(tween(dur)) { h -> h / 16 }) togetherWith fadeOut(tween(dur))
      },
    ) { id ->
      CompositionLocalProvider(
        LocalSharedTransitionScope provides this@SharedTransitionLayout,
        LocalAnimatedVisibilityScope provides this@AnimatedContent,
      ) {
        if (id != null) {
          val detail by store.selectorState(::feedDetailViewState)
          val resolvedDetail = detail
          if (resolvedDetail != null) {
            DetailScreen(
              resolvedDetail.card,
              hubName = resolvedDetail.hubName,
              onBack = { store.dispatch(NavBack) },
              onAction = handle,
              backContentDescription = detailBackContentDescription,
            )
          } else {
            UnavailableDetailScreen(
              onBack = { store.dispatch(NavBack) },
              backContentDescription = detailBackContentDescription,
            )
          }
        } else {
          val feed by store.selectorState(::feedViewState)
          // The Now projection is clock-dependent even when Redux is idle. Keep the ticker scoped
          // to this active route; leaving Feed disposes it automatically.
          val feedNow = rememberNowClock(enabled = true)
          FeedScreen(
            feed,
            onAction = handle,
            onOpenAccount = { store.dispatch(OpenAccount) },
            onConnectDevice = onConnectDevice,
            onNavHubs = onNavHubs,
            onRefresh = onRefresh,
            onShown = onNowShown,
            onSearch = onSearch,
            onOpenCalendarReview = onOpenCalendarReview,
            now = feedNow,
            listState = feedListState,
          )
        }
      }
    }
  }
}

/** Keeps a vanished selection user-controlled: sync may replace its data, but only Back exits it. */
@Composable
private fun UnavailableDetailScreen(
  onBack: () -> Unit,
  backContentDescription: String,
) {
  Column(Modifier.fillMaxSize().statusBarsPadding()) {
    TextButton(
      onClick = onBack,
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        .semantics { contentDescription = backContentDescription },
    ) {
      Icon(DayfoldIcons.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
      Text("Back", modifier = Modifier.padding(start = 4.dp))
    }
    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
      EmptyState(
        title = "This card is no longer available",
        body = "It may have been removed while Dayfold was updating.",
      )
    }
  }
}

// Hubs surface host (ADR 0006): list ↔ detail substate driven by currentHubId.
// A LaunchedEffect fetches the list on entry; the bottom nav flips back to Feed.
