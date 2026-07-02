package com.sloopworks.dayfold.client

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import com.sloopworks.dayfold.client.theme.DayfoldTheme
import com.sloopworks.dayfold.client.ui.loading.EmptyState
import com.sloopworks.dayfold.client.ui.loading.ErrorRetry
import com.sloopworks.dayfold.client.ui.loading.FeedSkeleton
import com.sloopworks.dayfold.client.ui.loading.ListSkeleton
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LoadingKitSnapshotTest {
  private fun snap(name: String, dark: Boolean = false, content: @Composable () -> Unit) = runComposeUiTest {
    setContent { DayfoldTheme(darkTheme = dark) { content() } }
    val img = onRoot().captureToImage()
    assertTrue(img.width > 0 && img.height > 0, "snapshot has no pixels")
    val dir = File("build/snapshots").apply { mkdirs() }
    ImageIO.write(img.toAwtImage(), "png", File(dir, "$name.png"))
  }

  @Test fun listSkeleton() = snap("kit-list-skeleton") { ListSkeleton(rows = 4, modifier = Modifier.fillMaxWidth()) }
  @Test fun feedSkeleton() = snap("kit-feed-skeleton") { FeedSkeleton() }
  @Test fun errorRetry() = snap("kit-error-retry") { ErrorRetry("Couldn't load devices. Try again.", onRetry = {}) }
  @Test fun errorRetryBusy() = snap("kit-error-retry-busy") { ErrorRetry("Retrying", onRetry = {}, retrying = true) }
  @Test fun emptyState() = snap("kit-empty-state") { EmptyState("No devices yet", "Phones and CLIs you authorize show up here.") }
}
