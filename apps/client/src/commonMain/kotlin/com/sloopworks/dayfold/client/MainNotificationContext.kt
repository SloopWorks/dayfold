package com.sloopworks.dayfold.client

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import org.reduxkotlin.concurrent.NotificationContext

/**
 * Builds the platform notification context used by production stores.
 *
 * Subscriber callbacks run on the platform UI thread. Dispatches already on
 * that thread notify inline when no older notification is pending; otherwise
 * they join the existing FIFO queue. This preserves dispatch order when a
 * background dispatch is followed by a UI-thread dispatch before the posted
 * background notification runs.
 */
expect fun mainNotificationContext(): NotificationContext

/**
 * Builds a serial target-thread context without allowing an inline callback to
 * overtake work that was already posted from another thread.
 */
internal fun serialTargetThreadNotificationContext(
  isOnTargetThread: () -> Boolean,
  postToTarget: (block: () -> Unit) -> Boolean,
  maxCallbacksPerDrain: Int = 64,
): NotificationContext {
  require(maxCallbacksPerDrain > 0) { "maxCallbacksPerDrain must be positive" }
  val lock = SynchronizedObject()
  val queue = ArrayDeque<() -> Unit>()
  var draining = false

  fun releaseDrainClaim() {
    synchronized(lock) { draining = false }
  }

  fun scheduleDrain(block: () -> Unit) {
    val accepted = try {
      postToTarget(block)
    } catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
      releaseDrainClaim()
      throw throwable
    }
    if (!accepted) {
      releaseDrainClaim()
      error("Target thread rejected notification delivery")
    }
  }

  fun drain() {
    var failure: Throwable? = null
    var delivered = 0
    while (delivered < maxCallbacksPerDrain) {
      val next = synchronized(lock) { queue.removeFirstOrNull() } ?: break
      try {
        next()
      } catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
        if (failure == null) failure = throwable
      }
      delivered++
    }

    // Yield between bounded batches so a sustained dispatch stream cannot monopolize the
    // platform event loop. Keep the claim while posting the continuation: producers append
    // behind it but cannot start a second drainer.
    val continueDraining = synchronized(lock) {
      if (queue.isEmpty()) {
        draining = false
        false
      } else {
        true
      }
    }
    if (continueDraining) scheduleDrain(::drain)
    failure?.let { throw it }
  }

  return NotificationContext { block ->
    val startDrain = synchronized(lock) {
      queue.addLast(block)
      if (draining) {
        false
      } else {
        draining = true
        true
      }
    }

    if (startDrain) {
      if (isOnTargetThread()) drain() else scheduleDrain(::drain)
    }
  }
}
