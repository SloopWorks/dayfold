package com.sloopworks.dayfold.android

import androidx.activity.ComponentActivity
import com.sloopworks.debugdrawer.DebugPlugin
import com.sloopworks.debugdrawer.redux.ReduxDevToolsDebugPlugin
import com.sloopworks.dayfold.swip.config.SwipConfigPlugin
import com.sloopworks.dayfold.swip.inspector.SwipInspectorPlugin

// Debug variant only: redux DevTools + (when the gated capture sink is installed) the SWIP
// inspector + (when swip hands out the config-debug seam) the Config panel. All are debug-only
// modules wired debugImplementation → release never references them.
//
// The Config panel is registered ONLY when swipConfigDebug() is non-null — swip hands the seam
// out solely in a debuggable dev/ci build. A null seam means no panel at all, rather than a
// panel whose overrides silently no-op. This runs AFTER swipInit() (MainActivity hoists it
// above DebugDrawer.install for exactly this reason) — before init the holder is empty and the
// panel would be missing from a debug build.
fun debugDrawerPlugins(activity: ComponentActivity): List<DebugPlugin> = buildList {
  add(ReduxDevToolsDebugPlugin())
  SwipInspectorGlue.debugSink()?.let { sink ->
    add(SwipInspectorPlugin(sink.entries, SwipInspectorGlue.secureWindow(activity)))
  }
  swipConfigDebug()?.let { debug ->
    add(SwipConfigPlugin(debug, SwipInspectorGlue.secureWindow(activity)))
  }
}
