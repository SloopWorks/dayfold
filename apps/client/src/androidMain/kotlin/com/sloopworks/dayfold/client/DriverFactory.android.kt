package com.sloopworks.dayfold.client

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.sloopworks.dayfold.client.db.ContentDb

actual class DriverFactory(private val context: Context) {
  actual fun createDriver(): SqlDriver {
    // Guard against a downgrade crash when an older build runs over a newer on-disk
    // cache (SQLiteException: Can't downgrade …). content.db is disposable — if its
    // schema version is newer than this build, drop it + let it re-create + re-sync.
    val dbFile = context.getDatabasePath("content.db")
    if (dbFile.exists()) {
      val onDisk = runCatching {
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
          .use { it.version.toLong() }
      }.getOrNull()
      if (cacheNeedsWipe(onDisk, ContentDb.Schema.version)) context.deleteDatabase("content.db")
    }
    // WAL — single-writer / many-readers, so the headless background pass (geofence wake / scheduled
    // alarm) can read the SAME process-shared cache while the foreground holds a write (ADR 0044 §S3
    // single-writer SQLite). Enabled via the open-helper callback (NOT a `PRAGMA journal_mode=WAL` over
    // driver.execute — that pragma RETURNS a row, which Android's executeUpdateDelete path rejects).
    return AndroidSqliteDriver(
      schema = ContentDb.Schema,
      context = context,
      name = "content.db",
      callback = object : AndroidSqliteDriver.Callback(ContentDb.Schema) {
        override fun onConfigure(db: SupportSQLiteDatabase) {
          super.onConfigure(db)
          db.enableWriteAheadLogging()
        }
      },
    )
  }
}
