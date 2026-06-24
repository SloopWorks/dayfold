package com.sloopworks.dayfold.client

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

// Desktop has no camera — scanning is never offered; enter-code is the only path.
actual val qrScanSupported: Boolean = false

@Composable
actual fun QrScanner(onCode: (String) -> Unit, onCancel: () -> Unit, modifier: Modifier) {
  // Never shown (qrScanSupported == false). A dark fill keeps the overlay legible
  // if a snapshot renders ScanDeviceScreen in isolation.
  Box(modifier.fillMaxSize().background(Color(0xFF171210)))
}

@Composable
actual fun rememberCameraPermissionRequester(onResult: (Boolean) -> Unit): () -> Unit = { onResult(false) }
