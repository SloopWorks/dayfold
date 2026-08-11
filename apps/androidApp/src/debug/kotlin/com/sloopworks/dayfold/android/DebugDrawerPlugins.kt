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
  // Component picker (Gate G). onAddToReport stays null until the typed SWIP
  // handoff artifacts (swip-bugreport 0.1.2) are published — the detail sheet
  // then omits Add to report; picking/inspection is fully functional.
  add(
    ComponentsPlugin(
      registration = ComponentsGlue.registration,
      onAddToReport = null,
      onReportWithoutDetails = { BugReporterGlue.openManualBugReport() },
    ),
  )
}
