package com.familyai.client

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

// Compose UI snapshots — render FeedScreen(state) off-screen and WRITE the PNG
// to apps/client/build/snapshots/ so a future agent session can `Read` the
// image to verify the UI without re-deriving the adb-screencap flow (cheaper
// loop). Golden-diff (Roborazzi, CI-enforced) is the next step — ADR 0019.
@OptIn(ExperimentalTestApi::class)
class FeedSnapshotTest {
  private fun snapshot(name: String, state: AppState) = runComposeUiTest {
    setContent { MaterialTheme { FeedScreen(state) } }
    val img = onRoot().captureToImage()
    assertTrue(img.width > 0 && img.height > 0, "snapshot has no pixels")
    val dir = File("build/snapshots").apply { mkdirs() }
    ImageIO.write(img.toAwtImage(), "png", File(dir, "$name.png"))
  }

  @Test
  fun populatedFeedSnapshot() = snapshot(
    "feed-populated",
    AppState(cards = listOf(
      Card("a", title = "Party Saturday — order groceries?", notBefore = "2026-06-20T09:00:00Z"),
      Card("b", title = "Rain at soccer 4pm — pack jackets", notBefore = "2026-06-18T15:00:00Z"),
    )),
  )

  @Test
  fun emptyFeedSnapshot() = snapshot("feed-empty", AppState())
}
