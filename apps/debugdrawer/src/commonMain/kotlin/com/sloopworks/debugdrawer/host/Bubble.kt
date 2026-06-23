package com.sloopworks.debugdrawer.host

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.sloopworks.debugdrawer.theme.Corner
import com.sloopworks.debugdrawer.theme.DrawerColors
import com.sloopworks.debugdrawer.theme.LocalReducedMotion
import com.sloopworks.debugdrawer.theme.SailMark
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The floating entry point (C1). 56dp, draggable; on release it edge-snaps to the
 * nearest horizontal edge (RTL-aware, R8). Position is driven by `Modifier.offset { }`
 * (the lambda overload defers the read to placement → no per-frame recomposition, R7);
 * snap uses `Animatable.animateTo` (or an instant snap under reduced motion).
 *
 * Overlay scope (R6): this fills the host's in-composition Box, so it floats only over
 * the wrapped app content — not over app-spawned separate windows.
 */
@Composable
fun DebugBubble(
  colors: DrawerColors,
  badge: Int,
  initialCorner: Corner,
  edgeSnap: Boolean,
  onOpen: () -> Unit,
) {
  BoxWithConstraints(Modifier.fillMaxSize()) {
    val density = LocalDensity.current
    val layoutDir = LocalLayoutDirection.current
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()

    val sizePx = with(density) { 56.dp.toPx() }
    val maxX = (constraints.maxWidth - sizePx).coerceAtLeast(0f)
    val maxY = (constraints.maxHeight - sizePx).coerceAtLeast(0f)

    val startRight = when (initialCorner) {
      Corner.TOP_END, Corner.BOTTOM_END -> layoutDir == LayoutDirection.Ltr
      Corner.TOP_START, Corner.BOTTOM_START -> layoutDir == LayoutDirection.Rtl
    }
    val startBottom = initialCorner == Corner.BOTTOM_END || initialCorner == Corner.BOTTOM_START

    val offsetX = remember(maxX) { Animatable(if (startRight) maxX else 0f) }
    val offsetY = remember(maxY) { Animatable(if (startBottom) maxY else 0f) }

    val desc = if (badge > 0) "Open debug drawer, $badge unread" else "Open debug drawer"

    Box(
      Modifier
        .offset { IntOffset(offsetX.value.roundToInt(), offsetY.value.roundToInt()) }
        .size(56.dp)
        .pointerInput(maxX, maxY) {
          detectDragGestures(
            onDrag = { change, drag ->
              change.consume()
              scope.launch { offsetX.snapTo((offsetX.value + drag.x).coerceIn(0f, maxX)) }
              scope.launch { offsetY.snapTo((offsetY.value + drag.y).coerceIn(0f, maxY)) }
            },
            onDragEnd = {
              if (edgeSnap) {
                val target = if (offsetX.value > maxX / 2f) maxX else 0f
                scope.launch { if (reduced) offsetX.snapTo(target) else offsetX.animateTo(target) }
              }
            },
          )
        }
        .clip(CircleShape)
        .background(colors.accent)
        .clickable(onClickLabel = "Open debug drawer") { onOpen() }
        .semantics { contentDescription = desc },
      contentAlignment = Alignment.Center,
    ) {
      SailMark(colors.onAccent, Modifier.size(26.dp))
    }
  }
}
