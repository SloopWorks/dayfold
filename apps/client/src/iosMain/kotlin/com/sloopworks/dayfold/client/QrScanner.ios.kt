package com.sloopworks.dayfold.client

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

// Tier 2 (device-gated): real camera = AVCaptureSession + AVCaptureMetadataOutput
// via UIKitView, behind the AVFoundation camera authorization (shown once). Until
// that lands, report unsupported so enter-code remains the path.
actual val qrScanSupported: Boolean = false

@Composable
actual fun QrScanner(onCode: (String) -> Unit, onCancel: () -> Unit, modifier: Modifier) {
  Box(modifier.fillMaxSize().background(Color(0xFF171210)))   // placeholder; replaced by AVFoundation in Tier 2
}
