package com.sloopworks.dayfold.android

import androidx.activity.ComponentActivity
import com.sloopworks.debugdrawer.DebugPlugin
import com.sloopworks.debugdrawer.redux.ReduxDevToolsDebugPlugin
import com.sloopworks.debugdrawer.swip.SwipInspectorPlugin
import com.sloopworks.debugdrawer.bugreport.BugReportQueuePlugin

// Debug variant only: redux DevTools + (when the gated capture sink is installed) the SWIP
// inspector. Both are debug-only modules wired debugImplementation → release never references them.
fun debugDrawerPlugins(activity: ComponentActivity): List<DebugPlugin> = buildList {
  add(ReduxDevToolsDebugPlugin())
  SwipInspectorGlue.debugSink()?.let { sink ->
    add(SwipInspectorPlugin(sink.entries, SwipInspectorGlue.secureWindow(activity)))
  }
  add(SwipErrorsTriggerPlugin())
  // The bug-report lane, read-only. Uses the SAME lane the reporter captures
  // into and the uploader drains, so what the panel shows is what is really
  // queued — not a second view of a different store.
  add(BugReportQueuePlugin({ BugReporterHolder.lane }) { System.currentTimeMillis() })
}
