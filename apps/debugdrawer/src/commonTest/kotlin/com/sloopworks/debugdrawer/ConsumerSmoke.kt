package com.sloopworks.debugdrawer

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.sloopworks.debugdrawer.log.DebugLog
import com.sloopworks.debugdrawer.log.LogLevel
import com.sloopworks.debugdrawer.persistence.DebugKeys
import com.sloopworks.debugdrawer.theme.DebugDrawerTheme

// R12 PARITY GUARD. This file is byte-identical in :debugdrawer and :debugdrawer-noop
// commonTest. It exercises the entire consumer-facing surface; if it compiles against
// BOTH modules, the artifacts are API-swappable (the actual release-stripping proof).
// Compile-only — nothing here is invoked.

private class SampleConsumerPlugin : DebugPlugin {
  override val id: String = "sample"
  override val title: String = "Sample"
  @Composable override fun Content(scope: DebugScope) {
    scope.logs.record(LogLevel.I, "smoke", "hello", 0L)
    scope.copy(scope.activeBackendId())
    scope.stageBackend("x")
    scope.stagedBackendId()
    scope.requestRestart()
    scope.store.get(DebugKeys.BACKEND_OVERRIDE)
    scope.backends.firstOrNull()
  }
}

@Composable
private fun sampleHost() {
  val cfg = DebugDrawerConfig(
    buildInfo = BuildInfo(version = "1.0", build = "100", buildType = "debug", commit = "abc", flavor = "free"),
    backends = listOf(Backend("prod", "Prod", "https://p"), Backend("local", "Local", "http://l")),
    plugins = listOf(SampleConsumerPlugin()),
    theme = DebugDrawerTheme(brandName = "Dayfold", accentLight = Color(0xFFC75C3C), accentDark = Color(0xFFE89070)),
    includeBuiltins = true,
  )
  DebugDrawer.install(cfg, context = null)
  DebugLog.i("smoke", "app started")
  DebugDrawerHost { /* app content */ }
}

private fun sampleSeam(default: String): String {
  val url = DebugDrawer.backendUrl(default)
  DebugDrawer.selectedBackendId()
  return url
}
