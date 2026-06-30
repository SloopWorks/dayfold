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
 * ADR 0044 Phase B · S2 — the opt-in PERMISSION LADDER (designs/triggers/Permission-Phone). The honesty
 * + reversibility posture is load-bearing: the "Not now" / secondary action is always a FULL-COLOR PEER
 * (never disabled), the on-device promise rides every priming screen, and the denied/limited states are
 * calm ("paused", "perfectly good setup") — never broken, never nagging. Six states map 1:1.
 */
@OptIn(ExperimentalTestApi::class)
class PermissionLadderTest {
  private fun shot(name: String, dark: Boolean, content: @androidx.compose.runtime.Composable () -> Unit) = runComposeUiTest {
    setContent { DayfoldTheme(darkTheme = dark) { Column { content() } } }
    val img = onRoot().captureToImage()
    assertTrue(img.width > 0 && img.height > 0, "no pixels")
    ImageIO.write(img.toAwtImage(), "png", File(File("build/snapshots").apply { mkdirs() }, "$name.png"))
  }

  @Test fun `loc prime light`() = shot("permission-locprime-light", false) {
    PermissionLadderScreen(PermissionPrompt.LocPrime, onPrimary = {}, onSecondary = {})
  }

  @Test fun `loc prime dark`() = shot("permission-locprime-dark", true) {
    PermissionLadderScreen(PermissionPrompt.LocPrime, onPrimary = {}, onSecondary = {})
  }

  @Test fun `always upgrade shows the honest tradeoff`() = shot("permission-always-light", false) {
    PermissionLadderScreen(PermissionPrompt.AlwaysUpgrade, onPrimary = {}, onSecondary = {})
  }

  @Test fun `notif prime light`() = shot("permission-notif-light", false) {
    PermissionLadderScreen(PermissionPrompt.NotifPrime, onPrimary = {}, onSecondary = {})
  }

  @Test fun `limited is calm not broken`() = shot("permission-limited-light", false) {
    PermissionLadderScreen(PermissionPrompt.Limited, onPrimary = {}, onSecondary = {})
  }

  @Test fun `denied is paused not broken`() = shot("permission-denied-light", false) {
    PermissionLadderScreen(PermissionPrompt.Denied, onPrimary = {}, onSecondary = {})
  }

  @Test fun `downgraded but functional dark`() = shot("permission-downgraded-dark", true) {
    PermissionLadderScreen(PermissionPrompt.Downgraded, onPrimary = {}, onSecondary = {})
  }

  @Test fun `the on-device promise + a full-peer Not now ride the priming screen`() = runComposeUiTest {
    setContent { DayfoldTheme { Column { PermissionLadderScreen(PermissionPrompt.LocPrime, onPrimary = {}, onSecondary = {}) } } }
    onNodeWithText("Matched on your device").assertIsDisplayed()
    onNodeWithText("Not now").assertIsDisplayed()          // secondary is present as a peer, never disabled
    onNodeWithText("Allow while using").assertIsDisplayed()
  }

  @Test fun `always upgrade names the battery + closed-app trade honestly`() = runComposeUiTest {
    setContent { DayfoldTheme { Column { PermissionLadderScreen(PermissionPrompt.AlwaysUpgrade, onPrimary = {}, onSecondary = {}) } } }
    onNodeWithText("Uses a little more battery in the background.").assertIsDisplayed()
    onNodeWithText("Keep when-in-use").assertIsDisplayed()  // declining Always is a full peer
  }
}
