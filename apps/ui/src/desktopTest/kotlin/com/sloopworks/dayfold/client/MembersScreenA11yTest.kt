package com.sloopworks.dayfold.client

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

// The pending-approval row's actions are ✓/✗ glyphs — a reader must hear distinct,
// name-bearing labels (approving vs declining a member is consequential).
@OptIn(ExperimentalTestApi::class)
class MembersScreenA11yTest {
  @Test fun approveAndDeclineExposeDistinctAccessibleLabels() = runComposeUiTest {
    val state = AppState(pendingApprovals = listOf(PendingMember("u9", "Sam Rivera")))
    setContent { MaterialTheme { MembersScreen(state) } }
    onNodeWithContentDescription("Approve Sam Rivera").assertIsDisplayed()
    onNodeWithContentDescription("Decline Sam Rivera").assertIsDisplayed()
  }

  // Wiring (not just labels): each glyph must invoke its callback for THAT member.
  // decline-<uid> was an orphaned tag — declining a join request is the deny side of
  // membership and was untested (only approve was, via the full flow).
  @Test fun approveAndDeclineInvokeTheirCallbacksForThatMember() = runComposeUiTest {
    var approved: String? = null
    var declined: String? = null
    val state = AppState(pendingApprovals = listOf(PendingMember("u9", "Sam Rivera")))
    setContent { MaterialTheme { MembersScreen(state, onApprove = { approved = it }, onDecline = { declined = it }) } }
    onNodeWithTag("decline-u9").performClick()
    assertEquals("u9", declined)   // deny side wired to the right member
    onNodeWithTag("approve-u9").performClick()
    assertEquals("u9", approved)
  }

  // Profile avatars (P1b Task 3): a roster member carrying a fun avatarRef renders the
  // fun avatar (a11y name from FunAvatars), not the monogram fallback.
  @Test fun memberWithFunAvatarShowsIt() = runComposeUiTest {
    val state = AppState(members = listOf(FamilyMember(uid = "u1", displayName = "Fiona Fox", avatarRef = "avatar:fox-01")))
    setContent { MaterialTheme { MembersScreen(state) } }
    onNodeWithContentDescription("Fox avatar").assertIsDisplayed()
  }

  // Same for a pending (awaiting-approval) member.
  @Test fun pendingMemberWithFunAvatarShowsIt() = runComposeUiTest {
    val state = AppState(pendingApprovals = listOf(PendingMember(uid = "u9", displayName = "Sam Rivera", avatarRef = "avatar:sun-01")))
    setContent { MaterialTheme { MembersScreen(state) } }
    onNodeWithContentDescription("Sun avatar").assertIsDisplayed()
  }
}
