package com.sloopworks.dayfold.client

import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Owns desktop shutdown independently of the Compose window lifecycle.
 *
 * The first close request synchronously closes runtime publication, then waits off the Swing event
 * thread for ordered resource teardown. Repeated window callbacks are ignored. Application exit is
 * always posted back to Swing after teardown has completed (or failed).
 */
internal class DesktopShutdownOwner(
  private val cancelRuntime: () -> Unit,
  private val awaitRuntimeClosed: suspend () -> Unit,
  private val exitApplication: () -> Unit,
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
  private val postToEventThread: ((() -> Unit) -> Unit) = ::postToSwingEventThread,
  private val onCloseFailure: (Throwable) -> Unit = Throwable::printStackTrace,
) {
  private val closeRequested = AtomicBoolean(false)

  /** Starts the one-way shutdown sequence without blocking the window callback. */
  fun requestClose() {
    if (!closeRequested.compareAndSet(false, true)) return

    val cancellationFailure = runCatching(cancelRuntime).exceptionOrNull()
    scope.launch {
      val closeFailure = runCatching { awaitRuntimeClosed() }.exceptionOrNull()
      try {
        (cancellationFailure ?: closeFailure)?.let(onCloseFailure)
      } finally {
        postToEventThread(exitApplication)
      }
    }
  }
}

private fun postToSwingEventThread(block: () -> Unit) {
  if (SwingUtilities.isEventDispatchThread()) {
    block()
  } else {
    SwingUtilities.invokeLater(block)
  }
}
