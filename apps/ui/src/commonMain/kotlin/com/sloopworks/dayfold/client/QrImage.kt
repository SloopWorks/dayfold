package com.sloopworks.dayfold.client

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.alexzhirkevich.qrose.options.QrBrush
import io.github.alexzhirkevich.qrose.options.solid
import io.github.alexzhirkevich.qrose.rememberQrCodePainter

// Cross-platform QR render for the owner invite-share screen (S7). qrose is a KMP
// Compose painter that publishes android/jvm/iosArm64/iosSimulatorArm64 klibs — zxing
// (the CLI's QR encoder) is JVM-only and unusable on iOS. Display-only: `data` is the
// invite URL (…/invite/{token}); the QR is never scanned in-app this slice. High error
// correction so the code survives a phone-camera scan off a screen.
@Composable
fun QrImage(data: String, modifier: Modifier, dark: Color = Color.Black, light: Color = Color.White) {
  val painter = rememberQrCodePainter(data) {
    colors {
      this.dark = QrBrush.solid(dark)
      this.light = QrBrush.solid(light)
    }
  }
  Image(painter = painter, contentDescription = "Invite QR code", modifier = modifier)
}
