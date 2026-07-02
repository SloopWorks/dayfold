package com.sloopworks.dayfold.client

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// AUTH-S6-D Phase 2 — in-app QR scanner seam (expect/actual).
//
// [qrScanSupported] gates the scan affordance: the EnterCode Scan/Type toggle and
// the scan routes only appear where a camera + decoder exist. Desktop = false.
// Android/iOS return false UNTIL their camera actuals land (Tier 2: CameraX + ML
// Kit barcode on Android; AVFoundation via UIKitView on iOS) — so this slice
// ships the full scan UI + flow, snapshot-verified, with the camera as the one
// remaining device-gated piece. No camera dependencies are pulled here.
expect val qrScanSupported: Boolean

// Renders the live camera preview and calls [onCode] with the raw decoded payload
// (the verification_uri_complete URL, or a bare code). The caller parses it with
// parseDeviceCode and drives lookupDevice. [onCancel] = the user backed out (or the
// platform couldn't start the camera). Hosted behind the ScanDevice overlay chrome.
// Reached only after the camera permission is granted (ScanPrimer → granted → here).
@Composable
expect fun QrScanner(onCode: (String) -> Unit, onCancel: () -> Unit, modifier: Modifier)

// Returns a launch function for the ScanPrimer "Allow camera" tap: it requests the
// OS camera permission (or reports an already-granted state) and reports the
// outcome to [onResult]. Platform-owned (Android runtime permission / iOS
// AVCaptureDevice authorization); desktop reports false (no camera).
@Composable
expect fun rememberCameraPermissionRequester(onResult: (Boolean) -> Unit): () -> Unit
