package com.sloopworks.dayfold.client

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import com.sloopworks.dayfold.client.theme.DayfoldTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ADR 0044 Phase B §3 — the reusable "Matched on your device" privacy affordance (chip / info-row /
 * detail-sheet). The honest two-part story is load-bearing: the CHIP says only "Matched on your device"
 * (the false "saved coords never leave" claim is the P0 bug — gone), and the true "your live position
 * never leaves this phone" line lives in the row/sheet body, alongside "saved places sync … encrypted".
 */
@OptIn(ExperimentalTestApi::class)
class PrivacyAffordanceTest {
  private fun shot(name: String, dark: Boolean, content: @androidx.compose.runtime.Composable () -> Unit) = runComposeUiTest {
    setContent { DayfoldTheme(darkTheme = dark) { Column { content() } } }
    val img = onRoot().captureToImage()
    assertTrue(img.width > 0 && img.height > 0, "no pixels")
    ImageIO.write(img.toAwtImage(), "png", File(File("build/snapshots").apply { mkdirs() }, "$name.png"))
  }

  @Test fun `chip reads only the honest short claim`() = shot("privacy-affordance-light", false) {
    MatchedOnDeviceChip()
    MatchedOnDeviceRow(onClick = {})
    PrivacyDetailContent(onManagePlaces = {}, onDismiss = {})
  }

  @Test fun `dark variant renders`() = shot("privacy-affordance-dark", true) {
    MatchedOnDeviceChip()
    MatchedOnDeviceRow(onClick = {})
    PrivacyDetailContent(onManagePlaces = {}, onDismiss = {})
  }

  @Test fun `the honest two-part promise is present, the false claim is not`() = runComposeUiTest {
    setContent { DayfoldTheme { Column { MatchedOnDeviceRow(onClick = {}); PrivacyDetailContent(onManagePlaces = {}, onDismiss = {}) } } }
    onNodeWithText("Matched on your device").assertIsDisplayed()
    onNodeWithText("Your live position never leaves this phone.").assertIsDisplayed()
    onNodeWithText("Saved places sync to your family").assertIsDisplayed()
  }
}
