package com.sloopworks.debugdrawer

import com.sloopworks.debugdrawer.theme.DebugDrawerTheme
import com.sloopworks.debugdrawer.theme.DebugSkins

/**
 * App-supplied build facts (the app fills these — KMP common can't read Android
 * BuildConfig). Foundation needs only [version]/[build] for the header chip; richer
 * fields are rendered by the AppInfo panel (Plan B, R10d).
 */
data class BuildInfo(
  val version: String,
  val build: String,
  val buildType: String = "debug",
  val commit: String? = null,
  val flavor: String? = null,
  val extras: Map<String, String> = emptyMap(),
)

/**
 * Drop-in configuration. The app passes this to [DebugDrawer.install]. `backends`
 * powers the Backend-switch panel; `plugins` are appended after the built-ins
 * (built-ins arrive in Plan B; `includeBuiltins` is a no-op until then).
 */
data class DebugDrawerConfig(
  val buildInfo: BuildInfo,
  val backends: List<Backend> = emptyList(),
  val plugins: List<DebugPlugin> = emptyList(),
  val theme: DebugDrawerTheme = DebugSkins.sloopworks(),
  val includeBuiltins: Boolean = true,
)
