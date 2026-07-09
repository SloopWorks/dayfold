package com.sloopworks.dayfold.client

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
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
    DayfoldBottomNav(hubsActive = route == Route.Hubs, onNow = onNow, onHubs = onHubs)
  }
}
