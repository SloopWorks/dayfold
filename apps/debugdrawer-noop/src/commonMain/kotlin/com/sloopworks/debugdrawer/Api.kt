package com.sloopworks.debugdrawer

import androidx.compose.runtime.Composable
import com.sloopworks.debugdrawer.log.LogBuffer
import com.sloopworks.debugdrawer.persistence.DebugStore
import com.sloopworks.debugdrawer.theme.DebugDrawerTheme
import com.sloopworks.debugdrawer.theme.DebugSkins

// No-op mirror of the consumer-facing API (R12). Every public symbol a consuming app
// can reference exists here with the IDENTICAL signature and an inert body, so the
// app compiles unchanged against either artifact. The host is a pure passthrough.

data class Backend(val id: String, val label: String, val url: String)

interface DebugPlugin {
  val id: String
  val title: String
  @Composable fun Content(scope: DebugScope)
}

interface DebugScope {
  val store: DebugStore
  val backends: List<Backend>
  val logs: LogBuffer
  fun activeBackendId(): String
  fun stageBackend(id: String)
  fun stagedBackendId(): String?
  fun requestRestart()
  fun copy(text: String)
}

data class BuildInfo(
  val version: String,
  val build: String,
  val buildType: String = "debug",
  val commit: String? = null,
  val flavor: String? = null,
  val extras: Map<String, String> = emptyMap(),
)

data class DebugDrawerConfig(
  val buildInfo: BuildInfo,
  val backends: List<Backend> = emptyList(),
  val plugins: List<DebugPlugin> = emptyList(),
  val theme: DebugDrawerTheme = DebugSkins.sloopworks(),
  val includeBuiltins: Boolean = true,
)

object DebugDrawer {
  fun install(config: DebugDrawerConfig, context: Any? = null) { /* no-op */ }
  fun backendUrl(default: String): String = default
  fun selectedBackendId(): String? = null
}

/** Release passthrough — renders the app, no bubble, no drawer, zero overhead. */
@Composable
fun DebugDrawerHost(content: @Composable () -> Unit) {
  content()
}
