package com.sloopworks.dayfold.client.ui.loading

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * Debounce a loading flag so fast responses never flash a skeleton: returns true
 * only after [loading] has held for [delayMs]; returns false the instant loading ends.
 */
@Composable
fun rememberStableLoading(loading: Boolean, delayMs: Long = 200): Boolean {
  var shown by remember { mutableStateOf(false) }
  LaunchedEffect(loading) {
    if (loading) { delay(delayMs); shown = true } else shown = false
  }
  return shown && loading
}
