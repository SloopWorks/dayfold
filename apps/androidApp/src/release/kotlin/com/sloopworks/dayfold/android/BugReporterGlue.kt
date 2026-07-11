package com.sloopworks.dayfold.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import com.sloopworks.dayfold.client.AppState
import org.reduxkotlin.StoreEnhancer

// Release variant: inert mirror of src/debug's BugReporterGlue (same signatures, the
// debugDrawerPlugins() idiom). No swip imports, no swip bytes on the release
// classpath — the reporter exists only in debug builds.
fun bugReporterEnhancer(): StoreEnhancer<AppState>? = null

@Suppress("UNUSED_PARAMETER")
fun bugReporterInstall(activity: ComponentActivity) = Unit

@Composable
fun BugReporterWrapped(content: @Composable () -> Unit) = content()
