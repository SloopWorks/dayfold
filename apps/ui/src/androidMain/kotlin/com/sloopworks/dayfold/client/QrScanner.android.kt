package com.sloopworks.dayfold.client

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean

// Tier 2 Android camera actual — CameraX Preview + ImageAnalysis feeding ML Kit's
// on-device barcode scanner (QR only; no network, no cost). Reached only after the
// CAMERA runtime permission is granted (ScanPrimer → granted → ScanDevice).
actual val qrScanSupported: Boolean = true

@Composable
actual fun QrScanner(onCode: (String) -> Unit, onCancel: () -> Unit, modifier: Modifier) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  // fire onCode at most once — the analyzer runs many frames; the first decode wins.
  val handled = remember { AtomicBoolean(false) }
  val scanner = remember {
    BarcodeScanning.getClient(
      BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
    )
  }
  val cameraProvider = remember { ProcessCameraProvider.getInstance(context) }

  AndroidView(
    modifier = modifier,
    factory = { ctx ->
      val previewView = PreviewView(ctx)
      val executor = ContextCompat.getMainExecutor(ctx)
      cameraProvider.addListener({
        val provider = cameraProvider.get()
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
          .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
          .also { it.setAnalyzer(executor) { proxy -> analyze(proxy, scanner, handled, onCode) } }
        runCatching {
          provider.unbindAll()
          provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }.onFailure { onCancel() }   // no usable camera → fall back (caller routes to enter-code)
      }, executor)
      previewView
    },
  )

  DisposableEffect(Unit) {
    onDispose { runCatching { cameraProvider.get().unbindAll() }; scanner.close() }
  }
}

// imageProxy.image needs the CameraX opt-in. First QR decode → onCode(raw); always
// close the proxy so the pipeline keeps delivering frames.
@ExperimentalGetImage
private fun analyze(proxy: ImageProxy, scanner: BarcodeScanner, handled: AtomicBoolean, onCode: (String) -> Unit) {
  val media = proxy.image
  if (media == null) { proxy.close(); return }
  val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
  scanner.process(input)
    .addOnSuccessListener { barcodes ->
      val raw = barcodes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }?.rawValue
      if (raw != null && handled.compareAndSet(false, true)) onCode(raw)
    }
    .addOnCompleteListener { proxy.close() }
}

@Composable
actual fun rememberCameraPermissionRequester(onResult: (Boolean) -> Unit): () -> Unit {
  val context = LocalContext.current
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> onResult(granted) }
  return {
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    if (granted) onResult(true) else launcher.launch(Manifest.permission.CAMERA)
  }
}
