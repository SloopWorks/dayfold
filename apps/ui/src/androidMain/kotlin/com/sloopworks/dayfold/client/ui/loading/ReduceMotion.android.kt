package com.sloopworks.dayfold.client.ui.loading

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

// Honor the system "remove animations" setting (ANIMATOR_DURATION_SCALE == 0).
@Composable actual fun rememberReduceMotion(): Boolean {
  val resolver = LocalContext.current.contentResolver
  return remember(resolver) {
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
  }
}
