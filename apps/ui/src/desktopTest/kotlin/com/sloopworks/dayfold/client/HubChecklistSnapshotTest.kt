package com.sloopworks.dayfold.client

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import com.sloopworks.dayfold.client.theme.DayfoldTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

// Slice 4 — render the INTERACTIVE checklist (items with ids) to PNGs so a session can
// `Read` them and eyeball the toggle UI against designs/two-way/{States,Todo}: coral
// check + strike on done, the folded "N done" line, the honesty chip, and the optimistic
// pending (hairline) / failed (calm Retry) + the offline banner & queue pill.
@OptIn(ExperimentalTestApi::class)
class HubChecklistSnapshotTest {
  private fun tree(localState: String? = null) = HubTree(
    hub = Hub(id = "h1", type = "party-event", title = "Maya's birthday", status = "active", visibility = "family"),
    sections = listOf(HubSection(id = "s1", hubId = "h1", title = "Packing", ord = 0)),
    blocks = listOf(HubBlock(id = "b_chk", sectionId = "s1", type = "checklist", ord = 0, version = 3, localState = localState,
      payload = BlockPayload(items = listOf(
        ChecklistItem(id = "i1", text = "Cooler + ice", done = false, assignee = "Sam"),
        ChecklistItem(id = "i2", text = "Beach umbrella", done = false),
        ChecklistItem(id = "i3", text = "Sunscreen", done = true, doneBy = "Mom", doneAt = "2026-06-29T10:00:00Z"))))),
  )

  private fun shot(name: String, dark: Boolean, state: AppState) = runComposeUiTest {
    setContent { DayfoldTheme(darkTheme = dark) { HubDetailScreen(state) } }
    val img = onRoot().captureToImage()
    assertTrue(img.width > 0 && img.height > 0, "snapshot has no pixels")
    val dir = File("build/snapshots").apply { mkdirs() }
    ImageIO.write(img.toAwtImage(), "png", File(dir, "$name.png"))
  }

  @Test fun interactiveLight() {
    shot("checklist-interactive-light", false, AppState(currentHubId = "h1", currentHubTree = tree()))
  }
  @Test fun interactiveDark() {
    shot("checklist-interactive-dark", true, AppState(currentHubId = "h1", currentHubTree = tree()))
  }

  @Test fun pendingShowsQueuePill() = runComposeUiTest {
    val state = AppState(currentHubId = "h1", currentHubTree = tree(localState = "pending"))
    setContent { DayfoldTheme { HubDetailScreen(state) } }
    onNodeWithText("Sync now").assertExists()                     // queue pill present while a write is in flight
    val img = onRoot().captureToImage()
    ImageIO.write(img.toAwtImage(), "png", File(File("build/snapshots").apply { mkdirs() }, "checklist-pending.png"))
  }

  @Test fun failedShowsBannerAndRetry() = runComposeUiTest {
    val state = AppState(currentHubId = "h1", currentHubTree = tree(localState = "failed"), error = "HTTP 500")
    setContent { DayfoldTheme { HubDetailScreen(state) } }
    onNodeWithText("Couldn't save", substring = true).assertExists()
    onNodeWithText("Saved here").assertExists()                   // calm offline/queued banner
    val img = onRoot().captureToImage()
    ImageIO.write(img.toAwtImage(), "png", File(File("build/snapshots").apply { mkdirs() }, "checklist-failed.png"))
  }
}
