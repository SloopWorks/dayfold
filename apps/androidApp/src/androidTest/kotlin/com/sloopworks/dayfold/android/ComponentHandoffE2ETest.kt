package com.sloopworks.dayfold.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sloopworks.debugdrawer.compose.inspector.ComposeUiTreeInspector
import com.sloopworks.debugdrawer.uitree.ComponentSelection
import com.sloopworks.debugdrawer.uitree.FrozenComponentCapture
import com.sloopworks.debugdrawer.uitree.UiTreeCaptureMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import works.sloop.swip.bugreport.DraftReports
import works.sloop.swip.bugreport.model.PartKind
import works.sloop.swip.bugreport.model.ReportType
import works.sloop.swip.bugreport.model.UiTreeEncoder
import works.sloop.swip.bugreport.ui.model.with

/**
 * Gate G end-to-end: real inspector capture → typed SWIP part → preseeded
 * Bug-only draft, with the selected path intact and consent coupling
 * (screenshot toggle removes tree + annotations + selection together).
 */
@RunWith(AndroidJUnit4::class)
class ComponentHandoffE2ETest {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  @Composable
  private fun Product() {
    Box(Modifier.size(200.dp).testTag("outer")) {
      Box(Modifier.size(80.dp).testTag("inner")) {
        Text("hello")
      }
    }
  }

  private fun captureViaInspector(): FrozenComponentCapture {
    val inspector = ComposeUiTreeInspector()
    rule.setContent { inspector.RecordProductContent { Product() } }
    rule.waitForIdle()
    var capture: FrozenComponentCapture? = null
    runBlocking { capture = inspector.capture(UiTreeCaptureMode.SourceOnDemand) }
    rule.waitForIdle()
    if (capture == null) {
      runBlocking { capture = inspector.capture(UiTreeCaptureMode.SourceOnDemand) }
    }
    return requireNotNull(capture) { "inspector returned no capture" }
  }

  @Test
  fun frozenCapture_becomesPreseededBugDraft_withSelectedPathAndConsentCoupling() {
    val capture = captureViaInspector()
    val target = capture.nodes.last { it.bounds.width > 0 && it.bounds.height > 0 }
    val selection = ComponentSelection(
      nodeId = target.id,
      hitPath = listOf(target.id),
      bounds = target.bounds,
    )

    val bundle = ComponentsHandoffGlue.buildBundle(capture, selection, screenshot = null)
    val draft = DraftReports.forPreseeded(bundle)

    // Bug-only preseeded draft
    assertTrue(draft.preseeded)
    assertEquals(ReportType.BUG, draft.manifest.type)
    assertEquals("component_picker", draft.manifest.trigger)

    // typed UI-tree part round-trips with the selection + ancestor path intact
    val treePart = draft.parts.first { it.kind == PartKind.UI_TREE }
    val decoded = assertNotNull(UiTreeEncoder.decode(treePart.bytes))
    assertEquals(target.id, decoded.selected)
    assertTrue(decoded.nodes.isNotEmpty())
    assertTrue(UiTreeEncoder.ancestorPath(decoded, target.id).isNotEmpty())

    // preseeded annotation carries the node id (never an anonymous rectangle)
    val annotations = draft.parts.first { it.kind == PartKind.ANNOTATIONS }.bytes.decodeToString()
    assertTrue(annotations.contains("\"node_id\":\"${target.id}\""))

    // reselection is atomic in the persisted part
    val other = capture.nodes.first { it.id != target.id }
    draft.updateUiTreeSelection(other.id)
    val reDecoded = assertNotNull(
      UiTreeEncoder.decode(draft.parts.first { it.kind == PartKind.UI_TREE }.bytes),
    )
    assertEquals(other.id, reDecoded.selected)

    // consent: tree + annotations ride the screenshot toggle
    draft.toggles = draft.toggles.with(PartKind.UI_TREE, false)
    assertTrue(!draft.toggles.screenshot)
    draft.toggles = draft.toggles.with(PartKind.ANNOTATIONS, true)
    assertTrue(draft.toggles.screenshot)
  }

  private fun <T> assertNotNull(value: T?): T {
    org.junit.Assert.assertNotNull(value)
    return value!!
  }
}
