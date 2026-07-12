package com.sloopworks.dayfold.android

import androidx.activity.ComponentActivity
import com.sloopworks.debugdrawer.DebugPlugin

// Release variant: no extra plugins (DebugDrawer is the no-op facade in release; the
// host is a pure passthrough). Kept symmetric with src/debug so MainActivity in
// src/main can call debugDrawerPlugins(activity) in either variant.
fun debugDrawerPlugins(activity: ComponentActivity): List<DebugPlugin> = emptyList()
