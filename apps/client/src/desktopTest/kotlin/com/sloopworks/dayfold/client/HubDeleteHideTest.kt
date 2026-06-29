package com.sloopworks.dayfold.client

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Slice 5b (ADR 0038 §W4/§W5) — the delete sheet is author-gated; hide is local + reachable
// by overflow (the a11y path). Drives the real HubDetailScreen callbacks the shells route.
@OptIn(ExperimentalTestApi::class)
class HubDeleteHideTest {
  private val me = Session(access = "a", refresh = "r", userId = "usr_mom")
  private fun treeWith(vararg blocks: HubBlock) = HubTree(
    hub = Hub(id = "h1", title = "Party", status = "active", visibility = "family"),
    sections = listOf(HubSection(id = "s1", hubId = "h1", title = "Plan", ord = 0)),
    blocks = blocks.toList(),
  )
  private fun textBlock(id: String, author: String?) =
    HubBlock(id = id, sectionId = "s1", type = "text", bodyMd = "Grocery run list", createdBy = author, ord = 0)

  @Test fun authorCanOpenTheDeleteSheetAndConfirm() = androidx.compose.ui.test.runComposeUiTest {
    var deleted: String? = null
    val state = AppState(session = me, currentHubId = "h1", currentHubTree = treeWith(textBlock("b1", author = "usr_mom")))
    setContent { MaterialTheme { HubDetailScreen(state, onDeleteBlock = { deleted = it }) } }
    onNodeWithContentDescription("More options").performClick()
    onNodeWithText("Delete").performClick()                      // overflow → Delete opens the warn sheet
    onNodeWithText("Delete for everyone").performClick()         // confirm
    assertEquals("b1", deleted)
  }

  @Test fun keepItDismissesWithoutDeleting() = androidx.compose.ui.test.runComposeUiTest {
    var deleted: String? = null
    val state = AppState(session = me, currentHubId = "h1", currentHubTree = treeWith(textBlock("b1", author = "usr_mom")))
    setContent { MaterialTheme { HubDetailScreen(state, onDeleteBlock = { deleted = it }) } }
    onNodeWithContentDescription("More options").performClick()
    onNodeWithText("Delete").performClick()
    onNodeWithText("Keep it").performClick()
    assertNull(deleted)                                          // declined → nothing deleted
  }

  @Test fun nonAuthorSeesNoDeleteOption() = androidx.compose.ui.test.runComposeUiTest {
    val state = AppState(session = me, currentHubId = "h1", currentHubTree = treeWith(textBlock("b1", author = "usr_sam")))
    setContent { MaterialTheme { HubDetailScreen(state) } }
    onNodeWithContentDescription("More options").performClick()
    onNodeWithText("Hide for me").assertIsDisplayed()            // hide is for everyone
    onNodeWithText("Delete").assertDoesNotExist()               // delete is absent (not disabled) for non-authors
  }

  @Test fun aBlockWithNoAuthorIsNotDeletable() = androidx.compose.ui.test.runComposeUiTest {
    val state = AppState(session = me, currentHubId = "h1", currentHubTree = treeWith(textBlock("b1", author = null)))
    setContent { MaterialTheme { HubDetailScreen(state) } }
    onNodeWithContentDescription("More options").performClick()
    onNodeWithText("Delete").assertDoesNotExist()               // legacy / loop-authored → no member delete
  }

  @Test fun overflowHideReportsTheBlock() = androidx.compose.ui.test.runComposeUiTest {
    var hidden: String? = null
    val state = AppState(session = me, currentHubId = "h1", currentHubTree = treeWith(textBlock("b1", author = "usr_sam")))
    setContent { MaterialTheme { HubDetailScreen(state, onHideBlock = { hidden = it }) } }
    onNodeWithContentDescription("More options").performClick()
    onNodeWithText("Hide for me").performClick()
    assertEquals("b1", hidden)
  }

  @Test fun aHiddenBlockLeavesTheLiveSectionAndFoldsIntoHiddenForYou() = androidx.compose.ui.test.runComposeUiTest {
    val state = AppState(
      session = me, currentHubId = "h1", hiddenIds = setOf("b1"),
      currentHubTree = treeWith(textBlock("b1", author = "usr_sam").copy(bodyMd = "Sensitive note")),
    )
    setContent { MaterialTheme { HubDetailScreen(state) } }
    onNodeWithText("Sensitive note").assertDoesNotExist()        // not in the live view
    onNodeWithText("Hidden for you · 1").assertIsDisplayed()     // folded into the personal section
  }

  @Test fun showHiddenRevealsTheItemWithYouHidThisAndUnhide() = androidx.compose.ui.test.runComposeUiTest {
    var unhidden: String? = null
    val state = AppState(
      session = me, currentHubId = "h1", hiddenIds = setOf("b1"), showHidden = true,
      currentHubTree = treeWith(textBlock("b1", author = "usr_sam").copy(bodyMd = "Sensitive note")),
    )
    setContent { MaterialTheme { HubDetailScreen(state, onUnhideBlock = { unhidden = it }) } }
    onNodeWithText("You hid this").assertIsDisplayed()
    onNodeWithText("Unhide").performClick()
    assertEquals("b1", unhidden)
  }
}
