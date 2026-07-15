package com.sloopworks.dayfold.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import platform.Foundation.NSDate
import platform.Foundation.NSRunLoop
import platform.Foundation.NSThread
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import platform.Foundation.timeIntervalSinceNow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MainNotificationContextIosTest {
  @Test fun worker_then_main_posts_are_delivered_on_main_in_fifo_order() {
    assertTrue(NSThread.isMainThread(), "the native test must drive the UIKit main run loop")
    val context = mainNotificationContext()
    val events = mutableListOf<String>()
    val callbackThreads = mutableListOf<Boolean>()

    // Keep the main run loop occupied until the worker has claimed the context's drain. The main
    // post must then queue behind that older worker post rather than taking the inline fast path.
    runBlocking {
      withContext(Dispatchers.Default) {
        context.post {
          callbackThreads += NSThread.isMainThread()
          events += "worker"
        }
      }
    }
    context.post {
      callbackThreads += NSThread.isMainThread()
      events += "main"
    }
    assertTrue(events.isEmpty(), "the newer main post must not overtake the queued worker post")

    val timeout = NSDate.dateWithTimeIntervalSinceNow(TIMEOUT_SECONDS)
    while (events.size < 2 && timeout.timeIntervalSinceNow > 0.0) {
      NSRunLoop.mainRunLoop.runUntilDate(
        NSDate.dateWithTimeIntervalSinceNow(RUN_LOOP_SLICE_SECONDS),
      )
    }

    assertEquals(listOf("worker", "main"), events)
    assertEquals(listOf(true, true), callbackThreads)
  }

  private companion object {
    const val TIMEOUT_SECONDS = 5.0
    const val RUN_LOOP_SLICE_SECONDS = 0.01
  }
}
