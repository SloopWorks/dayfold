package com.sloopworks.dayfold.client

import androidx.compose.ui.test.*
import com.sloopworks.dayfold.client.theme.DayfoldTheme
import kotlin.test.Test
import kotlin.test.assertEquals

// P1a Task 5 — the avatar picker sheet (Monogram · Fun avatars · Photo-disabled), driven
// purely by its own callback signature (no store coupling) so it's unit-testable in
// isolation; AccountScreen wires onSave to AuthEngine.updateAvatar.
class AvatarPickerSheetTest {
  @OptIn(ExperimentalTestApi::class)
  @Test fun pickingFunAvatarAndSavingEmitsItsId() = runComposeUiTest {
    var saved: Pair<String?, String?>? = null
    setContent { DayfoldTheme { AvatarPickerSheet(null, null, { c, r -> saved = c to r }, {}) } }
    onNodeWithText("Fun avatars").performClick()
    onNodeWithContentDescription("Fox avatar").performClick()
    onNodeWithText("Save").performClick()
    assertEquals("avatar:fox-01", saved?.second)
  }
  @OptIn(ExperimentalTestApi::class)
  @Test fun photoTabIsDisabled() = runComposeUiTest {
    setContent { DayfoldTheme { AvatarPickerSheet(null, null, { _, _ -> }, {}) } }
    onNodeWithText("Photo").assertIsNotEnabled()
  }
}
