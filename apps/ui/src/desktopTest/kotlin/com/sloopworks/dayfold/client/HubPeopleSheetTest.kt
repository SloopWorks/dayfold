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
  // The locked "Owner" row is pinned via the server-computed isAuthor flag (DC5
  // code-review fix — apps/api/src/content/hubs.ts hubAudience() projects
  // `(m.user_id = h.created_by) AS is_author`), NOT "first co_owner in list order": a
  // non-author co_owner (added later via this very sheet) is a normal, editable/removable
  // row — see nonAuthorCoOwnerRowIsEditableAndRemovable below.
  private fun audience(vararg members: HubAudienceMember, visibility: String = "restricted") =
    HubAudience(visibility = visibility, members = members.toList(), canManage = true)

  private val owner = HubAudienceMember(uid = "u_owner", displayName = "Maya", role = "adult", permitted = true, participationRole = "co_owner", isAuthor = true)
  private val jordan = HubAudienceMember(uid = "u_jordan", displayName = "Jordan", role = "adult", permitted = true, participationRole = "viewer")
  // A non-author co-owner: carries the SAME participationRole="co_owner" as the true
  // author, but isAuthor=false — this is exactly the case the old "first co_owner"
  // heuristic could mislabel.
  private val riley = HubAudienceMember(uid = "u_riley", displayName = "Riley", role = "adult", permitted = true, participationRole = "co_owner", isAuthor = false)
  // A family member NOT on this restricted hub (permitted=false, no role) — the Add-people
  // picker offers exactly these.
  private val dana = HubAudienceMember(uid = "u_dana", displayName = "Dana", role = "adult", permitted = false, participationRole = null)

  @Test fun managerSeesRoleDropdownAndPickingContributorEmitsSetRole() = runComposeUiTest {
    var setRole: Pair<String, String>? = null
    setContent {
      MaterialTheme {
        HubPeopleSheet(
          audience = audience(owner, jordan),
          onSetRole = { uid, role -> setRole = uid to role },
          onRemove = {}, onSetVisibility = {}, onDismiss = {},
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
          onSetRole = { _, _ -> }, onRemove = {}, onSetVisibility = {}, onDismiss = {},
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
          onSetRole = { _, _ -> }, onRemove = { removed = it }, onSetVisibility = {}, onDismiss = {},
        )
      }
    }
    onNodeWithContentDescription("Remove Jordan").performClick()
    assertEquals("u_jordan", removed)
  }

  // Add-people picker: a not-yet-permitted family member (permitted=false) is offered under
  // "Add people"; tapping Add emits onSetRole(uid, "viewer") — HubEngine.setParticipant upserts.
  @Test fun addPeoplePickerAddsNonPermittedMemberAsViewer() = runComposeUiTest {
    var setRole: Pair<String, String>? = null
    setContent {
      MaterialTheme {
        HubPeopleSheet(
          audience = audience(owner, jordan, dana),   // dana: permitted=false, not on the hub
          onSetRole = { uid, role -> setRole = uid to role },
          onRemove = {}, onSetVisibility = {}, onDismiss = {},
        )
      }
    }
    onNodeWithText("Add people").performClick()          // open the picker
    onNodeWithContentDescription("Add Dana").performClick()
    assertEquals("u_dana" to "viewer", setRole)
  }

  // When every active member is already permitted (e.g. a family-visible hub), the picker
  // shows the empty state instead of a member list.
  @Test fun addPeoplePickerShowsEmptyStateWhenEveryoneAlreadyHasAccess() = runComposeUiTest {
    setContent {
      MaterialTheme {
        HubPeopleSheet(
          audience = audience(owner, jordan),           // both permitted → nobody to add
          onSetRole = { _, _ -> }, onRemove = {}, onSetVisibility = {}, onDismiss = {},
        )
      }
    }
    onNodeWithText("Add people").performClick()
    onNodeWithText("already has access", substring = true).assertIsDisplayed()
  }

  @Test fun flippingFamilyToRestrictedConfirmsBeforeCallingOnSetVisibility() = runComposeUiTest {
    var visibility: String? = null
    setContent {
      MaterialTheme {
        HubPeopleSheet(
          audience = audience(owner, jordan, visibility = "family"),
          onSetRole = { _, _ -> }, onRemove = {}, onSetVisibility = { visibility = it }, onDismiss = {},
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
          onSetRole = { _, _ -> }, onRemove = {}, onSetVisibility = { visibility = it }, onDismiss = {},
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
          onSetRole = { _, _ -> }, onRemove = {}, onSetVisibility = {}, onDismiss = {},
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
      hubs = HubState(audienceSheetOpen = true, currentAudience = HubAudience(
        visibility = "restricted",
        members = listOf(owner, jordan),
        canManage = false,
      )),
    )
    setContent { MaterialTheme { WhoCanSeeSheet(state) } }
    onNodeWithText("Maya").assertIsDisplayed()
    onNodeWithText("Jordan").assertIsDisplayed()
    onNodeWithTag("role-u_jordan").assertDoesNotExist()
    onNodeWithContentDescription("Remove Jordan").assertDoesNotExist()
  }

  // Code-review fix (owner-lock keys on isAuthor, not "first co_owner in list order"):
  // riley is a non-author co_owner LISTED BEFORE the true author (owner) — the old
  // heuristic would have pinned riley as the locked "Owner" and left the true author
  // editable/removable. isAuthor fixes this: riley stays a normal, editable/removable
  // participant row regardless of list position or matching participationRole.
  @Test fun nonAuthorCoOwnerRowIsEditableAndRemovableEvenWhenListedBeforeTheAuthor() = runComposeUiTest {
    var setRole: Pair<String, String>? = null
    var removed: String? = null
    setContent {
      MaterialTheme {
        HubPeopleSheet(
          audience = audience(riley, owner, jordan),   // riley (non-author co_owner) FIRST
          onSetRole = { uid, role -> setRole = uid to role },
          onRemove = { removed = it }, onSetVisibility = {}, onDismiss = {},
        )
      }
    }
    // The true author (owner) is the ONLY locked row — one "Owner" badge, on Maya.
    onNodeWithText("Owner").assertIsDisplayed()
    onNodeWithTag("role-u_owner").assertDoesNotExist()
    onNodeWithContentDescription("Remove Maya").assertDoesNotExist()
    // riley (non-author co_owner) is a normal participant row: role pill + remove both work.
    onNodeWithTag("role-u_riley").performClick()
    // pick Contributor (not Viewer — "Viewer" also appears as Jordan's role pill, which
    // would make the exact-text match ambiguous; the passing dropdown test picks the same way)
    onNodeWithText("Contributor").performClick()
    assertEquals("u_riley" to "contributor", setRole)
    onNodeWithContentDescription("Remove Riley").performClick()
    assertEquals("u_riley", removed)
  }

  // Code-review fix (write-failure surfacing): a failed role/remove/visibility op
  // dispatches HubManageFailed → state.hubs.hubs.audienceError, which HubsHost now threads into
  // HubPeopleSheet's errorMessage param — the manager must see it, not silence.
  @Test fun errorMessageRendersAnInlineBanner() = runComposeUiTest {
    setContent {
      MaterialTheme {
        HubPeopleSheet(
          audience = audience(owner, jordan),
          onSetRole = { _, _ -> }, onRemove = {}, onSetVisibility = {}, onDismiss = {},
          errorMessage = "Couldn't remove that person. Try again.",
        )
      }
    }
    onNodeWithText("Couldn't remove that person. Try again.").assertIsDisplayed()
  }

  @Test fun noErrorMessageRendersNoBanner() = runComposeUiTest {
    setContent {
      MaterialTheme {
        HubPeopleSheet(
          audience = audience(owner, jordan),
          onSetRole = { _, _ -> }, onRemove = {}, onSetVisibility = {}, onDismiss = {},
          errorMessage = null,
        )
      }
    }
    onNodeWithText("Couldn't remove that person. Try again.", substring = true).assertDoesNotExist()
  }
}
