package com.sloopworks.debugdrawer.panels

import java.util.Locale

internal actual fun platformInfo(): PlatformInfo = PlatformInfo(
  os = System.getProperty("os.name") ?: "Desktop",
  osVersion = System.getProperty("os.version") ?: "",
  device = "JVM ${System.getProperty("java.version") ?: ""} · ${System.getProperty("os.arch") ?: ""}".trim(),
  locale = Locale.getDefault().toString(),
)
