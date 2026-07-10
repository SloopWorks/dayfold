package com.sloopworks.dayfold.client

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// DC5 (ADR 0053) — the People management sheet. Owner/co-owner-only: role DropdownMenu +
// remove per participant, the pinned/locked author row, the Family/Restricted visibility
// toggle (with a who-loses-access confirm on the tightening direction), and Add people.
// Mirrors HubScreenTest's whoCanSeeSheetRenders* pattern (direct compose, no store/engine).
@OptIn(ExperimentalTestApi::class)
class HubPeopleSheetTest {
  // The API synthesizes participation_role="co_owner" for the implicit hub author too
  // (hubAudience() in apps/api/src/content/hubs.ts) — there is no separate isAuthor flag.
  // We pin the FIRST co_owner row in list order as the locked "Owner" row (documented
  // limitation: if a second, later co-owner ever sorted before the true author, this
  // sheet would mislabel it).
  private fun audience(vararg members: HubAudienceMember, visibility: String = "restricted") =
    HubAudience(visibility = visibility, members = members.toList(), canManage = true)

  private val owner = HubAudienceMember(uid = "u_owner", displayName = "Maya", role = "adult", permitted = true, participationRole = "co_owner")
  private val jordan = HubAudienceMember(uid = "u_jordan", displayName = "Jordan", role = "adult", permitted = true, participationRole = "viewer")

  @Test fun managerSeesRoleDropdownAndPickingContributorEmitsSetRole() = runComposeUiTest {
    var setRole: Pair<String, String>? = null
    setContent {
      MaterialTheme {
        HubPeopleSheet(
          audience = audience(owner, jordan),
          onSetRole = { uid, role -> setRole = uid to role },
          onRemove = {}, onSetVisibility = {}, onAddPeople = {}, onDismiss = {},
        )
      }
    }
    onNodeWithTag("role-u_jordan").performClick()          // opens the role DropdownMenu
    onNodeWithText("Contributor").performClick()            // pick Contributor
    assertEquals("u_jordan" to "contributor", setRole)
  }

  @Test fun ownerRowShowsBadgeAndNoRemoveOrDropdown() = runComposeUiTest {
    setContent {
      MaterialTheme {
        HubPeopleSheet(
          audience = audience(owner, jordan),
          onSetRole = { _, _ -> }, onRemove = {}, onSetVisibility = {}, onAddPeople = {}, onDismiss = {},
        )
      }
    }
    onNodeWithText("Owner").assertIsDisplayed()
    onNodeWithTag("role-u_owner").assertDoesNotExist()       // no role dropdown for the pinned owner
    onNodeWithContentDescription("Remove Maya").assertDoesNotExist()
    onNodeWithContentDescription("Remove Jordan").assertIsDisplayed()  // non-owner participant IS removable
  }

  @Test fun removingAParticipantEmitsOnRemove() = runComposeUiTest {
    var removed: String? = null
    setContent {
      MaterialTheme {
        HubPeopleSheet(
          audience = audience(owner, jordan),
          onSetRole = { _, _ -> }, onRemove = { removed = it }, onSetVisibility = {}, onAddPeople = {}, onDismiss = {},
        )
      }
    }
    onNodeWithContentDescription("Remove Jordan").performClick()
    assertEquals("u_jordan", removed)
  }

  @Test fun addPeopleAffordanceCallsOnAddPeople() = runComposeUiTest {
    var called = false
    setContent {
      MaterialTheme {
        HubPeopleSheet(
          audience = audience(owner, jordan),
          onSetRole = { _, _ -> }, onRemove = {}, onSetVisibility = {}, onAddPeople = { called = true }, onDismiss = {},
        )
      }
    }
    onNodeWithText("Add people").performClick()
    assertEquals(true, called)
  }

  @Test fun flippingFamilyToRestrictedConfirmsBeforeCallingOnSetVisibility() = runComposeUiTest {
    var visibility: String? = null
    setContent {
      MaterialTheme {
        HubPeopleSheet(
          audience = audience(owner, jordan, visibility = "family"),
          onSetRole = { _, _ -> }, onRemove = {}, onSetVisibility = { visibility = it }, onAddPeople = {}, onDismiss = {},
        )
      }
    }
    onNodeWithText("Restricted").performClick()
    assertNull(visibility)                                    // not applied yet — confirm pending
    onNodeWithText("Restrict this hub?").assertIsDisplayed()
    onNodeWithText("Restrict").performClick()
    assertEquals("restricted", visibility)
  }

  @Test fun flippingRestrictedToFamilyNeedsNoConfirm() = runComposeUiTest {
    var visibility: String? = null
    setContent {
      MaterialTheme {
        HubPeopleSheet(
          audience = audience(owner, jordan, visibility = "restricted"),
          onSetRole = { _, _ -> }, onRemove = {}, onSetVisibility = { visibility = it }, onAddPeople = {}, onDismiss = {},
        )
      }
    }
    onNodeWithText("Family").performClick()
    assertEquals("family", visibility)                        // applied immediately, no confirm
  }

  @Test fun inlineRoleExplainerLinesAreShown() = runComposeUiTest {
    setContent {
      MaterialTheme {
        HubPeopleSheet(
          audience = audience(owner, jordan),
          onSetRole = { _, _ -> }, onRemove = {}, onSetVisibility = {}, onAddPeople = {}, onDismiss = {},
        )
      }
    }
    // Substrings unique to the explainer sentence (role NAMES alone also appear in each
    // participant row's role pill, e.g. Jordan's "Viewer" — so match the explainer's phrasing).
    onNodeWithText("Viewer sees this hub", substring = true).assertIsDisplayed()
    onNodeWithText("Contributor can also add", substring = true).assertIsDisplayed()
    onNodeWithText("Co-owner can also manage", substring = true).assertIsDisplayed()
  }

  // Non-manager path is unchanged: WhoCanSeeSheet stays read-only — no role dropdown,
  // no remove — regardless of the audience's own canManage flag (HubsHost is what
  // decides which sheet to open; WhoCanSeeSheet itself never grows controls).
  @Test fun nonManagerAudienceRendersReadOnlyWhoCanSeeSheet() = runComposeUiTest {
    val state = AppState(
      audienceSheetOpen = true,
      currentHubAudience = HubAudience(
        visibility = "restricted",
        members = listOf(owner, jordan),
        canManage = false,
      ),
    )
    setContent { MaterialTheme { WhoCanSeeSheet(state) } }
    onNodeWithText("Maya").assertIsDisplayed()
    onNodeWithText("Jordan").assertIsDisplayed()
    onNodeWithTag("role-u_jordan").assertDoesNotExist()
    onNodeWithContentDescription("Remove Jordan").assertDoesNotExist()
  }
}
