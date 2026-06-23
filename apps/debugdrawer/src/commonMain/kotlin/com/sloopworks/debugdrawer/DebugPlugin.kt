package com.sloopworks.debugdrawer

import androidx.compose.runtime.Composable
import com.sloopworks.debugdrawer.log.LogBuffer
import com.sloopworks.debugdrawer.persistence.DebugStore

/** A named backend the Backend-switch panel can select between. */
data class Backend(val id: String, val label: String, val url: String)

/**
 * A self-contained panel. The host lists registered plugins and navigates
 * list→detail; [Content] renders the detail body inside the host chrome.
 */
interface DebugPlugin {
  val id: String
  val title: String
  @Composable fun Content(scope: DebugScope)
}

/**
 * Services the host hands every panel. Keep panels decoupled from host internals:
 * persistence, the backend registry, the log buffer, and host actions all flow
 * through here.
 */
interface DebugScope {
  val store: DebugStore
  val backends: List<Backend>
  val logs: LogBuffer

  /** Currently-active backend id (persisted override, else the first backend). */
  fun activeBackendId(): String

  /** Stage a selection without applying it (Backend panel's "pending" state). */
  fun stageBackend(id: String)
  fun stagedBackendId(): String?

  /** Apply the staged backend + restart (real per-platform restart = Plan B). */
  fun requestRestart()

  /** Copy text to the clipboard (host-provided; toast handled by host). */
  fun copy(text: String)
}
