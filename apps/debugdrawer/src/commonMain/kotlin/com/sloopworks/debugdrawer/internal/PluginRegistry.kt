package com.sloopworks.debugdrawer.internal

import com.sloopworks.debugdrawer.Backend
import com.sloopworks.debugdrawer.DebugPlugin
import com.sloopworks.debugdrawer.DebugScope
import com.sloopworks.debugdrawer.log.LogBuffer
import com.sloopworks.debugdrawer.log.LogLevel
import com.sloopworks.debugdrawer.persistence.DebugKeys
import com.sloopworks.debugdrawer.persistence.DebugStore

/** Holds registered plugins in insertion order. */
internal class PluginRegistry(initial: List<DebugPlugin> = emptyList()) {
  private val items: List<DebugPlugin> = initial.toList()
  val plugins: List<DebugPlugin> get() = items
  fun find(id: String): DebugPlugin? = items.firstOrNull { it.id == id }
}

/**
 * Default [DebugScope]. `onRestart`/`onCopy` are injected by the host (per-platform);
 * when no restart is wired (foundation), [requestRestart] is observably inert — it
 * logs a warning rather than dying silently (R9).
 *
 * `activeBackendId` reads [store] (low-frequency: only while a panel is visible). The
 * client-facing hot path (`DebugDrawer.backendUrl`) is cached separately (R2/P-1).
 */
internal class DebugScopeImpl(
  override val store: DebugStore,
  override val backends: List<Backend>,
  override val logs: LogBuffer,
  private val onCopy: (String) -> Unit = {},
  private val onRestart: (() -> Unit)? = null,
  private val now: () -> Long = { 0L },
) : DebugScope {

  private var staged: String? = null

  override fun activeBackendId(): String =
    store.get(DebugKeys.BACKEND_OVERRIDE)?.takeIf { id -> backends.any { it.id == id } }
      ?: backends.firstOrNull()?.id.orEmpty()

  override fun stageBackend(id: String) { staged = id }
  override fun stagedBackendId(): String? = staged

  override fun requestRestart() {
    val r = onRestart
    if (r != null) r() else logs.record(LogLevel.W, "DebugDrawer", "Restart unavailable in this build", now())
  }

  override fun copy(text: String) = onCopy(text)
}
