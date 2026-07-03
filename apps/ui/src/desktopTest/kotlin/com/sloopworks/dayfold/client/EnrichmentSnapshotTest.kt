package com.sloopworks.dayfold.client

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import com.sloopworks.dayfold.client.theme.DayfoldTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

// ADR 0036 — visual-enrichment render snapshots → apps/client/build/snapshots/.
// Headless has no network, so the Coil image never resolves and the fallback ladder
// renders (icon+accent tile) — exactly the design-critical "a failure is invisible"
// state. On-device the real Wikimedia image loads over the same composables.
@OptIn(ExperimentalTestApi::class)
class EnrichmentSnapshotTest {
  private val HERO = "https://upload.wikimedia.org/wikipedia/commons/0/0c/Logo.png"

  private fun snap(name: String, dark: Boolean, content: @Composable () -> Unit) = runComposeUiTest {
    setContent { DayfoldTheme(darkTheme = dark) { content() } }
    val img = onRoot().captureToImage()
    assertTrue(img.width > 0 && img.height > 0, "no pixels")
    ImageIO.write(img.toAwtImage(), "png", File("build/snapshots".also { File(it).mkdirs() }, "$name.png"))
  }

  // mixed list: cover photo, contain logo, icon-tile (no image), and an unenriched hub.
  private val hubs = listOf(
    Hub(id = "trip", type = "vacation", title = "Summer trip — Lisbon", status = "planning", countdownTo = "2026-08-03T00:00:00Z",
      media = HubMedia(heroUrl = HERO, thumbnailUrl = HERO, heroFit = "cover", imageAlt = "Lisbon coast", icon = "travel", accentColor = "#1C6E8C")),
    Hub(id = "college", type = "starting-college", title = "Maya starts college", status = "planning", countdownTo = "2026-08-24T00:00:00Z",
      media = HubMedia(heroUrl = HERO, heroFit = "contain", imageAlt = "University logo", icon = "school", accentColor = "#2C3E73")),
    Hub(id = "med", type = "medical", title = "Dad's knee surgery", status = "active",
      media = HubMedia(icon = "medical", accentColor = "#1F8A6D")),
    Hub(id = "plain", type = "move", title = "House move (unenriched)", status = "planning"),
  )

  @Test fun hubListLight() = snap("enrich-hublist", false) { HubListScreen(AppState(hubs = hubs)) }
  @Test fun hubListDark() = snap("enrich-hublist-dark", true) { HubListScreen(AppState(hubs = hubs)) }

  // hero detail — contain logo (fallback to accent + school icon tile, with scrim title).
  private fun detail(hub: Hub) = AppState(currentHubTree = HubTree(hub = hub, sections = emptyList(), blocks = emptyList()))
  @Test fun hubDetailLogoLight() = snap("enrich-hubdetail-logo", false) { HubDetailScreen(detail(hubs[1])) }
  @Test fun hubDetailLogoDark() = snap("enrich-hubdetail-logo-dark", true) { HubDetailScreen(detail(hubs[1])) }
  @Test fun hubDetailPhotoLight() = snap("enrich-hubdetail-photo", false) { HubDetailScreen(detail(hubs[0])) }

  // enriched feed cards: icon+accent kind chip + (thumb→tile fallback); + accent-only.
  private val feed = AppState(cards = listOf(
    Card("trip", kind = "action", title = "Lisbon check-in opens today", bodyMd = "Window seats still free.",
      provenance = Provenance("claude"), media = CardMedia(icon = "travel", accentColor = "#1C6E8C", thumbnailUrl = HERO, imageAlt = "trip")),
    Card("school", kind = "action", title = "Dorm forms due Thursday", bodyMd = "Sign the housing waiver.",
      provenance = Provenance("claude"), media = CardMedia(icon = "school", accentColor = "#2C3E73")),
  ))
  @Test fun enrichedFeedLight() = snap("enrich-feed", false) { FeedScreen(feed) }
  @Test fun enrichedFeedDark() = snap("enrich-feed-dark", true) { FeedScreen(feed) }
}
