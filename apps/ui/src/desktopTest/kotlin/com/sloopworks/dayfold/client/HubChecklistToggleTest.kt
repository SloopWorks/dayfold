package com.sloopworks.dayfold.client

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Slice 4 (ADR 0038 §4) — the tappable checklist row wires the real toggle→enqueue seam.
// A tap reports (blockId, itemId, newDoneValue) to the screen's onToggleItem callback,
// which the shell routes to HubEngine.toggleItem → ContentStore.enqueueBlockToggle.
@OptIn(ExperimentalTestApi::class)
class HubChecklistToggleTest {
  private fun treeWith(item: ChecklistItem) = HubTree(
    hub = Hub(id = "h1", title = "Party", status = "active", visibility = "family"),
    sections = listOf(HubSection(id = "s1", hubId = "h1", title = "Plan", ord = 0)),
    blocks = listOf(HubBlock(id = "b_chk", sectionId = "s1", type = "checklist", ord = 0,
      payload = BlockPayload(items = listOf(item)))),
  )

  @Test fun tappingAnUndoneRowReportsAToggleToDone() = runComposeUiTest {
    var toggled: Triple<String, String, Boolean>? = null
    val state = AppState(currentHubId = "h1",
      currentHubTree = treeWith(ChecklistItem(id = "i1", text = "Buy balloons", done = false)))
    setContent { MaterialTheme { HubDetailScreen(state, onToggleItem = { b, i, d -> toggled = Triple(b, i, d) }) } }
    onNodeWithText("Buy balloons").performClick()
    assertEquals(Triple("b_chk", "i1", true), toggled)
  }

  @Test fun tappingADoneRowReportsAToggleToUndone() = runComposeUiTest {
    var toggled: Triple<String, String, Boolean>? = null
    val state = AppState(currentHubId = "h1",
      currentHubTree = treeWith(ChecklistItem(id = "i1", text = "Order cake", done = true)))
    // a done item with an empty burst is folded — expand the "1 done" section, then tap it
    setContent { MaterialTheme { HubDetailScreen(state, onToggleItem = { b, i, d -> toggled = Triple(b, i, d) }) } }
    onNodeWithText("1 done").performClick()                  // expand the folded section
    onNodeWithText("Order cake").performClick()
    assertEquals(Triple("b_chk", "i1", false), toggled)
  }

  @Test fun anItemWithoutAnIdIsNotTappable() = runComposeUiTest {
    var toggled: Triple<String, String, Boolean>? = null
    val state = AppState(currentHubId = "h1",
      currentHubTree = treeWith(ChecklistItem(id = null, text = "Legacy item", done = false)))
    setContent { MaterialTheme { HubDetailScreen(state, onToggleItem = { b, i, d -> toggled = Triple(b, i, d) }) } }
    onNodeWithText("Legacy item").assertIsDisplayed()        // still renders
    onNodeWithText("Legacy item").performClick()
    assertNull(toggled)                                       // but no toggle fires (can't merge without an id)
  }

  @Test fun aFailedBlockShowsACalmRetryThatReportsTheBlock() = runComposeUiTest {
    var retried: String? = null
    val state = AppState(currentHubId = "h1",
      currentHubTree = treeWith(ChecklistItem(id = "i1", text = "Buy balloons", done = false))
        .let { it.copy(blocks = it.blocks.map { b -> b.copy(localState = "failed") }) })
    setContent { MaterialTheme { HubDetailScreen(state, onRetryBlock = { retried = it }) } }
    onNodeWithText("Couldn't save", substring = true).assertIsDisplayed()
    onNodeWithText("Retry").performClick()
    assertEquals("b_chk", retried)
  }
}
