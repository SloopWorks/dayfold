package com.sloopworks.dayfold.client

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test

// Task 4 — cross-platform QR render smoke: qrose builds a matrix + painter and the
// Image composes without throwing (the real value is that this compiles + links on
// iosArm64/desktop — the dependency gate; see build.gradle qrose 1.1.2).
@OptIn(ExperimentalTestApi::class)
class QrImageTest {
  @Test fun `renders the invite QR without crashing`() = runComposeUiTest {
    setContent { QrImage("https://x/invite/TOK_ONETIME", Modifier.size(160.dp), Color.Black, Color.White) }
    waitForIdle()
    onNodeWithContentDescription("Invite QR code").assertExists()
  }
}
