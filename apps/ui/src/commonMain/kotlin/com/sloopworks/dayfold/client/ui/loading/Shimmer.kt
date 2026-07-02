package com.sloopworks.dayfold.client.ui.loading

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush

/**
 * True when the platform asks for reduced motion. Shimmer falls back to a static
 * dim fill so the animation never runs (WCAG 2.3.3 / vestibular safety).
 */
@Composable expect fun rememberReduceMotion(): Boolean

/**
 * Placeholder fill for skeletons: a horizontal highlight sweep over a base tint.
 * When reduce-motion is on, paints a single static tint (no animation).
 */
fun Modifier.shimmer(): Modifier = composed {
  val base = MaterialTheme.colorScheme.surfaceContainerHigh
  val highlight = MaterialTheme.colorScheme.surfaceContainerHighest
  if (rememberReduceMotion()) {
    background(base)
  } else {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
      initialValue = -600f,
      targetValue = 600f,
      animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Restart),
      label = "shimmer-x",
    )
    background(
      Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(x, 0f),
        end = Offset(x + 300f, 0f),
      ),
    )
  }
}
