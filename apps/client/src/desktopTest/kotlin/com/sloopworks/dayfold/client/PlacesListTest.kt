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
 * ADR 0044 Phase B · S2 — the READ-ONLY saved-places list (designs/triggers/Places-Phone, list state).
 * Places are CLI/server-authored; the client only renders them — no edit pencil, no Add FAB. The
 * family-privacy row carries the honesty posture.
 */
@OptIn(ExperimentalTestApi::class)
class PlacesListTest {
  private val places = listOf(
    Place(id = "home", kind = "home", label = "Home", lat = 0.0, lng = 0.0, radiusM = 150),
    Place(id = "school", kind = "school", label = "Maya's school", lat = 0.01, lng = 0.0, radiusM = 250),
    Place(id = "store", kind = "store", label = "Lincoln Market", lat = 0.02, lng = 0.0, radiusM = 200),
  )

  private fun shot(name: String, dark: Boolean, content: @androidx.compose.runtime.Composable () -> Unit) = runComposeUiTest {
    setContent { DayfoldTheme(darkTheme = dark) { Column { content() } } }
    val img = onRoot().captureToImage()
    assertTrue(img.width > 0 && img.height > 0, "no pixels")
    ImageIO.write(img.toAwtImage(), "png", File(File("build/snapshots").apply { mkdirs() }, "$name.png"))
  }

  @Test fun `places list light`() = shot("places-list-light", false) { PlacesListScreen(places) }
  @Test fun `places list dark`() = shot("places-list-dark", true) { PlacesListScreen(places) }
  @Test fun `places empty`() = shot("places-empty-light", false) { PlacesListScreen(emptyList()) }

  @Test fun `list renders places with provenance + privacy posture`() = runComposeUiTest {
    setContent { DayfoldTheme { Column { PlacesListScreen(places) } } }
    onNodeWithText("Maya's school").assertIsDisplayed()
    onNodeWithText("Lincoln Market").assertIsDisplayed()
    onNodeWithText("Your places stay private").assertIsDisplayed()
  }
}
