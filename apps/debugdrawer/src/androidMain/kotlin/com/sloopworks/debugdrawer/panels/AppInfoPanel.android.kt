package com.sloopworks.debugdrawer.panels

import android.os.Build
import java.util.Locale

internal actual fun platformInfo(): PlatformInfo = PlatformInfo(
  os = "Android",
  osVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
  device = "${Build.MANUFACTURER} ${Build.MODEL}",
  locale = Locale.getDefault().toString(),
)
