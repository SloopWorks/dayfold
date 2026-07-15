package com.sloopworks.dayfold.android

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sloopworks.dayfold.client.AndroidContentStoreHolder
import com.sloopworks.dayfold.client.OpenFeed
import com.sloopworks.dayfold.client.OpenHubs
import com.sloopworks.dayfold.client.createAppStore
import com.sloopworks.dayfold.client.mainNotificationContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class MainNotificationContextAndroidTest {
  @Test fun worker_dispatch_reaches_subscribers_on_main_looper_in_fifo_order() {
    val notificationContext = mainNotificationContext()
    val store = createAppStore(notificationContext = notificationContext, debug = false)
    val delivered = CountDownLatch(3)
    val sequence = AtomicInteger()
    val callbacks = CopyOnWriteArrayList<Int>()
    val allOnMain = AtomicBoolean(true)
    val fifoCallbacks = CopyOnWriteArrayList<Int>()
    val fifoDelivered = CountDownLatch(3)
    val unsubscribe = store.subscribe {
      allOnMain.compareAndSet(true, Looper.myLooper() == Looper.getMainLooper())
      callbacks += sequence.incrementAndGet()
      delivered.countDown()
    }
    val worker = Executors.newSingleThreadExecutor()
    try {
      worker.submit {
        store.dispatch(OpenFeed)
        store.dispatch(OpenHubs())
        store.dispatch(OpenFeed)
        repeat(3) { value ->
          notificationContext.post {
            fifoCallbacks += value
            fifoDelivered.countDown()
          }
        }
      }.get(5, TimeUnit.SECONDS)

      assertTrue("subscriber callbacks were not delivered", delivered.await(5, TimeUnit.SECONDS))
      assertTrue("FIFO callbacks were not delivered", fifoDelivered.await(5, TimeUnit.SECONDS))
      assertTrue("every subscriber callback must run on the main Looper", allOnMain.get())
      assertEquals(listOf(1, 2, 3), callbacks.toList())
      assertEquals(listOf(0, 1, 2), fifoCallbacks.toList())
    } finally {
      unsubscribe()
      worker.shutdownNow()
    }
  }

  @Test fun headless_and_foreground_paths_share_the_process_content_store() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val foreground = AndroidContentStoreHolder.get(context)
    val headless = AndroidContentStoreHolder.get(context.applicationContext)

    assertSame(foreground, headless)
  }
}
