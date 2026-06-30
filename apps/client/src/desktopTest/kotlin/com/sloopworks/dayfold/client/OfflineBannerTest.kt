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
 * ADR 0044 Phase B · S2 — the OFFLINE banner (designs/triggers/Offline-Phone). Offline is a strength,
 * not an error: matching still happens on the device. Privacy-teal, honest copy.
 */
@OptIn(ExperimentalTestApi::class)
class OfflineBannerTest {
  private fun shot(name: String, dark: Boolean, content: @androidx.compose.runtime.Composable () -> Unit) = runComposeUiTest {
    setContent { DayfoldTheme(darkTheme = dark) { Column { content() } } }
    val img = onRoot().captureToImage()
    assertTrue(img.width > 0 && img.height > 0, "no pixels")
    ImageIO.write(img.toAwtImage(), "png", File(File("build/snapshots").apply { mkdirs() }, "$name.png"))
  }

  @Test fun `offline banner light`() = shot("offline-banner-light", false) { OfflineBanner() }
  @Test fun `offline banner dark`() = shot("offline-banner-dark", true) { OfflineBanner() }

  @Test fun `offline copy frames matching as on-device, not an error`() = runComposeUiTest {
    setContent { DayfoldTheme { Column { OfflineBanner() } } }
    onNodeWithText("Offline · still matched on your device").assertIsDisplayed()
  }
}
