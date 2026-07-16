package com.sloopworks.dayfold.client

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
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
      observedRoutes += store.state.navigation.route
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

}
