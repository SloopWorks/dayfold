package com.sloopworks.debugdrawer

import com.sloopworks.debugdrawer.persistence.DebugKeys
import com.sloopworks.debugdrawer.persistence.DebugStore
import com.sloopworks.debugdrawer.persistence.createDebugStore
import kotlin.concurrent.Volatile

/** Platform init hook (R3) — Android stashes the Context for [DebugStore]. */
internal expect fun initPlatform(context: Any?)

/** Bound config + store + the cached backend override (R1/R2). */
internal class Installed(val config: DebugDrawerConfig, val store: DebugStore) {
  // Cached so the client hot path never hits disk; @Volatile = safe cross-thread
  // publication to the (possibly background) thread that builds HTTP clients.
  @Volatile var override: String? = null
}

/**
 * Top-level entry/seam. The app calls [install] ONCE at startup (before any HTTP
 * client is built) — decoupled from Compose (R1) — then reads its base URL through
 * [backendUrl]. Until installed, [backendUrl] returns the default and the host is a
 * passthrough (matches the no-op artifact's behavior on release).
 */
object DebugDrawer {
  @Volatile private var installed: Installed? = null

  /** Eager registration. `context` is the Android Context (ignored elsewhere, R3). */
  fun install(config: DebugDrawerConfig, context: Any? = null) {
    initPlatform(context)
    val store = createDebugStore()
    val seeded = store.get(DebugKeys.BACKEND_OVERRIDE)?.takeIf { id -> config.backends.any { it.id == id } }
    installed = Installed(config, store).also { it.override = seeded }
  }

  /** Resolve the active backend URL from the cached override, else [default] (R2). */
  fun backendUrl(default: String): String {
    val inst = installed ?: return default
    val id = inst.override ?: return default
    return inst.config.backends.firstOrNull { it.id == id }?.url ?: default
  }

  fun selectedBackendId(): String? = installed?.override

  // ── internal: the host/Backend panel apply path ─────────────────────────────
  internal fun current(): Installed? = installed

  internal fun setOverride(id: String?) {
    val inst = installed ?: return
    inst.override = id
    if (id == null) inst.store.remove(DebugKeys.BACKEND_OVERRIDE)
    else inst.store.put(DebugKeys.BACKEND_OVERRIDE, id)
  }
}
