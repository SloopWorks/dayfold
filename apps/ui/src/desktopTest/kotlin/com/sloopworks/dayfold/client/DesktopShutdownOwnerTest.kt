package com.sloopworks.dayfold.client

import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class DesktopShutdownOwnerTest {
  @Test fun repeated_requests_cancel_await_close_resources_and_exit_once() = runBlocking<Unit> {
    val closeStarted = CompletableDeferred<Unit>()
    val allowResourcesToClose = CompletableDeferred<Unit>()
    val exitPosted = CompletableDeferred<() -> Unit>()
    val cancelCount = AtomicInteger()
    val resourceCloseCount = AtomicInteger()
    val exitCount = AtomicInteger()
    val events = mutableListOf<String>()
    var onEventThread = false

    val owner = DesktopShutdownOwner(
      cancelRuntime = {
        cancelCount.incrementAndGet()
        events += "cancel"
      },
      awaitRuntimeClosed = {
        events += "await"
        closeStarted.complete(Unit)
        allowResourcesToClose.await()
        resourceCloseCount.incrementAndGet()
        events += "resources"
      },
      exitApplication = {
        assertTrue(onEventThread)
        exitCount.incrementAndGet()
        events += "exit"
      },
      scope = this,
      postToEventThread = { block -> exitPosted.complete(block) },
    )

    owner.requestClose()
    owner.requestClose()
    closeStarted.await()

    assertEquals(1, cancelCount.get())
    assertEquals(0, resourceCloseCount.get())
    assertEquals(0, exitCount.get())
    assertFalse(exitPosted.isCompleted, "exit must wait for runtime resource teardown")

    allowResourcesToClose.complete(Unit)
    val exit = exitPosted.await()
    assertEquals(1, resourceCloseCount.get())
    assertEquals(listOf("cancel", "await", "resources"), events)

    onEventThread = true
    exit()
    owner.requestClose()

    assertEquals(1, cancelCount.get())
    assertEquals(1, resourceCloseCount.get())
    assertEquals(1, exitCount.get())
    assertEquals(listOf("cancel", "await", "resources", "exit"), events)
  }

  @Test fun real_exit_dispatch_runs_on_swing_event_thread() = runBlocking<Unit> {
    val exitedOnEventThread = CompletableDeferred<Boolean>()
    val owner = DesktopShutdownOwner(
      cancelRuntime = {},
      awaitRuntimeClosed = {},
      exitApplication = {
        exitedOnEventThread.complete(SwingUtilities.isEventDispatchThread())
      },
      scope = this,
    )

    thread(name = "desktop-close-request") { owner.requestClose() }.join()

    assertTrue(withTimeout(5_000L) { exitedOnEventThread.await() })
  }

  @Test fun close_failure_is_reported_before_exit_is_posted() = runBlocking<Unit> {
    val failure = IllegalStateException("driver close failed")
    val reported = mutableListOf<Throwable>()
    val exitPosted = CompletableDeferred<Unit>()
    val owner = DesktopShutdownOwner(
      cancelRuntime = {},
      awaitRuntimeClosed = { throw failure },
      exitApplication = {},
      scope = this,
      postToEventThread = { exitPosted.complete(Unit) },
      onCloseFailure = reported::add,
    )

    owner.requestClose()
    exitPosted.await()

    assertEquals(listOf<Throwable>(failure), reported)
  }
}
