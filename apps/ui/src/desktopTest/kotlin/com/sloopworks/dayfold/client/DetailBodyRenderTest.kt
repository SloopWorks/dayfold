package com.sloopworks.dayfold.client

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runComposeUiTest
import com.sloopworks.dayfold.client.cards.DetailScreen
import com.sloopworks.dayfold.client.theme.DayfoldTheme
import kotlin.test.Test

// A typed card's body_md renders on the DETAIL screen as formatted markdown (bold
// stripped of `**`, links shown as their label) — the fix for typed cards, which
// previously dropped body_md on detail and dumped it raw in the feed row. The
// vetted-link routing itself is proven by InlineLinkHandoffTest (same renderer).
@OptIn(ExperimentalTestApi::class)
class DetailBodyRenderTest {
  private val card = Card(
    id = "c1", kind = "action", title = "Firestone tire appointment",
    provenance = Provenance("claude"), type = "geo",
    payload = Payload(geo = GeoPayload(address = "21780 Market Place NW", etaMin = 28)),
    bodyMd = "**Bring** the wheel-lock key.\n[Directions](https://maps.example/x)",
  )

  @Test fun bodyMarkdownRendersOnDetailWithoutRawSyntax() = runComposeUiTest {
    setContent { DayfoldTheme { DetailScreen(card, onBack = {}, onAction = {}) } }
    // The body sits below the fold in the detail LazyColumn — scroll it in. (The
    // outer vertical list is the FIRST scrollable; the actions row also scrolls.)
    onAllNodes(hasScrollAction()).onFirst()
      .performScrollToNode(hasText("Bring the wheel-lock key.", substring = true))
    // bold rendered as its content (no raw `**`), link rendered as its label
    onNodeWithText("Bring the wheel-lock key.", substring = true).assertIsDisplayed()
    onNodeWithText("Directions", substring = true).assertIsDisplayed()
  }
}
