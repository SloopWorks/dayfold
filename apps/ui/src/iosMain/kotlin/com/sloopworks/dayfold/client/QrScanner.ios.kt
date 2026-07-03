package com.sloopworks.dayfold.client

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeQRCode
import platform.CoreGraphics.CGRectMake
import platform.QuartzCore.CATransaction
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.darwin.dispatch_get_main_queue

// Tier 2 iOS camera actual — AVCaptureSession + AVCaptureMetadataOutput (QR) shown
// through a UIKitView. Reached only after camera authorization (ScanPrimer Allow).
//
// qrScanSupported stays false until the runnable Xcode host exists: it must declare
// NSCameraUsageDescription (and the associated-domains entitlement for Universal
// Links). Flip this to true in that host's slice — the scanner code below is ready.
actual val qrScanSupported: Boolean = false

// A UIView whose AVCaptureVideoPreviewLayer tracks its bounds (Compose lays the
// view out; the capture layer doesn't auto-resize).
@OptIn(ExperimentalForeignApi::class)
private class ScanPreviewView : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
  var previewLayer: AVCaptureVideoPreviewLayer? = null
  override fun layoutSubviews() {
    super.layoutSubviews()
    CATransaction.begin()
    CATransaction.setDisableActions(true)
    previewLayer?.setFrame(bounds)
    CATransaction.commit()
  }
}

private class QrDelegate(val onCode: (String) -> Unit) : NSObject(), AVCaptureMetadataOutputObjectsDelegateProtocol {
  private var handled = false
  override fun captureOutput(
    output: AVCaptureOutput,
    didOutputMetadataObjects: List<*>,
    fromConnection: AVCaptureConnection,
  ) {
    if (handled) return
    val value = (didOutputMetadataObjects.firstOrNull() as? AVMetadataMachineReadableCodeObject)?.stringValue ?: return
    handled = true
    onCode(value)
  }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun QrScanner(onCode: (String) -> Unit, onCancel: () -> Unit, modifier: Modifier) {
  val session = remember { AVCaptureSession() }
  val delegate = remember { QrDelegate(onCode) }

  UIKitView(
    factory = {
      val view = ScanPreviewView()
      val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
      val input = device?.let { AVCaptureDeviceInput.deviceInputWithDevice(it, null) }
      if (input == null || !session.canAddInput(input)) {
        onCancel()                                  // no usable camera → caller routes to enter-code
        return@UIKitView view
      }
      session.addInput(input)
      val output = AVCaptureMetadataOutput()
      if (session.canAddOutput(output)) {
        session.addOutput(output)
        output.setMetadataObjectsDelegate(delegate, dispatch_get_main_queue())
        output.metadataObjectTypes = listOf(AVMetadataObjectTypeQRCode)
      }
      val preview = AVCaptureVideoPreviewLayer(session = session)
      preview.videoGravity = AVLayerVideoGravityResizeAspectFill
      view.previewLayer = preview
      view.layer.addSublayer(preview)
      session.startRunning()
      view
    },
    modifier = modifier,
  )

  DisposableEffect(Unit) {
    onDispose { session.stopRunning() }   // harmless if already stopped
  }
}

// Camera-permission request is wired with the rest of the iOS host slice
// (AVCaptureDevice.requestAccessForMediaType + NSCameraUsageDescription). Until
// then iOS reports no camera (qrScanSupported == false), so this is never invoked.
@Composable
actual fun rememberCameraPermissionRequester(onResult: (Boolean) -> Unit): () -> Unit = { onResult(false) }
