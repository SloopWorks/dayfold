package com.sloopworks.dayfold.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Serializes UIKit lifecycle signals for one controller-owned runtime graph.
 *
 * The owner retains only suspend operations and its own processor job; native controller objects
 * stay in [MainViewController]. [dispose] closes runtime publication synchronously and never waits
 * for structured teardown on the composition scope.
 */
internal class IosControllerRuntimeOwner(
  scope: CoroutineScope,
  private val startRuntime: suspend () -> Unit,
  private val resumeRuntime: suspend () -> Unit,
  private val pauseRuntime: suspend () -> Unit,
  private val cancelRuntime: () -> Unit,
) {
  private val events = Channel<Event>(capacity = Channel.UNLIMITED)
  private val processor: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
    var started = false
    for (event in events) {
      when (event) {
        is Event.Start -> {
          if (!started) {
            startRuntime()
            started = true
          }
          if (event.isActive) resumeRuntime()
        }
        Event.DidBecomeActive -> resumeRuntime()
        Event.WillResignActive -> pauseRuntime()
      }
    }
  }

  fun start(isActive: Boolean) {
    events.trySend(Event.Start(isActive))
  }

  fun didBecomeActive() {
    events.trySend(Event.DidBecomeActive)
  }

  fun willResignActive() {
    events.trySend(Event.WillResignActive)
  }

  fun dispose() {
    // Close publication before cancelling the queued lifecycle processor. Runtime cancellation is
    // deliberately non-suspending; its ordered resource join belongs to runtime-owned teardown.
    cancelRuntime()
    events.close()
    processor.cancel()
  }

  private sealed interface Event {
    data class Start(val isActive: Boolean) : Event
    data object DidBecomeActive : Event
    data object WillResignActive : Event
  }
}
