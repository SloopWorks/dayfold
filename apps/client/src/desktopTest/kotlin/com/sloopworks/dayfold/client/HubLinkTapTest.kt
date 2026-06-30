package com.sloopworks.dayfold.client

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Regression — a link block in a Hub must be TAPPABLE (it wasn't: LinkRow showed an "opens externally"
 * arrow but had no click handler, and HubBlockCard was passed no open action). Tapping opens its URL via
 * the same installed LocalUriHandler (PlatformUriHandler → vetted PlatformActions.openUri) the inline
 * body-links already use. A document block with no openable URL stays inert.
 */
@OptIn(ExperimentalTestApi::class)
class HubLinkTapTest {
  private class FakeUriHandler : UriHandler {
    var opened: String? = null
    override fun openUri(uri: String) { opened = uri }
  }

  private fun treeWith(block: HubBlock) = HubTree(
    hub = Hub(id = "h1", title = "Party", status = "active", visibility = "family"),
    sections = listOf(HubSection(id = "s1", hubId = "h1", title = "Plan", ord = 0)),
    blocks = listOf(block),
  )

  @Test fun `tapping a link block opens its url`() = runComposeUiTest {
    val handler = FakeUriHandler()
    val tree = treeWith(HubBlock(id = "b_link", sectionId = "s1", type = "link", ord = 0,
      payload = BlockPayload(label = "Party playlist", domain = "open.spotify.com", url = "https://open.spotify.com/playlist/abc")))
    setContent {
      MaterialTheme {
        CompositionLocalProvider(LocalUriHandler provides handler) {
          HubDetailScreen(AppState(currentHubId = "h1", currentHubTree = tree))
        }
      }
    }
    onNodeWithText("Party playlist").performClick()
    assertEquals("https://open.spotify.com/playlist/abc", handler.opened)
  }

  @Test fun `tapping a document block opens its ref url`() = runComposeUiTest {
    // real document blocks keep the (https PDF) URL in `ref`, not `url` — the on-device bug.
    val handler = FakeUriHandler()
    val tree = treeWith(HubBlock(id = "b_doc", sectionId = "s1", type = "document", ord = 0,
      payload = BlockPayload(label = "Immunization Requirements", ref = "https://cdn.butler.edu/Immunization-Req.pdf")))
    setContent {
      MaterialTheme {
        CompositionLocalProvider(LocalUriHandler provides handler) {
          HubDetailScreen(AppState(currentHubId = "h1", currentHubTree = tree))
        }
      }
    }
    onNodeWithText("Immunization Requirements").performClick()
    assertEquals("https://cdn.butler.edu/Immunization-Req.pdf", handler.opened)
  }

  @Test fun `a document with a non-url ref stays inert`() = runComposeUiTest {
    val handler = FakeUriHandler()
    val tree = treeWith(HubBlock(id = "b_doc", sectionId = "s1", type = "document", ord = 0,
      payload = BlockPayload(label = "Permission slip", ref = "doc_123")))   // internal id, not https → inert
    setContent {
      MaterialTheme {
        CompositionLocalProvider(LocalUriHandler provides handler) {
          HubDetailScreen(AppState(currentHubId = "h1", currentHubTree = tree))
        }
      }
    }
    onNodeWithText("Permission slip").performClick()
    assertNull(handler.opened)
  }
}
