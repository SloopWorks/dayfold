package com.sloopworks.dayfold.client

import org.reduxkotlin.concurrent.NotificationContext
import org.reduxkotlin.concurrent.coalescingNotificationContext
import javax.swing.SwingUtilities

/** Desktop UI-thread notification delivery backed by the Swing event queue. */
actual fun mainNotificationContext(): NotificationContext =
  coalescingNotificationContext(
    isOnTargetThread = SwingUtilities::isEventDispatchThread,
    post = { block ->
      SwingUtilities.invokeLater(block)
      true
    },
  )
