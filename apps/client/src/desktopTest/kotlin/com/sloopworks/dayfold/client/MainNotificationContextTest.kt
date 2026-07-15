package com.sloopworks.dayfold.client

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MainNotificationContextTest {
  @Test fun worker_dispatch_notifies_on_desktop_event_thread() {
    val store = createAppStore(notificationContext = mainNotificationContext(), debug = false)
    val notified = CountDownLatch(1)
    var notificationWasOnEventThread = false
    store.subscribe {
      notificationWasOnEventThread = SwingUtilities.isEventDispatchThread()
      notified.countDown()
    }

    val worker = thread(name = "store-worker") { store.dispatch(OpenFeed) }
    worker.join()

    assertTrue(notified.await(5, TimeUnit.SECONDS), "subscriber was not notified")
    assertTrue(notificationWasOnEventThread)
  }

  @Test fun target_thread_dispatch_notifies_inline_when_queue_is_empty() {
    val store = createAppStore(notificationContext = mainNotificationContext(), debug = false)
    var notified = false

    SwingUtilities.invokeAndWait {
      store.subscribe { notified = true }
      store.dispatch(OpenFeed)
      assertTrue(notified, "an idle target-thread dispatch should notify before dispatch returns")
    }
  }

  @Test fun target_thread_work_does_not_overtake_an_older_worker_post() {
    val context = mainNotificationContext()
    val events = CopyOnWriteArrayList<String>()
    val delivered = CountDownLatch(2)

    SwingUtilities.invokeAndWait {
      val worker = thread(name = "notification-worker") {
        context.post {
          events += "worker"
          delivered.countDown()
        }
      }
      worker.join()

      context.post {
        events += "target"
        delivered.countDown()
      }
      assertTrue(events.isEmpty(), "new target-thread work must queue behind an older post")
    }

    assertTrue(delivered.await(5, TimeUnit.SECONDS), "queued callbacks were not delivered")
    assertEquals(listOf("worker", "target"), events.toList())
  }

  @Test fun queued_store_notifications_are_serial_latest_state_signals() {
    val store = createAppStore(notificationContext = mainNotificationContext(), debug = false)
    val observedRoutes = CopyOnWriteArrayList<Route>()
    val delivered = CountDownLatch(2)
    store.subscribe {
      observedRoutes += store.state.route
      delivered.countDown()
    }

    SwingUtilities.invokeAndWait {
      val worker = thread(name = "dispatch-worker") { store.dispatch(OpenFeed) }
      worker.join()
      store.dispatch(OpenHubs())
      assertTrue(observedRoutes.isEmpty(), "both callbacks should remain behind the older worker post")
    }

    assertTrue(delivered.await(5, TimeUnit.SECONDS), "queued callbacks were not delivered")
    assertEquals(listOf(Route.Hubs, Route.Hubs), observedRoutes.toList())
  }

  @Test fun callback_failure_does_not_strand_queued_or_future_work() {
    val postedDrains = ArrayDeque<() -> Unit>()
    val delivered = mutableListOf<String>()
    val context = serialTargetThreadNotificationContext(
      isOnTargetThread = { false },
      postToTarget = { block -> postedDrains.addLast(block); true },
    )
    context.post { error("boom") }
    context.post { delivered += "already-queued" }

    assertEquals(1, postedDrains.size)
    assertFailsWith<IllegalStateException> { postedDrains.removeFirst()() }
    assertEquals(listOf("already-queued"), delivered)

    context.post { delivered += "future" }
    assertEquals(1, postedDrains.size)
    postedDrains.removeFirst()()
    assertEquals(listOf("already-queued", "future"), delivered)
  }

  @Test fun sustained_work_yields_between_bounded_fifo_batches() {
    val postedDrains = ArrayDeque<() -> Unit>()
    val delivered = mutableListOf<Int>()
    val context = serialTargetThreadNotificationContext(
      isOnTargetThread = { false },
      postToTarget = { block -> postedDrains.addLast(block); true },
      maxCallbacksPerDrain = 2,
    )
    repeat(5) { value -> context.post { delivered += value } }

    assertEquals(1, postedDrains.size)
    postedDrains.removeFirst()()
    assertEquals(listOf(0, 1), delivered)
    assertEquals(1, postedDrains.size, "remaining work should yield through a continuation")

    postedDrains.removeFirst()()
    assertEquals(listOf(0, 1, 2, 3), delivered)
    assertEquals(1, postedDrains.size)

    postedDrains.removeFirst()()
    assertEquals(listOf(0, 1, 2, 3, 4), delivered)
    assertTrue(postedDrains.isEmpty())
  }

  @Test fun concurrent_producers_never_run_more_than_one_callback_at_once() {
    val executor = Executors.newFixedThreadPool(4)
    val callbackCount = 64
    val producerCount = 7
    val callbacksPerProducer = 9
    val firstEntered = CountDownLatch(1)
    val releaseFirst = CountDownLatch(1)
    val delivered = CountDownLatch(callbackCount)
    val active = AtomicInteger(0)
    val maxActive = AtomicInteger(0)
    val context = serialTargetThreadNotificationContext(
      isOnTargetThread = { false },
      postToTarget = { block -> executor.execute(block); true },
      maxCallbacksPerDrain = 4,
    )

    fun callback(waitForRelease: Boolean = false): () -> Unit = {
      val nowActive = active.incrementAndGet()
      maxActive.updateAndGet { previous -> maxOf(previous, nowActive) }
      try {
        if (waitForRelease) {
          firstEntered.countDown()
          assertTrue(releaseFirst.await(5, TimeUnit.SECONDS), "first callback was not released")
        }
      } finally {
        active.decrementAndGet()
        delivered.countDown()
      }
    }

    try {
      context.post(callback(waitForRelease = true))
      assertTrue(firstEntered.await(5, TimeUnit.SECONDS), "first callback did not start")
      val producers = (0 until producerCount).map { index ->
        thread(name = "notification-producer-$index") {
          repeat(callbacksPerProducer) { context.post(callback()) }
        }
      }
      producers.forEach { it.join() }
      releaseFirst.countDown()

      assertTrue(delivered.await(5, TimeUnit.SECONDS), "callbacks were not delivered")
      assertEquals(1, maxActive.get())
    } finally {
      releaseFirst.countDown()
      executor.shutdownNow()
    }
  }

  @Test fun reentrant_post_runs_after_the_current_callback_without_recursion() {
    val delivered = mutableListOf<String>()
    val context = serialTargetThreadNotificationContext(
      isOnTargetThread = { true },
      postToTarget = { error("an uncontended inline drain should not be posted") },
    )

    context.post {
      delivered += "outer-start"
      context.post { delivered += "nested" }
      delivered += "outer-end"
    }

    assertEquals(listOf("outer-start", "outer-end", "nested"), delivered)
  }

  @Test fun rejected_schedule_releases_the_claim_for_a_future_retry() {
    val postedDrains = ArrayDeque<() -> Unit>()
    val delivered = mutableListOf<String>()
    var reject = true
    val context = serialTargetThreadNotificationContext(
      isOnTargetThread = { false },
      postToTarget = { block ->
        if (reject) false else { postedDrains.addLast(block); true }
      },
    )

    assertFailsWith<IllegalStateException> { context.post { delivered += "rejected" } }
    reject = false
    context.post { delivered += "retry" }
    assertEquals(1, postedDrains.size)
    postedDrains.removeFirst()()

    assertEquals(listOf("rejected", "retry"), delivered)
  }

  @Test fun throwing_scheduler_releases_the_claim_for_a_future_retry() {
    val postedDrains = ArrayDeque<() -> Unit>()
    val delivered = mutableListOf<String>()
    var throwOnPost = true
    val context = serialTargetThreadNotificationContext(
      isOnTargetThread = { false },
      postToTarget = { block ->
        if (throwOnPost) throw RejectedExecutionException("stopped")
        postedDrains.addLast(block)
        true
      },
    )

    assertFailsWith<RejectedExecutionException> { context.post { delivered += "thrown" } }
    throwOnPost = false
    context.post { delivered += "retry" }
    assertEquals(1, postedDrains.size)
    postedDrains.removeFirst()()

    assertEquals(listOf("thrown", "retry"), delivered)
  }
}
