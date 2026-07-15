package com.sloopworks.dayfold.client

import android.os.Handler
import android.os.Looper
import org.reduxkotlin.concurrent.NotificationContext

/** Android UI-thread notification delivery backed by the main [Looper]. */
actual fun mainNotificationContext(): NotificationContext {
  val mainLooper = Looper.getMainLooper()
  val handler = Handler(mainLooper)
  return serialTargetThreadNotificationContext(
    isOnTargetThread = { Looper.myLooper() == mainLooper },
    postToTarget = { block -> handler.post(block) },
  )
}
