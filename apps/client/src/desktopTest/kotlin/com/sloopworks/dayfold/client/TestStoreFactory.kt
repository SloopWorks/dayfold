package com.sloopworks.dayfold.client

import org.reduxkotlin.Store
import org.reduxkotlin.StoreEnhancer
import org.reduxkotlin.concurrent.NotificationContext

/** Creates a synchronous store for deterministic desktop unit tests. */
internal fun createTestAppStore(
  initial: AppState = AppState(),
  debug: Boolean = true,
  extraEnhancer: StoreEnhancer<AppState>? = null,
): Store<AppState> = createAppStore(
  notificationContext = NotificationContext.Inline,
  initial = initial,
  debug = debug,
  extraEnhancer = extraEnhancer,
)
