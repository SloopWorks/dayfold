package com.sloopworks.dayfold.client

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.sloopworks.dayfold.client.cards.CardAction
import kotlin.test.Test
import kotlin.test.assertEquals

// Hub contact/location blocks hand off to the OS via the same CardAction channel the Now
// cards use (Call→tel:, Message→sms:, Email→mailto:, Navigate→geo:). Before this they were
// display-only: the round affordances had no onClick and the block took no action callback.
@OptIn(ExperimentalTestApi::class)
class HubBlockActionTest {
  private fun tree(vararg blocks: HubBlock) = HubTree(
    hub = Hub(id = "h1", title = "Butler", status = "active", visibility = "family"),
    sections = listOf(HubSection(id = "s1", hubId = "h1", title = "Contacts", ord = 0)),
    blocks = blocks.toList(),
  )
  private fun contact(payload: BlockPayload) =
    HubBlock(id = "b_c", sectionId = "s1", type = "contact", ord = 0, payload = payload)
  private fun location(payload: BlockPayload) =
    HubBlock(id = "b_l", sectionId = "s1", type = "location", ord = 1, payload = payload)
  private fun state(t: HubTree) = AppState(hubs = HubState(currentHubId = "h1", currentHubTree = t))

  @Test fun `tapping Call on a contact block dials the number`() = runComposeUiTest {
    var got: CardAction? = null
    val t = tree(contact(BlockPayload(name = "Fin Aid", phone = "317-940-8200", email = "finaid@butler.edu")))
    setContent { MaterialTheme { HubDetailScreen(state(t), onCardAction = { got = it }) } }
    onNodeWithContentDescription("Call").performClick()
    assertEquals(CardAction.Call("317-940-8200"), got)
  }

  @Test fun `tapping Email on a contact block composes mail`() = runComposeUiTest {
    var got: CardAction? = null
    val t = tree(contact(BlockPayload(name = "Fin Aid", email = "finaid@butler.edu")))
    setContent { MaterialTheme { HubDetailScreen(state(t), onCardAction = { got = it }) } }
    onNodeWithContentDescription("Email").performClick()
    assertEquals(CardAction.Email("mailto:finaid@butler.edu"), got)
  }

  @Test fun `a contact with no phone shows no Call affordance`() = runComposeUiTest {
    val t = tree(contact(BlockPayload(name = "Fin Aid", email = "finaid@butler.edu")))
    setContent { MaterialTheme { HubDetailScreen(state(t), onCardAction = {}) } }
    onNodeWithContentDescription("Call").assertDoesNotExist()
    onNodeWithContentDescription("Email").assertExists()
  }

  @Test fun `tapping Directions on a location block navigates to the address`() = runComposeUiTest {
    var got: CardAction? = null
    val t = tree(location(BlockPayload(label = "Butler University", address = "4600 Sunset Ave, Indianapolis")))
    setContent { MaterialTheme { HubDetailScreen(state(t), onCardAction = { got = it }) } }
    onNodeWithText("Directions").performClick()
    assertEquals(CardAction.Navigate("4600 Sunset Ave, Indianapolis"), got)
  }

  @Test fun `Mark done on a hub block opens the direct completion step for its exact subject`() = runComposeUiTest {
    var got: CardAction? = null
    val block = HubBlock(
      id = "b_task", sectionId = "s1", type = "text", bodyMd = "Call the caterer",
      provenance = Provenance(source = "Weekend planner"),
    )
    setContent { MaterialTheme { HubDetailScreen(state(tree(block)), onCardAction = { got = it }) } }

    onNodeWithContentDescription("More options for Call the caterer").performClick()
    onNodeWithText("Mark done").performClick()

    assertEquals(
      CardAction.Respond(
        subjectRef = "hub:h1/section:s1/block:b_task",
        subjectTitle = "Call the caterer",
        reasonKind = "text",
        source = "Weekend planner",
        surface = ResponseSurface.HUB,
        initialStep = ResponseStep.DONE_NOTE,
      ),
      got,
    )
  }

  @Test fun `response subject labels strip every markdown heading marker`() {
    assertEquals(
      "Party day plan",
      responseSubjectTitleFor(HubBlock("b", type = "markdown", bodyMd = "## Party day plan\n\nDoors at 3")),
    )
  }

  @Test fun `response subject labels strip list checkbox emphasis and link markdown`() {
    assertEquals("Confirm caterer menu", markdownPreviewText("- [ ] **Confirm [caterer](https://example.com) menu**"))
  }

  @Test fun `response subject labels skip separators and strip blockquotes`() {
    assertEquals(
      "Important update",
      responseSubjectTitleFor(HubBlock("b", type = "markdown", bodyMd = "---\n\n> Important update")),
    )
  }

  @Test fun `hub dates are human readable instead of raw wire timestamps`() {
    assertEquals("Due Jun 21, 1:00 PM", checklistDueLabel("2026-06-21T13:00:00Z"))
    assertEquals("Jun 21, 3:00 PM", milestoneDateLabel("2026-06-21T15:00:00Z"))
  }
}
