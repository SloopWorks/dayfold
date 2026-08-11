package com.sloopworks.dayfold.android

import androidx.activity.ComponentActivity
import com.sloopworks.debugdrawer.DebugPlugin
import com.sloopworks.debugdrawer.components.ComponentsPlugin
import com.sloopworks.debugdrawer.redux.ReduxDevToolsDebugPlugin
import com.sloopworks.debugdrawer.swip.SwipInspectorPlugin

// Debug variant only: redux DevTools + (when the gated capture sink is installed) the SWIP
// inspector. Both are debug-only modules wired debugImplementation → release never references them.
fun debugDrawerPlugins(activity: ComponentActivity): List<DebugPlugin> = buildList {
  add(ReduxDevToolsDebugPlugin())
  SwipInspectorGlue.debugSink()?.let { sink ->
    add(SwipInspectorPlugin(sink.entries, SwipInspectorGlue.secureWindow(activity)))
  }
  add(SwipErrorsTriggerPlugin())
  // Component picker (Gate G): Add to report hands the frozen capture to the
  // SWIP preseeded Bug draft (typed ui_tree part, decor-draw screenshot,
  // node-id highlight). Resolves swip-bugreport 0.1.2 (mavenLocal until the
  // operator publish).
  add(
    ComponentsPlugin(
      registration = ComponentsGlue.registration,
      onAddToReport = { capture, selection ->
        ComponentsHandoffGlue.openPreseededComponentReport(capture, selection)
      },
      onReportWithoutDetails = { BugReporterGlue.openManualBugReport() },
    ),
  )
}
