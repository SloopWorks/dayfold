package com.sloopworks.dayfold.client

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.sloopworks.dayfold.client.db.ContentDb

actual class DriverFactory(private val context: Context) {
  actual fun createDriver(): SqlDriver =
    AndroidSqliteDriver(ContentDb.Schema, context, "content.db")
}
