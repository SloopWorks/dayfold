package com.sloopworks.dayfold.client

import org.reduxkotlin.concurrent.NotificationContext
import platform.Foundation.NSThread
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/** iOS UI-thread notification delivery backed by the main dispatch queue. */
actual fun mainNotificationContext(): NotificationContext =
  serialTargetThreadNotificationContext(
    isOnTargetThread = { NSThread.isMainThread() },
    postToTarget = { block ->
      dispatch_async(dispatch_get_main_queue()) { block() }
      true
    },
  )
