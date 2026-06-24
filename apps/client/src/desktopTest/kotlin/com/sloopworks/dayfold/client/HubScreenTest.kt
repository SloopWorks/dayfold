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

  @Test fun detailRendersRichBlockTypes() = runComposeUiTest {
    val tree = HubTree(
      hub = Hub(id = "h1", title = "Party", status = "active", visibility = "family"),
      sections = listOf(HubSection(id = "s1", hubId = "h1", title = "Plan", ord = 0)),
      blocks = listOf(
        HubBlock(id = "b_chk", sectionId = "s1", type = "checklist", ord = 0,
          payload = BlockPayload(items = listOf(
            ChecklistItem(text = "Sheet cake", done = false, due = "11am"),
            ChecklistItem(text = "Candles", done = true)))),
        HubBlock(id = "b_link", sectionId = "s1", type = "link", ord = 1,
          payload = BlockPayload(label = "Party playlist", url = "open.spotify.com")),
        HubBlock(id = "b_con", sectionId = "s1", type = "contact", ord = 2,
          payload = BlockPayload(name = "Jake Rentals", role = "Bouncy castle", phone = "555-0100")),
        HubBlock(id = "b_loc", sectionId = "s1", type = "location", ord = 3,
          payload = BlockPayload(label = "Riverside Park", address = "Shelter B")),
        HubBlock(id = "b_bud", sectionId = "s1", type = "budget", ord = 4,
          payload = BlockPayload(total = 300.0, spent = 248.0)),
      ),
    )
    val state = AppState(currentHubId = "h1", currentHubTree = tree)
    setContent { MaterialTheme { HubDetailScreen(state) } }
    onNodeWithText("Sheet cake").assertIsDisplayed()            // checklist row
    onNodeWithText("Party playlist").assertIsDisplayed()        // link
    onNodeWithText("Jake Rentals").assertIsDisplayed()          // contact
    onNodeWithText("Riverside Park").assertIsDisplayed()        // location
    onNodeWithText("${'$'}52 left").assertIsDisplayed()         // budget bar (300-248)
  }

  @Test fun whoCanSeeSheetRendersRosterWithPermittedFlags() = runComposeUiTest {
    val state = AppState(
      audienceSheetOpen = true,
      currentHubAudience = HubAudience(visibility = "restricted", members = listOf(
        HubAudienceMember(uid = "u1", displayName = "Pat", role = "owner", permitted = true),
        HubAudienceMember(uid = "u2", displayName = "Jordan", role = "adult", permitted = false),
      )),
    )
    setContent { MaterialTheme { WhoCanSeeSheet(state) } }
    onNodeWithText("Who can see this hub").assertIsDisplayed()
    onNodeWithText("Pat").assertIsDisplayed()
    onNodeWithText("Jordan").assertIsDisplayed()
    onNodeWithText("The family owner isn't added automatically.", substring = true).assertIsDisplayed()
  }

  @Test fun detailShowsNotFoundNoteOnRestrictedMiss() = runComposeUiTest {
    val state = AppState(currentHubId = "hX", currentHubTree = null, hubError = "That hub is no longer available.")
    setContent { MaterialTheme { HubDetailScreen(state) } }
    onNodeWithText("That hub is no longer available.").assertIsDisplayed()
  }
}
