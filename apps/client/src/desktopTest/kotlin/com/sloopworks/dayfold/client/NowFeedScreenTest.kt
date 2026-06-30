package com.sloopworks.dayfold.client

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.sloopworks.dayfold.client.cards.CardAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR 0043 Phase A — Slice 4b/5: headless render proof of the merged feed (Compose Desktop,
 * no device). Derived + authored render as peers with the why-chip vocabulary; bands + caught-up;
 * a derived item deep-links into its hub block (the tap path; container transform + pulse are the
 * shipped hub-arrival reuse).
 */
@OptIn(ExperimentalTestApi::class)
class NowFeedScreenTest {

  private fun derived(id: String, why: String, kind: String, target: DeepLinkTarget?, geo: Boolean = false) =
    NowItem(id = id, origin = Origin.DERIVED, reasonKind = kind, title = why, why = why,
      subjectKey = "k:$id", target = target, geoActive = geo)

  @Test fun `derived and authored render as peers with why chips and bands`() = runComposeUiTest {
    val feed = RankedFeed(
      now = listOf(RankedItem(derived("d1", "You're near Safeway", ReasonKind.GEO, DeepLinkTarget("h1", null, "b1"), geo = true), Band.NOW, 0.9, emphasized = true)),
      soon = listOf(RankedItem(derived("d2", "Party in 2 days", ReasonKind.COUNTDOWN, DeepLinkTarget("h2")), Band.SOON, 0.6)),
      caughtUp = false,
    )
    val card = Card(id = "c1", title = "Rain at soccer 4pm", provenance = Provenance("claude"))
    val authored = RankedItem(cardToNowItem(card, RankConfig()), Band.SOON, 0.5)
    val merged = feed.copy(soon = feed.soon + authored)
    setContent { MaterialTheme { NowFeedList(merged, mapOf("c1" to card), onAction = {}) } }

    onNodeWithText("You're near Safeway").assertIsDisplayed()
    onNodeWithText("Party in 2 days").assertIsDisplayed()
    onNodeWithText("Rain at soccer 4pm").assertIsDisplayed()              // authored peer
    onNodeWithText("Now").assertIsDisplayed()                            // band labels
    onNodeWithText("Soon").assertIsDisplayed()
    onNodeWithText("Matched on your device · location never leaves").assertIsDisplayed()  // geo honesty chip
    onNodeWithText("Nearby").assertIsDisplayed()                         // geo-active live cue
  }

  @Test fun `tapping a derived item deep-links into its hub block`() = runComposeUiTest {
    var opened: CardAction.OpenHub? = null
    val feed = RankedFeed(
      soon = listOf(RankedItem(derived("d1", "3 left before Party", ReasonKind.CHECKLIST, DeepLinkTarget("h1", "s1", "b1")), Band.SOON, 0.6)),
      caughtUp = true,
    )
    setContent { MaterialTheme { NowFeedList(feed, emptyMap(), onAction = { if (it is CardAction.OpenHub) opened = it }) } }
    onNodeWithContentDescription("Open 3 left before Party in its hub").performClick()
    assertEquals("h1", opened?.hubId)
    assertEquals("b1", opened?.focusBlockId)
  }

  @Test fun `caught-up shows the calm headline and still renders the horizon`() = runComposeUiTest {
    val feed = RankedFeed(
      soon = listOf(RankedItem(derived("d1", "Recital in 3 days", ReasonKind.COUNTDOWN, DeepLinkTarget("h1")), Band.SOON, 0.5)),
      caughtUp = true,
    )
    setContent { MaterialTheme { NowFeedList(feed, emptyMap(), onAction = {}) } }
    onNodeWithText("You're all caught up").assertIsDisplayed()
    onNodeWithText("Recital in 3 days").assertIsDisplayed()              // horizon preserved
  }

  @Test fun `overflow collapses behind a calm count-free More`() = runComposeUiTest {
    val feed = RankedFeed(
      soon = listOf(RankedItem(derived("d1", "Visible", ReasonKind.COUNTDOWN, DeepLinkTarget("h1")), Band.SOON, 0.6)),
      overflow = listOf(RankedItem(derived("d2", "Tail item", ReasonKind.COUNTDOWN, DeepLinkTarget("h2")), Band.LATER, 0.1)),
      caughtUp = false,
    )
    setContent { MaterialTheme { NowFeedList(feed, emptyMap(), onAction = {}) } }
    onNodeWithText("More").assertIsDisplayed()
    onNodeWithText("Tail item").assertDoesNotExistYet()                  // collapsed until expanded
    onNodeWithText("More").performClick()
    onNodeWithText("Tail item").assertIsDisplayed()
  }
}

// Tiny readability helper — asserts a node is not currently in the tree.
private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistYet() = assertDoesNotExist()
