package com.sloopworks.dayfold.client

import org.reduxkotlin.concurrent.NotificationContext

/**
 * Builds the platform notification context used by production stores.
 *
 * Subscriber callbacks run on the platform UI thread. The platform actuals
 * provide their schedulers to redux-kotlin's FIFO, coalescing notification
 * context; a target-thread dispatch runs inline only when no older callback is
 * pending.
 */
expect fun mainNotificationContext(): NotificationContext
