package com.sloopworks.dayfold.client

import android.content.Context

// ADR 0044 §S3 — single-writer SQLite. The foreground (MainActivity) and the headless background path
// (geofence receiver / exact-alarm receiver / worker) MUST share ONE driver + ContentStore in the
// process — two connections would race the WAL writer. This process-global holder lazily builds that
// single instance from the application context; both paths fetch it here.
object AndroidContentStoreHolder {
  @Volatile private var instance: ContentStore? = null

  fun get(context: Context): ContentStore =
    instance ?: synchronized(this) {
      instance ?: ContentStore(DriverFactory(context.applicationContext).createDriver()).also { instance = it }
    }
}
