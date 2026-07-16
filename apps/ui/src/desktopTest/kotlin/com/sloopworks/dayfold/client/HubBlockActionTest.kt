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
}
