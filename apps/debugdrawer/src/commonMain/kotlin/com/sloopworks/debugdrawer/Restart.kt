package com.sloopworks.debugdrawer

/**
 * Best-effort app restart, used by the Backend-switch panel after persisting an
 * override (switching backend wants a clean slate — tokens/cache/sync cursor reset).
 * Returns true if a restart was triggered; false means "saved, relaunch to apply"
 * (the panel logs that). Android relaunches the launcher activity; desktop/iOS
 * return false (no safe in-process relaunch).
 */
internal expect fun attemptRestart(): Boolean
