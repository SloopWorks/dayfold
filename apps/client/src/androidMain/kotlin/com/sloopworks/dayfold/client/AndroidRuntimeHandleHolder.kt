package com.sloopworks.dayfold.client

// ADR 0020 R3 — process-wide handle to the live runtime's background-sync entry point.
// WorkManager runs the refresh worker in THIS app's own process, so if a DayfoldRuntimeGraph is
// already retained here, it — not the worker — must own the one refresh-token use for this wake.
// A second, independent refresh races the graph's own SessionCoordinator refresh and the server's
// reuse detection revokes the whole lineage, signing the user out (see the note on
// AuthClient.refresh). Delegating through this handle when one is registered is how the worker
// avoids ever being that second refresher.
//
// Mirrors AndroidContentStoreHolder's shape (a `@Volatile` process-global, not a DI graph) because
// the same constraint applies: whichever host retains the live runtime must register/clear this
// synchronously with that runtime's own lifecycle, not on a delay. A stale (registered-but-dead)
// handle is worse than none — it would make the worker believe a delegate exists when nothing is
// listening — so callers MUST clear it on close/cancel, not just on success.
object AndroidRuntimeHandleHolder {
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
