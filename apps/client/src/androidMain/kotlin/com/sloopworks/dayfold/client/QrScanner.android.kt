package com.sloopworks.dayfold.client

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

// Tier 2 (device-gated): real camera = CameraX Preview + ImageAnalysis + ML Kit
// BarcodeScanning, behind a runtime CAMERA permission (scanprimer → OS prompt →
// scandevice/scandenied). Until that lands + its deps are added, report unsupported
// so the scan affordance stays hidden and enter-code remains the path.
actual val qrScanSupported: Boolean = false

@Composable
actual fun QrScanner(onCode: (String) -> Unit, onCancel: () -> Unit, modifier: Modifier) {
  Box(modifier.fillMaxSize().background(Color(0xFF171210)))   // placeholder; replaced by CameraX in Tier 2
}
