package com.sloopworks.debugdrawer

// No safe generic in-process JVM relaunch — the override is saved; the developer
// relaunches to apply.
internal actual fun attemptRestart(): Boolean = false
