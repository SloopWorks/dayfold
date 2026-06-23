package com.sloopworks.debugdrawer.host

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sloopworks.debugdrawer.theme.DrawerColors

/**
 * The adaptive drawer container (C2). COMPACT → bottom sheet (~92% height, modal
 * scrim); EXPANDED → right side sheet (~420dp, full height). Only the container
 * changes across form factors — content/density/shapes are identical (spec §5,
 * R10c: two width classes at the foundation). The bubble overlay is in-composition
 * (R6) — it floats over the wrapped app content, not over app-spawned windows.
 */
@Composable
fun DrawerScaffold(
  width: DrawerWidth,
  colors: DrawerColors,
  onDismiss: () -> Unit,
  content: @Composable () -> Unit,
) {
  Box(Modifier.fillMaxSize()) {
    // Scrim — dismiss on tap (no ripple).
    Box(
      Modifier
        .fillMaxSize()
        .background(colors.scrim)
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          onClickLabel = "Dismiss debug drawer",
        ) { onDismiss() }
        .semantics { contentDescription = "Dismiss debug drawer" }
    )
    val sheetModifier = when (width) {
      DrawerWidth.COMPACT -> Modifier
        .align(Alignment.BottomCenter)
        .fillMaxWidth()
        .fillMaxHeight(0.92f)
        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
      DrawerWidth.EXPANDED -> Modifier
        .align(Alignment.CenterEnd)
        .width(420.dp)
        .fillMaxHeight()
    }
    Surface(sheetModifier, color = colors.surface) {
      Column { content() }
    }
  }
}

/** Convenience: header + divider + body, used inside [DrawerScaffold]. */
@Composable
fun DrawerBody(header: @Composable () -> Unit, colors: DrawerColors, body: @Composable () -> Unit) {
  Column(Modifier.fillMaxSize()) {
    header()
    HorizontalDivider(color = colors.border)
    body()
  }
}
