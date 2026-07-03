package com.sloopworks.dayfold.client

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.sloopworks.dayfold.client.theme.DayfoldTheme
import kotlin.test.Test
import kotlin.test.assertTrue

// The QR device-grant entry must NEVER dead-end: both the camera primer and the
// camera-DENIED screen always offer an "Enter code instead" manual escape (comments
// in ScanScreens: "no dead end"). These tags (scan-allow/entercode/settings/
// denied-entercode) had no behavioral test — only snapshots. A regression dropping
// the escape would strand a user whose camera is off: they couldn't approve a device.
// ScanDeviceScreen is omitted here: it embeds a live QrScanner (camera), unsafe headless.
@OptIn(ExperimentalTestApi::class)
class ScanScreensUiTest {
  @Test fun primerOffersAllowAndAnEnterCodeEscape() = runComposeUiTest {
    var allowed = false; var enteredCode = false
    setContent { DayfoldTheme { ScanPrimerScreen(onAllow = { allowed = true }, onEnterCode = { enteredCode = true }) } }
    onNodeWithTag("scan-allow").performClick()
    onNodeWithTag("scan-entercode").performClick()
    assertTrue(allowed, "Allow camera → onAllow")
    assertTrue(enteredCode, "primer always offers a manual escape")
  }

  @Test fun deniedScreenOffersSettingsAndAnEnterCodeEscape() = runComposeUiTest {
    // camera permission off — the critical no-dead-end case
    var settings = false; var enteredCode = false
    setContent { DayfoldTheme { ScanDeniedScreen(onOpenSettings = { settings = true }, onEnterCode = { enteredCode = true }) } }
    onNodeWithTag("scan-settings").performClick()
    onNodeWithTag("scan-denied-entercode").performClick()
    assertTrue(settings, "Open Settings → onOpenSettings")
    assertTrue(enteredCode, "denied screen must still let the user enter a code")
  }
}
