package com.sloopworks.debugdrawer

// iOS has no public programmatic relaunch — the override is saved; relaunch to apply.
internal actual fun attemptRestart(): Boolean = false
