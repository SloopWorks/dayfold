@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.familyai.client.cards

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

// CL-7b — container transform plumbing. FeedApp wraps the host in a
// SharedTransitionLayout and provides BOTH scopes via CompositionLocals so the
// feed card and the detail root can share bounds keyed by card id WITHOUT
// threading scopes through every signature. Null in snapshot tests / non-shared
// contexts → the helper is a no-op (graceful; snapshots unchanged).
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** Apply shared-bounds keyed by card id when both scopes are present — morphs the
 *  tapped card's bounds into the detail container (and back). No-op otherwise. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.cardSharedBounds(id: String): Modifier {
  val sts = LocalSharedTransitionScope.current ?: return this
  val avs = LocalAnimatedVisibilityScope.current ?: return this
  return with(sts) {
    this@cardSharedBounds.sharedBounds(
      rememberSharedContentState(key = "card-$id"),
      animatedVisibilityScope = avs,
    )
  }
}
