package com.sloopworks.dayfold.client

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.sloopworks.dayfold.client.cards.PlatformUriHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// End-to-end proof of the PR1 handoff fix: an inline [label](url) link, when
// tapped, routes through the provided LocalUriHandler (PlatformUriHandler) — NOT
// Compose's default system handler. Covers render → LinkAnnotation.Url → Compose
// dispatch → our handler → openUri callback.
@OptIn(ExperimentalTestApi::class)
class InlineLinkHandoffTest {
  @Test fun inlineLinkTapRoutesThroughLocalUriHandler() = runComposeUiTest {
    var opened: String? = null
    setContent {
      CompositionLocalProvider(LocalUriHandler provides PlatformUriHandler { opened = it }) {
        Text(renderBlockMarkdown("[call](tel:+15551234567)"))
      }
    }
    onNodeWithText("call").performClick()
    assertEquals("tel:+15551234567", opened) // routed to our handler, not the system one
  }

  @Test fun disallowedSchemeIsNotEvenALink() = runComposeUiTest {
    var opened: String? = null
    setContent {
      CompositionLocalProvider(LocalUriHandler provides PlatformUriHandler { opened = it }) {
        Text(renderBlockMarkdown("[x](javascript:boom)"))
      }
    }
    // render-time allowlist degraded it to plain text → no link, tap routes nowhere
    onNodeWithText("x").performClick()
    assertNull(opened)
  }
}
