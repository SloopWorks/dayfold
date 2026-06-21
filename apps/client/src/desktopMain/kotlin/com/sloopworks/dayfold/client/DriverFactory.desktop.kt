package com.sloopworks.dayfold.client

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.sloopworks.dayfold.client.db.ContentDb
import java.io.File

actual class DriverFactory {
  actual fun createDriver(): SqlDriver {
    val dir = File(System.getProperty("user.home"), ".dayfold").apply { mkdirs() }
    val driver = JdbcSqliteDriver(
      "jdbc:sqlite:${File(dir, "content.db").absolutePath}",
      schema = ContentDb.Schema,   // create/migrate handled by the driver
    )
    driver.execute(null, "PRAGMA journal_mode=WAL", 0)
    return driver
  }
}
