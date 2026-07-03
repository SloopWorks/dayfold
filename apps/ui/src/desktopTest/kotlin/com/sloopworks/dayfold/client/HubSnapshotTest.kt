package com.sloopworks.dayfold.client

import com.sloopworks.dayfold.client.theme.DayfoldTheme
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

// Renders a full hub detail with EVERY block type authored in the CANONICAL schema
// payloads (ADR 0035 Option C): document `ref` (not the client `docRef`), an itemized
// budget `items[{label,amount,paid}]` (not the client `total`/`spent`), location, etc.
// Two jobs: (1) prove those canonical payloads actually render (Option C renderer
// tolerance, #146) — not just pixels but the right content; (2) write a PNG to
// apps/client/build/snapshots/ so a session can `Read` it to eyeball the hub.
@OptIn(ExperimentalTestApi::class)
class HubSnapshotTest {
  private fun canonicalHub() = HubTree(
    hub = Hub(id = "sample", type = "starting-college", title = "Sample → Starting College", status = "active", visibility = "family"),
    sections = listOf(
      HubSection(id = "dates", hubId = "sample", title = "Dates & Deadlines", ord = 0),
      HubSection(id = "money", hubId = "sample", title = "Money & Forms", ord = 1),
    ),
    blocks = listOf(
      HubBlock(id = "b1", sectionId = "dates", type = "milestone", ord = 0,
        payload = BlockPayload(date = "Aug 1", label = "E-Bill due in full")),
      HubBlock(id = "b2", sectionId = "dates", type = "checklist", ord = 1,
        payload = BlockPayload(items = listOf(
          ChecklistItem(text = "Submit FAFSA", done = true),
          ChecklistItem(text = "Upload immunization records", done = false)))),
      HubBlock(id = "b3", sectionId = "money", type = "contact", ord = 0,
        payload = BlockPayload(name = "Financial Aid Office", role = "Billing & aid", phone = "888-555-0100")),
      // canonical document — schema `ref`, NOT the client `docRef`
      HubBlock(id = "b4", sectionId = "money", type = "document", ord = 1,
        payload = BlockPayload(ref = "https://example.edu/immunization.pdf", label = "Immunization Requirements")),
      // canonical location — schema `mapUrl`
      HubBlock(id = "b5", sectionId = "money", type = "location", ord = 2,
        payload = BlockPayload(label = "Butler University", address = "4600 Sunset Ave", mapUrl = "https://maps.example/butler")),
      // canonical itemized budget — schema `items[{label,amount,paid}]`, NOT client total/spent
      HubBlock(id = "b6", sectionId = "money", type = "budget", ord = 3,
        payload = BlockPayload(items = listOf(
          ChecklistItem(label = "Tuition", amount = 12000.0, paid = true),
          ChecklistItem(label = "Housing", amount = 6000.0, paid = false)))),
      HubBlock(id = "b7", sectionId = "money", type = "markdown", ord = 4,
        bodyMd = "## Timing traps\n- **Meningitis B** = 2 doses\n- Check the [aid portal](https://example.edu/aid)"),
    ),
  )

  private fun snapshot(name: String, dark: Boolean) = runComposeUiTest {
    val state = AppState(currentHubId = "sample", currentHubTree = canonicalHub())
    setContent { DayfoldTheme(darkTheme = dark) { HubDetailScreen(state) } }
    // capture FIRST so the artifact is written even if an assertion later fails
    val img = onRoot().captureToImage()
    assertTrue(img.width > 0 && img.height > 0, "snapshot has no pixels")
    val dir = File("build/snapshots").apply { mkdirs() }
    ImageIO.write(img.toAwtImage(), "png", File(dir, "$name.png"))
    // canonical payloads must RENDER (Option C, #146), not just produce pixels.
    // (The itemized-budget total/spent derivation is unit-tested in HubBlockRenderLogicTest.)
    onNodeWithText("Immunization Requirements").assertIsDisplayed()   // document via schema `ref`
    onNodeWithText("Financial Aid Office").assertIsDisplayed()        // contact
    onNodeWithText("Butler University").assertIsDisplayed()           // location (label + `mapUrl`)
  }

  @Test fun canonicalHubLight() = snapshot("hub-canonical-light", false)
  @Test fun canonicalHubDark() = snapshot("hub-canonical-dark", true)

  // ADR 0036 enrichment render kit (#177): a hub with media renders the EnrichedHeroBanner.
  // No hero image loads in headless, so this exercises the FALLBACK ladder — accent tile +
  // curated icon + title scrim — and accentRolesFor's color math at the render level (the
  // unit tests cover the math; nothing rendered it). Also writes a PNG to eyeball.
  @Test fun enrichedHubRendersHeroBannerFallback() = runComposeUiTest {
    val base = canonicalHub()
    val enriched = base.copy(hub = base.hub.copy(media = HubMedia(icon = "school", accentColor = "#3B5BDB")))
    val state = AppState(currentHubId = "sample", currentHubTree = enriched)
    setContent { DayfoldTheme { HubDetailScreen(state) } }
    val img = onRoot().captureToImage()
    assertTrue(img.width > 0 && img.height > 0, "enriched snapshot has no pixels")
    ImageIO.write(img.toAwtImage(), "png", File(File("build/snapshots").apply { mkdirs() }, "hub-enriched-light.png"))
    onNodeWithText("Financial Aid Office").assertIsDisplayed()   // hub content still renders below the banner
  }
}
