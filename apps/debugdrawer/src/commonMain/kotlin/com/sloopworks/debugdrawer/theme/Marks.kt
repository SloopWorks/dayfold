package com.sloopworks.debugdrawer.theme

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path

/**
 * The SloopWorks sail mark — "a sail, reduced to geometry" (BRAND.md). Concrete at
 * the foundation; the pluggable [DrawerMark] abstraction for consumer custom marks
 * is deferred to Plan E (R10b). Path is the simplest sail triangle on a 48-grid:
 * `M18 7 L18 38 L40 38 Z`, scaled to the draw size.
 */
fun sailPath(size: Float): Path {
  val k = size / 48f
  return Path().apply {
    moveTo(18f * k, 7f * k)
    lineTo(18f * k, 38f * k)
    lineTo(40f * k, 38f * k)
    close()
  }
}

/** Draws the SloopWorks sail in [color], filling the smallest dimension of [modifier]. */
@Composable
fun SailMark(color: Color, modifier: Modifier) {
  Canvas(modifier) {
    drawPath(sailPath(size.minDimension), color)
  }
}
