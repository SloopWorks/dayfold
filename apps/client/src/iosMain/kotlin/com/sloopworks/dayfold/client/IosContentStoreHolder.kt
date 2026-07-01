package com.sloopworks.dayfold.client

// ADR 0044 §S3 — single-writer SQLite for iOS (parallel to AndroidContentStoreHolder). The foreground
// (MainViewController) and the headless background paths (region-enter delegate / BGTask reconcile) MUST
// share ONE driver + ContentStore in the process — two NativeSqliteDriver connections on content.db would
// race the WAL writer. This process-global holder lazily builds that single instance; both paths fetch it
// here. IosNotifGlue.start() warms it on the main thread at launch, so the later background fetch always
// sees a non-null instance (no construction race; K/N has no freezing under the new memory model).
object IosContentStoreHolder {
  @kotlin.concurrent.Volatile private var instance: ContentStore? = null

  fun get(): ContentStore =
    instance ?: ContentStore(DriverFactory().createDriver()).also { instance = it }
}
