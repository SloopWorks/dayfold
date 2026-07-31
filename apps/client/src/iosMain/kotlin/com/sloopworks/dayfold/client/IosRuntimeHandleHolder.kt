package com.sloopworks.dayfold.client

import kotlin.concurrent.Volatile // multiplatform @Volatile (bare resolves to kotlin.jvm → fails on K/Native)

// ADR 0020 R3 — process-wide handle to the live runtime's background-sync entry point.
// BGTaskScheduler runs the refresh task in THIS app's own process, so if a DayfoldRuntimeGraph is
// already retained here (MainViewController's composition is live), it — not the BGTask — must own
// the one refresh-token use for this wake. A second, independent refresh races the graph's own
// SessionCoordinator refresh and the server's reuse detection revokes the whole lineage, signing the
// user out (see the note on AuthClient.refresh). Delegating through this handle when one is
// registered is how bgRefresh avoids ever being that second refresher.
//
// Mirrors AndroidRuntimeHandleHolder's shape (a `@Volatile` process-global, not a DI graph) because
// the same constraint applies: whichever host retains the live runtime must register/clear this
// synchronously with that runtime's own lifecycle, not on a delay. A stale (registered-but-dead)
// handle is worse than none — it would make bgRefresh believe a delegate exists when nothing is
// listening — so callers MUST clear it on close/cancel, not just on success.
object IosRuntimeHandleHolder {
  @Volatile private var delegate: (suspend () -> Unit)? = null

  /** Registers the live runtime's background-sync entry point. Overwrites any prior registration. */
  fun register(requestBackgroundSync: suspend () -> Unit) {
    delegate = requestBackgroundSync
  }

  /** Clears the registration. Safe to call even when nothing is registered. */
  fun clear() {
    delegate = null
  }

  /** The live runtime's background-sync entry point for this process, or null if none is retained. */
  fun get(): (suspend () -> Unit)? = delegate
}
