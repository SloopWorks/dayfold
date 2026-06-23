package com.sloopworks.debugdrawer.panels

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.sloopworks.debugdrawer.BuildInfo
import com.sloopworks.debugdrawer.DebugPlugin
import com.sloopworks.debugdrawer.DebugScope
import com.sloopworks.debugdrawer.host.KeyValueRow
import com.sloopworks.debugdrawer.theme.LocalDebugDrawerColors

/** Device/OS facts the panel shows alongside the app's [BuildInfo]. */
data class PlatformInfo(val os: String, val osVersion: String, val device: String, val locale: String)

internal expect fun platformInfo(): PlatformInfo

/**
 * Pure projection of [BuildInfo] + [PlatformInfo] → ordered (label, value) rows.
 * Extracted so the panel's content is unit-testable without a Compose host.
 */
fun appInfoRows(build: BuildInfo, platform: PlatformInfo): List<Pair<String, String>> = buildList {
  add("Version" to build.version)
  add("Build" to build.build)
  add("Build type" to build.buildType)
  build.commit?.let { add("Commit" to it) }
  build.flavor?.let { add("Flavor" to it) }
  add("OS" to "${platform.os} ${platform.osVersion}".trim())
  add("Device" to platform.device)
  add("Locale" to platform.locale)
  build.extras.forEach { (k, v) -> add(k to v) }
}

/** Built-in C5 panel: read-only app/build/device facts; every value is tap-to-copy. */
class AppInfoPlugin(
  private val build: BuildInfo,
  private val platform: PlatformInfo = platformInfo(),
) : DebugPlugin {
  override val id: String = "appinfo"
  override val title: String = "App Info"

  @Composable
  override fun Content(scope: DebugScope) {
    val colors = LocalDebugDrawerColors.current
    Column(Modifier.verticalScroll(rememberScrollState())) {
      appInfoRows(build, platform).forEach { (label, value) ->
        KeyValueRow(label = label, value = value, colors = colors, onCopy = { scope.copy(it) })
      }
    }
  }
}
