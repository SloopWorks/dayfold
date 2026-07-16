package com.sloopworks.dayfold.client

import android.os.Handler
import android.os.Looper
import org.reduxkotlin.concurrent.NotificationContext
import org.reduxkotlin.concurrent.coalescingNotificationContext

/** Android UI-thread notification delivery backed by the main [Looper]. */
actual fun mainNotificationContext(): NotificationContext {
  val mainLooper = Looper.getMainLooper()
  val handler = Handler(mainLooper)
  return coalescingNotificationContext(
    isOnTargetThread = { Looper.myLooper() == mainLooper },
    post = { block ->
      check(handler.post(block)) { "Android main looper rejected notification delivery" }
    },
  )
}
