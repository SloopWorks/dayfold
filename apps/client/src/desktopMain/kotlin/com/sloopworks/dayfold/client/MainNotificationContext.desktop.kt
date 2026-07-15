package com.sloopworks.dayfold.client

import org.reduxkotlin.concurrent.NotificationContext
import javax.swing.SwingUtilities

/** Desktop UI-thread notification delivery backed by the Swing event queue. */
actual fun mainNotificationContext(): NotificationContext =
  serialTargetThreadNotificationContext(
    isOnTargetThread = SwingUtilities::isEventDispatchThread,
    postToTarget = { block ->
      SwingUtilities.invokeLater(block)
      true
    },
  )
