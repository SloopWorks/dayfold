package com.sloopworks.dayfold.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sloopworks.debugdrawer.compose.inspector.ComposeUiTreeInspector
import com.sloopworks.debugdrawer.uitree.FrozenComponentCapture
import com.sloopworks.debugdrawer.uitree.UiTreeCaptureMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gate G product leak test (review §7 Dayfold tests): salted visible text,
 * member-ish data, content descriptions, and test tags must NEVER appear in a
 * component capture — the tree carries structure + source identifiers only.
 */
@RunWith(AndroidJUnit4::class)
class ComponentCaptureLeakTest {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  private val salts = listOf(
    "SALT_CARD_TEXT_7f3a",
    "SALT_MEMBER_NAME_Priya_9b1c",
    "SALT_DESCRIPTION_2e8d",
    "SALT_TEST_TAG_5c4f",
  )

  @Composable
  private fun SaltedProduct() {
    Column(Modifier.testTag(salts[3])) {
      Text(salts[0])
      Text("chores for ${salts[1]}")
      Box(
        Modifier
          .size(40.dp)
          .semantics { contentDescription = salts[2] },
      )
    }
  }

  @Test
  fun capturedTree_containsNoSaltedTextDescriptionsOrTags() {
    val inspector = ComposeUiTreeInspector()
    rule.setContent {
      inspector.RecordProductContent { SaltedProduct() }
    }
    rule.waitForIdle()

    var capture: FrozenComponentCapture? = null
    runBlocking {
      capture = inspector.capture(UiTreeCaptureMode.SourceOnDemand)
    }
    rule.waitForIdle()
    if (capture == null) {
      runBlocking { capture = inspector.capture(UiTreeCaptureMode.SourceOnDemand) }
    }
    val got = capture
    assertTrue("expected a capture", got != null && got.nodes.isNotEmpty())

    val serialized = buildString {
      for (n in got!!.nodes) {
        append(n.id).append(n.parentId).append(n.name).append(n.composeGroupKey)
        append(n.bounds)
        n.source?.let {
          append(it.displayFile).append(it.repositoryRelativePath).append(it.line)
          append(it.function).append(it.packageHash).append(it.sourceOffset)
        }
      }
    }
    for (salt in salts) {
      assertTrue(
        "salted value leaked into the component capture: $salt",
        !serialized.contains(salt),
      )
    }
  }

  /**
   * Regression (2026-08-10 device finding): CompositionData is per-composition,
   * so tooling siblings in the SAME composition (drawer host, selection
   * overlay's full-screen interception Box) leaked into captures and every
   * hit path resolved to a full-screen node. RecordProductContent must give
   * product content its own subcomposition root table.
   */
  @Test
  fun sameComposition_toolingSiblings_areExcludedFromCapture() {
    val inspector = ComposeUiTreeInspector()
    rule.setContent {
      // full-screen "tooling" sibling in the same composition, composed AFTER
      // the product slot — mimics the selection overlay
      Box(Modifier.fillMaxSize().testTag("tooling-overlay"))
      inspector.RecordProductContent {
        Box(Modifier.size(120.dp).testTag("product"))
      }
    }
    rule.waitForIdle()
    var capture: FrozenComponentCapture? = null
    runBlocking { capture = inspector.capture(UiTreeCaptureMode.BoundsOnly) }
    val got = capture
    assertTrue("expected a capture", got != null && got.nodes.isNotEmpty())
    val maxDim = 3 * 120 * rule.activity.resources.displayMetrics.density
    val oversized = got!!.nodes.filter {
      it.bounds.width > maxDim || it.bounds.height > maxDim
    }
    assertTrue(
      "tooling-sized nodes leaked into the product capture: " +
        oversized.take(3).map { it.bounds },
      oversized.isEmpty(),
    )
  }
}
