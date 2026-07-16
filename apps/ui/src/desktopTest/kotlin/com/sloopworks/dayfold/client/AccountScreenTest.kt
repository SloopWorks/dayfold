package com.sloopworks.dayfold.client

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Profile name edit — the pencil affordance on the account profile card opens a dialog;
// Save emits the trimmed name to onUpdateName (→ AuthEngine.updateDisplayName). Pure
// state-in/callback-out (no store/engine), mirrors HubPeopleSheetTest.
@OptIn(ExperimentalTestApi::class)
class AccountScreenTest {
  @Test fun editingNameAndSavingEmitsTrimmedName() = runComposeUiTest {
    var saved: String? = null
    setContent {
      MaterialTheme {
        AccountScreen(state = AppState(profile = ProfileState(displayName = "Leo")), onUpdateName = { saved = it })
      }
    }
    onNodeWithContentDescription("Edit name").performClick()   // open the dialog (prefilled "Leo")
    onNodeWithTag("name-field").performTextClearance()
    onNodeWithTag("name-field").performTextInput("  Zoe  ")     // leading/trailing space → trimmed
    onNodeWithTag("save-name").performClick()
    assertEquals("Zoe", saved)
  }

  @Test fun saveIsDisabledForABlankName() = runComposeUiTest {
    setContent {
      MaterialTheme {
        AccountScreen(state = AppState(profile = ProfileState(displayName = "Leo")), onUpdateName = {})
      }
    }
    onNodeWithContentDescription("Edit name").performClick()
    onNodeWithTag("name-field").performTextClearance()          // empty → invalid
    onNodeWithTag("save-name").assertIsNotEnabled()
  }
}
