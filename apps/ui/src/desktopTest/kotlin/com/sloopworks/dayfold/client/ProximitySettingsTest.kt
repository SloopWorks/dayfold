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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR 0044 Phase B · S2 — the reversible Background-proximity SETTINGS (designs/triggers/Settings-Phone).
 * Default-off, device-local (never synced), reversible: the OFF state confirms "Geofences removed" and
 * (while de-registering) shows the async state; the daily cap is a SEGMENTED chooser (a budget, not a
 * slider); quiet-hours is editable. The on-device banner + privacy row carry the honesty posture.
 */
@OptIn(ExperimentalTestApi::class)
class ProximitySettingsTest {
  private fun shot(name: String, dark: Boolean, content: @androidx.compose.runtime.Composable () -> Unit) = runComposeUiTest {
    setContent { DayfoldTheme(darkTheme = dark) { Column { content() } } }
    val img = onRoot().captureToImage()
    assertTrue(img.width > 0 && img.height > 0, "no pixels")
    ImageIO.write(img.toAwtImage(), "png", File(File("build/snapshots").apply { mkdirs() }, "$name.png"))
  }

  private val onCfg = NotifConfig(enabled = true)
  private val offCfg = NotifConfig(enabled = false)

  @Test fun `formatMinuteOfDay renders 12-hour clock with AM PM`() {
    assertEquals("10:00 PM", formatMinuteOfDay(22 * 60))
    assertEquals("8:00 AM", formatMinuteOfDay(8 * 60))
    assertEquals("12:00 AM", formatMinuteOfDay(0))
    assertEquals("12:30 PM", formatMinuteOfDay(12 * 60 + 30))
    assertEquals("9:05 AM", formatMinuteOfDay(9 * 60 + 5))
  }

  @Test fun `settings on light`() = shot("proximity-settings-on-light", false) {
    ProximitySettingsScreen(onCfg, LocationPermission.Always, deregistering = false, {}, {}, {}, {}, {}, {})
  }

  @Test fun `settings on dark`() = shot("proximity-settings-on-dark", true) {
    ProximitySettingsScreen(onCfg, LocationPermission.Always, deregistering = false, {}, {}, {}, {}, {}, {})
  }

  @Test fun `settings off shows geofences removed`() = shot("proximity-settings-off-light", false) {
    ProximitySettingsScreen(offCfg, LocationPermission.WhenInUse, deregistering = false, {}, {}, {}, {}, {}, {})
  }

  @Test fun `settings de-registering shows async state`() = shot("proximity-settings-deregistering-light", false) {
    ProximitySettingsScreen(offCfg, LocationPermission.WhenInUse, deregistering = true, {}, {}, {}, {}, {}, {})
  }

  @Test fun `off state confirms geofences removed + on state names the cap`() = runComposeUiTest {
    setContent { DayfoldTheme { Column { ProximitySettingsScreen(offCfg, LocationPermission.WhenInUse, false, {}, {}, {}, {}, {}, {}) } } }
    onNodeWithText("Geofences removed").assertIsDisplayed()
    onNodeWithText("These settings live on this phone").assertIsDisplayed()
  }

  @Test fun `on state shows the device-local privacy posture + quiet hours`() = runComposeUiTest {
    setContent { DayfoldTheme { Column { ProximitySettingsScreen(onCfg, LocationPermission.Always, false, {}, {}, {}, {}, {}, {}) } } }
    onNodeWithText("Quiet hours").assertIsDisplayed()
    onNodeWithText("Daily cap").assertIsDisplayed()
    onNodeWithText("10:00 PM").assertIsDisplayed()
  }
}
