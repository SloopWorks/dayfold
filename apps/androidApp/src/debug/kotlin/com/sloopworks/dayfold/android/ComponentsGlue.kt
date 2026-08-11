package com.sloopworks.dayfold.android

import androidx.compose.runtime.Composable
import com.sloopworks.debugdrawer.compose.inspector.ComposeUiTreeInspector
import com.sloopworks.debugdrawer.uitree.BuildSourceRef
import com.sloopworks.debugdrawer.uitree.UiTreeMode
import com.sloopworks.debugdrawer.uitree.UiTreeRegistration
import com.sloopworks.debugdrawer.uitree.registerUiTree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Debug wiring for component inspection (component reporting Gate G): ONE
 * inspector + ONE host-owned registration per process, shared by the drawer
 * Components plugin and (once swip-bugreport 0.1.2 is published) the SWIP
 * preseeded handoff. Dayfold picks SourceOnDemand for developer builds.
 * Release mirror is an inert passthrough — no provider, no registration.
 */
object ComponentsGlue {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

  val inspector: ComposeUiTreeInspector by lazy {
    ComposeUiTreeInspector(
      // BuildSourceRef is trusted build wiring, never inferred from UI data.
      // versionCode keys the future BuildSourceManifest registration; local
      // debug builds are unregistered server-side until CI registers them.
      buildRef = BuildSourceRef(
        buildId = "dayfold-android-debug-${BuildConfig.VERSION_CODE}",
        dirty = false,
      ),
    )
  }

  val registration: UiTreeRegistration by lazy {
    registerUiTree(
      mode = UiTreeMode.SourceOnDemand,
      capability = inspector,
      scope = scope,
    )
  }
}

/** Wraps ONLY product content in the inspector's recording boundary. */
@Composable
fun ComponentsRecordedContent(content: @Composable () -> Unit) {
  ComponentsGlue.inspector.RecordProductContent(content)
}
