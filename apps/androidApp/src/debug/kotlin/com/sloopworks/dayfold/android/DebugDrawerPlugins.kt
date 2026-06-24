package com.sloopworks.dayfold.android

import com.sloopworks.debugdrawer.DebugPlugin
import com.sloopworks.debugdrawer.redux.ReduxDevToolsDebugPlugin

// Debug variant only: the redux DevTools inspector embedded as a drawer panel. This
// is the one debug-only plugin (it depends on :debugdrawer-redux, wired
// debugImplementation), so it lives in src/debug — release never references it.
// The AppInfo / Backend-switch / Logs panels are built-ins added by the drawer host
// from DebugDrawerConfig, so they need no entry here.
fun debugDrawerPlugins(): List<DebugPlugin> = listOf(ReduxDevToolsDebugPlugin())
