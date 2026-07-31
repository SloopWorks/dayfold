package com.sloopworks.dayfold.client

import kotlin.concurrent.Volatile // multiplatform @Volatile (bare resolves to kotlin.jvm → fails on K/Native)
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

// ADR 0020 R3 — process-wide handle to the live runtime's background-sync entry point. ONE holder for
// both platforms: WorkManager and BGTaskScheduler each run their refresh in THIS app's own process, so
// if a DayfoldRuntimeGraph is already retained here (the Android ViewModel, or MainViewController's
// composition) it — not the headless wake — must own the one refresh-token use for that wake. A second,
// independent refresh races the graph's own SessionCoordinator refresh and the server's reuse detection
// revokes the whole lineage, signing the user out (see the note on AuthClient.refresh). Delegating
// through this handle when one is registered is how a headless pass avoids ever being that second
// refresher.
//
// A `@Volatile` process-global rather than a DI graph, mirroring AndroidContentStoreHolder, because the
// same constraint applies: whichever host retains the live runtime must register/clear this
// synchronously with that runtime's own lifecycle, not on a delay. A stale (registered-but-dead) handle
// is worse than none — it would make the wake believe a delegate exists when nothing is listening — so
// callers MUST clear on close/cancel, not just on success.
object RuntimeHandleHolder {
  private val gate = SynchronizedObject()
  @Volatile private var delegate: (suspend () -> Boolean)? = null

  /**
   * Registers the live runtime's background-sync entry point, replacing any prior registration.
   * Returns the registration token the owner must hand back to [clear] at teardown.
   */
  fun register(requestBackgroundSync: suspend () -> Boolean): suspend () -> Boolean {
    synchronized(gate) { delegate = requestBackgroundSync }
    return requestBackgroundSync
  }

  /**
   * Compare-and-clear: drops [registration] only if it is still the live one. Registration and
   * teardown are far apart in both hosts (iOS registers in `MainViewController()` and clears in the
   * composition's `onDispose`), so a replacement controller/ViewModel can register BEFORE the outgoing
   * one disposes. An unconditional clear would then wipe the live NEW registration, and the next
   * headless wake, finding no delegate, would sync independently — the refresh-token reuse-detection
   * sign-out this whole scheme exists to prevent. Safe to call with a stale token or with nothing
   * registered: both are no-ops.
   */
  fun clear(registration: (suspend () -> Boolean)?) {
    synchronized(gate) { if (delegate === registration) delegate = null }
  }

  /** The live runtime's background-sync entry point for this process, or null if none is retained. */
  fun get(): (suspend () -> Boolean)? = delegate
}
