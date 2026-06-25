package com.sloopworks.dayfold.client

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

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
}
