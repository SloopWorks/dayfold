package com.sloopworks.dayfold.client

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.sloopworks.dayfold.client.theme.DayfoldTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR 0044 Phase B · S2 — the navigable Background-proximity settings host: top bar + the device-local
 * config writes (toggle / daily-cap). The write goes UI → onSetNotifConfig (→ ContentStore in the shell);
 * there is no optimistic UI→store shortcut. Enabling here is what arms the geofences + exact alarms.
 */
@OptIn(ExperimentalTestApi::class)
class ProximitySettingsHostTest {

  @Test fun `toggling on emits an enabled config write`() = runComposeUiTest {
    var written: NotifConfig? = null
    setContent {
      DayfoldTheme {
        ProximitySettingsHost(
          config = NotifConfig(enabled = false),
          permission = LocationPermission.WhenInUse,
          onSetNotifConfig = { written = it },
          onOpenPermission = {}, onBack = {},
        )
      }
    }
    onNodeWithContentDescription("Background proximity").performClick()
    assertEquals(true, written?.enabled)
  }

  @Test fun `picking a daily cap emits the new cap`() = runComposeUiTest {
    var written: NotifConfig? = null
    setContent {
      DayfoldTheme {
        ProximitySettingsHost(
          config = NotifConfig(enabled = true, dailyCap = 3),
          permission = LocationPermission.Always,
          onSetNotifConfig = { written = it },
          onOpenPermission = {}, onBack = {},
        )
      }
    }
    onNodeWithText("5").performClick()   // the segmented daily-cap chooser
    assertEquals(5, written?.dailyCap)
  }

  @Test fun `back invokes the close callback`() = runComposeUiTest {
    var backed = false
    setContent {
      DayfoldTheme {
        ProximitySettingsHost(NotifConfig(), LocationPermission.Denied, onSetNotifConfig = {}, onOpenPermission = {}, onBack = { backed = true })
      }
    }
    onNodeWithContentDescription("Back").performClick()
    assertTrue(backed)
  }
}
