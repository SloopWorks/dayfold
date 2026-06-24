package com.sloopworks.dayfold.client

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class HubScreenTest {
  @Test fun listRendersHubsAndMarksRestricted() = runComposeUiTest {
    val state = AppState(hubs = listOf(
      Hub(id = "h1", type = "party-event", title = "Maya's birthday", status = "active", visibility = "family"),
      Hub(id = "h2", type = "medical", title = "Dad's surgery", status = "active", visibility = "restricted"),
    ))
    setContent { MaterialTheme { HubListScreen(state) } }
    onNodeWithText("Maya's birthday").assertIsDisplayed()
    onNodeWithText("Dad's surgery").assertIsDisplayed()
    onNodeWithText("Private").assertIsDisplayed()                 // ADR 0030 restricted marker
  }

  @Test fun detailRendersBlocksAndVisibilityChip() = runComposeUiTest {
    val tree = HubTree(
      hub = Hub(id = "h2", title = "Dad's surgery", status = "active", visibility = "restricted"),
      sections = listOf(HubSection(id = "s1", hubId = "h2", title = "Overview", ord = 0)),
      blocks = listOf(HubBlock(id = "b1", sectionId = "s1", type = "text", bodyMd = "Discharge Thursday", ord = 0)),
    )
    val state = AppState(currentHubId = "h2", currentHubTree = tree)
    setContent { MaterialTheme { HubDetailScreen(state) } }
    onNodeWithText("Dad's surgery").assertIsDisplayed()           // title
    onNodeWithText("Discharge Thursday").assertIsDisplayed()      // text block
    onNodeWithText("Only people you choose can open this hub.").assertIsDisplayed()  // honesty line
  }

  @Test fun detailShowsNotFoundNoteOnRestrictedMiss() = runComposeUiTest {
    val state = AppState(currentHubId = "hX", currentHubTree = null, hubError = "That hub is no longer available.")
    setContent { MaterialTheme { HubDetailScreen(state) } }
    onNodeWithText("That hub is no longer available.").assertIsDisplayed()
  }
}
