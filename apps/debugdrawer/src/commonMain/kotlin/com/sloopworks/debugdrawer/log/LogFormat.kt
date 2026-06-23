package com.sloopworks.debugdrawer.log

import androidx.compose.ui.graphics.Color
import com.sloopworks.debugdrawer.theme.DrawerColors

/** Pure log helpers — extracted so filtering/level mapping is unit-testable. */

/** `null` level = show all (V), else only that level. */
fun filterLogs(entries: List<LogEntry>, level: LogLevel?): List<LogEntry> =
  if (level == null) entries else entries.filter { it.level == level }

/** Single-letter level tag (color is never the only signal — a11y). */
fun LogLevel.letter(): String = name

fun levelColor(level: LogLevel, c: DrawerColors): Color = when (level) {
  LogLevel.V -> c.logV
  LogLevel.D -> c.logD
  LogLevel.I -> c.logI
  LogLevel.W -> c.logW
  LogLevel.E -> c.logE
}
