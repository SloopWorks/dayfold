package com.sloopworks.dayfold.client

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.sloopworks.dayfold.client.db.ContentDb

actual class DriverFactory {
  actual fun createDriver(): SqlDriver =
    NativeSqliteDriver(ContentDb.Schema, "content.db")
}
