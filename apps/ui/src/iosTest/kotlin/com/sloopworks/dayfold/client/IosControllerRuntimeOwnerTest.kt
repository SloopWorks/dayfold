package com.sloopworks.dayfold.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class IosControllerRuntimeOwnerTest {
  @Test fun active_initial_state_starts_then_resumes_without_waiting_for_a_notification() =
    runBlocking<Unit> {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      val resumed = CompletableDeferred<Unit>()
      val events = mutableListOf<String>()
      var cancelCalls = 0
      val owner = IosControllerRuntimeOwner(
        scope = scope,
        startRuntime = { events += "start" },
        resumeRuntime = {
          events += "resume"
          resumed.complete(Unit)
        },
        pauseRuntime = { events += "pause" },
        cancelRuntime = {
          events += "cancel"
          cancelCalls += 1
        },
      )

      try {
        owner.start(isActive = true)
        withTimeout(TIMEOUT_MS) { resumed.await() }

        assertEquals(listOf("start", "resume"), events)
      } finally {
        owner.dispose()
        scope.cancel()
      }
      assertEquals(1, cancelCalls)
    }

  @Test fun activation_during_blocked_cold_start_is_not_lost_and_events_remain_fifo() =
    runBlocking<Unit> {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      val startEntered = CompletableDeferred<Unit>()
      val releaseStart = CompletableDeferred<Unit>()
      val paused = CompletableDeferred<Unit>()
      val events = mutableListOf<String>()
      val owner = IosControllerRuntimeOwner(
        scope = scope,
        startRuntime = {
          events += "start"
          startEntered.complete(Unit)
          releaseStart.await()
        },
        resumeRuntime = { events += "resume" },
        pauseRuntime = {
          events += "pause"
          paused.complete(Unit)
        },
        cancelRuntime = { events += "cancel" },
      )

      try {
        owner.start(isActive = false)
        withTimeout(TIMEOUT_MS) { startEntered.await() }
        owner.didBecomeActive()
        owner.willResignActive()
        releaseStart.complete(Unit)
        withTimeout(TIMEOUT_MS) { paused.await() }

        assertEquals(listOf("start", "resume", "pause"), events)
      } finally {
        owner.dispose()
        scope.cancel()
      }
    }

  @Test fun dispose_cancels_blocked_lifecycle_work_and_closes_runtime_without_awaiting() =
    runBlocking<Unit> {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      val startEntered = CompletableDeferred<Unit>()
      val startCancelled = CompletableDeferred<Unit>()
      val events = mutableListOf<String>()
      val owner = IosControllerRuntimeOwner(
        scope = scope,
        startRuntime = {
          events += "start"
          startEntered.complete(Unit)
          try {
            awaitCancellation()
          } finally {
            startCancelled.complete(Unit)
          }
        },
        resumeRuntime = { events += "resume" },
        pauseRuntime = { events += "pause" },
        cancelRuntime = { events += "cancel" },
      )

      owner.start(isActive = true)
      withTimeout(TIMEOUT_MS) { startEntered.await() }
      owner.dispose()
      withTimeout(TIMEOUT_MS) { startCancelled.await() }
      scope.cancel()

      assertEquals(listOf("start", "cancel"), events)
    }

  private companion object {
    const val TIMEOUT_MS = 5_000L
  }
}
