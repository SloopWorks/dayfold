package com.sloopworks.dayfold.client

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.sloopworks.dayfold.client.db.ContentDb

// ADR 0044 §S3 — single-writer SQLite for iOS (parallel to AndroidContentStoreHolder). The foreground
// (MainViewController) and the headless background paths (region-enter delegate / BGTask reconcile) MUST
// share ONE driver + ContentStore in the process — two NativeSqliteDriver connections on content.db would
// race the WAL writer. This process-global holder lazily builds that single instance; both paths fetch it
// here. Construction is synchronized because a cold foreground launch and a background callback may
// race before IosNotifGlue.start() has warmed the holder.
object IosContentStoreHolder {
  private val holder = SynchronizedContentStoreHolder {
    ContentStore(DriverFactory().createDriver())
  }

  fun get(): ContentStore = holder.get()
}

/** Simulator fake state is disposable and must never share the production cache namespace. */
object IosDebugContentStoreHolder {
  private val holder = SynchronizedContentStoreHolder {
    ContentStore(NativeSqliteDriver(ContentDb.Schema, "content-debug-fake.db"))
  }

  fun get(): ContentStore = holder.get()
}

/**
 * Notification entry points must read the same cache selected by the foreground composition. In
 * simulator DEBUG builds that is the isolated fake cache; before foreground composition exists the
 * production holder remains the safe background-launch default.
 */
object IosNotificationContentStoreHolder {
  private val holder = SelectableContentStoreHolder(IosContentStoreHolder::get)

  fun select(store: ContentStore) = holder.select(store)
  fun get(): ContentStore = holder.get()
}

internal class SelectableContentStoreHolder(
  private val fallback: () -> ContentStore,
) {
  private val lock = SynchronizedObject()
  private var selected: ContentStore? = null

  fun select(store: ContentStore) = synchronized(lock) { selected = store }
  fun get(): ContentStore = synchronized(lock) { selected } ?: fallback()
}

/**
 * Synchronized lazy owner used by [IosContentStoreHolder].
 *
 * Keeping the factory injectable lets native tests exercise cold-start races without creating or
 * resetting the process-global production store. There is deliberately no reset API: replacing a
 * live store could leave foreground or headless callers holding a different SQLite connection.
 */
internal class SynchronizedContentStoreHolder(
  private val factory: () -> ContentStore,
) {
  private val lock = SynchronizedObject()
  private var instance: ContentStore? = null

  fun get(): ContentStore = synchronized(lock) {
    instance ?: factory().also { instance = it }
  }
}
