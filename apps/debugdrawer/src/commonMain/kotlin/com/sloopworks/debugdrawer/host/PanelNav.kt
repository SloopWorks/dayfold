package com.sloopworks.debugdrawer.host

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * List→detail nav within the open drawer. `null` = the panel list (host home);
 * a non-null id = that panel's detail. Main-thread only (read/written during
 * composition + UI events). Plain state holder so it survives recomposition and
 * is unit-testable without a Compose host.
 */
class PanelNavState {
  var current: String? by mutableStateOf(null)
    private set

  val inDetail: Boolean get() = current != null

  fun open(id: String) { current = id }
  fun back() { current = null }
}

/** Two container classes only at the foundation (R10c); MEDIUM/list-rail come with panels. */
enum class DrawerWidth { COMPACT, EXPANDED }

/** Compact bottom sheet below the breakpoint; side sheet at/above it. */
fun drawerWidthFor(availableWidthDp: Int): DrawerWidth =
  if (availableWidthDp >= 720) DrawerWidth.EXPANDED else DrawerWidth.COMPACT
