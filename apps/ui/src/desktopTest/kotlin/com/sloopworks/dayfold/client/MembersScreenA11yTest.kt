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
}
