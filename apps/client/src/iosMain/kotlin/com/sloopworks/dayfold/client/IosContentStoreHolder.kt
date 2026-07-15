package com.sloopworks.dayfold.client

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

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
