package com.sloopworks.dayfold.android

import androidx.activity.ComponentActivity
import com.sloopworks.debugdrawer.DebugPlugin
import com.sloopworks.debugdrawer.redux.ReduxDevToolsDebugPlugin
import com.sloopworks.dayfold.swip.inspector.SwipInspectorPlugin

// Debug variant only: redux DevTools + (when the gated capture sink is installed) the SWIP
// inspector. Both are debug-only modules wired debugImplementation → release never references them.
fun debugDrawerPlugins(activity: ComponentActivity): List<DebugPlugin> = buildList {
  add(ReduxDevToolsDebugPlugin())
  SwipInspectorGlue.debugSink()?.let { sink ->
    add(SwipInspectorPlugin(sink.entries, SwipInspectorGlue.secureWindow(activity)))
  }
  add(SwipErrorsTriggerPlugin())
}
