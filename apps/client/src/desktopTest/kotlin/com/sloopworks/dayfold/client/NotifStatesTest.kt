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
 * ADR 0044 Phase B · S2 — the two calm "non-event" notification states (designs/triggers/Notifications
 * §2b): quiet-hours HELD (waiting, not dropped) + daily-cap reached (a done-state, not "you missed out").
 * No badge, no urgency-count, no nag.
 */
@OptIn(ExperimentalTestApi::class)
class NotifStatesTest {
  private fun shot(name: String, dark: Boolean, content: @androidx.compose.runtime.Composable () -> Unit) = runComposeUiTest {
    setContent { DayfoldTheme(darkTheme = dark) { Column { content() } } }
    val img = onRoot().captureToImage()
    assertTrue(img.width > 0 && img.height > 0, "no pixels")
    ImageIO.write(img.toAwtImage(), "png", File(File("build/snapshots").apply { mkdirs() }, "$name.png"))
  }

  @Test fun `quiet held light`() = shot("notif-quiet-held-light", false) { QuietHoursHeldCard("8:00 AM", 2) }
  @Test fun `quiet held dark`() = shot("notif-quiet-held-dark", true) { QuietHoursHeldCard("8:00 AM", 2) }
  @Test fun `cap reached light`() = shot("notif-cap-reached-light", false) { CapReachedState(3, onOpenApp = {}) }
  @Test fun `cap reached dark`() = shot("notif-cap-reached-dark", true) { CapReachedState(3, onOpenApp = {}) }

  @Test fun `held holds, cap is a calm done-state`() = runComposeUiTest {
    setContent { DayfoldTheme { Column { QuietHoursHeldCard("8:00 AM", 2); CapReachedState(3, onOpenApp = {}) } } }
    onNodeWithText("Quiet until 8:00 AM").assertIsDisplayed()
    onNodeWithText("2 non-urgent reminders are waiting for morning.").assertIsDisplayed()
    onNodeWithText("You're all caught up").assertIsDisplayed()
    onNodeWithText("3 of 3 today · cap resets at midnight").assertIsDisplayed()
  }

  @Test fun `singular held copy`() = runComposeUiTest {
    setContent { DayfoldTheme { Column { QuietHoursHeldCard("8:00 AM", 1) } } }
    onNodeWithText("1 non-urgent reminder is waiting for morning.").assertIsDisplayed()
  }
}
