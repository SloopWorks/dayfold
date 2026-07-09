package com.sloopworks.dayfold.client

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

// Persistent Feed↔Hubs shell: the bottom bar is a Column sibling BELOW a weighted
// AnimatedContent, so the bar stays put while the tab content slides (shared-axis-X).
// A Column (not a nested Scaffold) avoids double-counting the bottom system inset —
// the content screens keep their own Scaffold(topBar); DayfoldBottomNav consumes the
// nav-bar inset itself. Non-tab routes render OUTSIDE this shell (AppNavHost). ADR 0051.
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun TabShell(
  route: Route,
  reduceMotion: Boolean,
  barVisible: Boolean,
  onNow: () -> Unit,
  onHubs: () -> Unit,
  feedContent: @Composable () -> Unit,
  hubsContent: @Composable () -> Unit,
) {
  val slidePx = with(LocalDensity.current) { 30.dp.roundToPx() }
  Column(Modifier.fillMaxSize()) {
    AnimatedContent(
      targetState = route,
      modifier = Modifier.weight(1f),
      transitionSpec = { navAnimFor(initialState, targetState, reduceMotion).toContentTransform(slidePx) },
      contentKey = { it },
      label = "tab-content",
    ) { r -> if (r == Route.Hubs) hubsContent() else feedContent() }
    // Hide the bar for full-screen details (card detail, timeline overlay) so they morph to
    // FULL screen (ADR 0050) instead of screen-minus-bar. Collapses toward the bottom + fades;
    // the weighted content grows to fill. Reduced motion → instant.
    AnimatedVisibility(
      visible = barVisible,
      enter = if (reduceMotion) EnterTransition.None else expandVertically() + fadeIn(),
      exit = if (reduceMotion) ExitTransition.None else shrinkVertically() + fadeOut(),
    ) {
      DayfoldBottomNav(hubsActive = route == Route.Hubs, onNow = onNow, onHubs = onHubs)
    }
  }
}
