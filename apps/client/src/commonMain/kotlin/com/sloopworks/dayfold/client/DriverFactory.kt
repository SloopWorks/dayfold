package com.sloopworks.dayfold.client

import app.cash.sqldelight.db.SqlDriver

// Per-platform SQLDelight driver (ADR 0020). The sync engine (TASK-SYNC) builds a
// ContentStore from this; nothing wires it into the store at runtime yet — this
// just gives each target a driver path so the shared ContentStore + generated DB
// compile on every platform.
expect class DriverFactory {
  fun createDriver(): SqlDriver
}
