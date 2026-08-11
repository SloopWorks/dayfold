package com.sloopworks.dayfold.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sloopworks.dayfold.client.createAppStore
import com.sloopworks.dayfold.client.theme.DayfoldTheme
import com.sloopworks.debugdrawer.components.ComponentHitTesting
import com.sloopworks.debugdrawer.compose.inspector.ComposeUiTreeInspector
import com.sloopworks.debugdrawer.uitree.FrozenComponentCapture
import com.sloopworks.debugdrawer.uitree.UiTreeCaptureMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.reduxkotlin.concurrent.NotificationContext

/**
 * Captures against the REAL dayfold feed UI (TestFeedApp) — the probe that
 * the toy-tree suites missed twice: it asserts the capture is rich (many
 * nodes), has small drawn leaves, named nodes, and that a hit path in the
 * content area anchors at something far smaller than the screen.
 */
@RunWith(AndroidJUnit4::class)
class ComponentCaptureRealUiProbeTest {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  private fun realUiCapture(): FrozenComponentCapture {
    val store = createAppStore(
      notificationContext = NotificationContext.Inline,
      debug = false,
    )
    val inspector = ComposeUiTreeInspector()
    rule.setContent {
      DayfoldTheme {
        inspector.RecordProductContent { TestFeedApp(store) }
      }
    }
    rule.waitForIdle()
    var capture: FrozenComponentCapture? = null
    runBlocking { capture = inspector.capture(UiTreeCaptureMode.SourceOnDemand) }
    rule.waitForIdle()
    if (capture == null) {
      runBlocking { capture = inspector.capture(UiTreeCaptureMode.SourceOnDemand) }
    }
    return requireNotNull(capture) { "no capture from real UI" }
  }

  @Test
  fun realFeedUi_capture_isRich_andHitPathAnchorsBelowScreenSize() {
    val capture = realUiCapture()
    val selectable = ComponentHitTesting.candidates(capture)
    println(
      "[probe] nodes=${capture.nodes.size} selectable=${selectable.size} " +
        "named=${capture.nodes.count { it.name != null }} " +
        "sourced=${capture.nodes.count { it.source != null }} " +
        "viewport=${capture.viewportWidth}x${capture.viewportHeight}",
    )
    assertTrue(
      "capture too thin (${capture.nodes.size} nodes) — descendant " +
        "subcompositions are not being recorded",
      capture.nodes.size >= 30,
    )
    assertTrue("no named nodes recorded", capture.nodes.any { it.name != null })

    val screenArea = capture.viewportWidth.toLong() * capture.viewportHeight.toLong()
    selectable.sortedBy { it.bounds.area }.take(8).forEach {
      println("[probe] smallest: ${it.name} ${it.bounds} src=${it.source?.displayFile}")
    }
    val distinct = selectable.map { it.bounds }.distinct().size
    println("[probe] distinct selectable bounds: $distinct of ${selectable.size}")
    val smallLeaves = selectable.count { it.bounds.area < screenArea / 8 }
    assertTrue("no small drawn leaves in capture", smallLeaves > 0)

    // probe the CENTERS of the smallest drawn components: tapping a visible
    // component must anchor the path at that component, not the screen
    val targets = selectable
      .filter { it.bounds.area < screenArea / 8 }
      .distinctBy { it.bounds }
      .sortedBy { it.bounds.area }
      .take(3)
    assertTrue("no sub-screen components to probe", targets.isNotEmpty())
    for (target in targets) {
      val cx = (target.bounds.left + target.bounds.right) / 2
      val cy = (target.bounds.top + target.bounds.bottom) / 2
      val path = ComponentHitTesting.hitPath(capture, cx, cy)
      assertTrue("no path at ($cx,$cy) inside ${target.bounds}", path.isNotEmpty())
      val leaf = path[ComponentHitTesting.defaultTargetIndex(path)]
      println("[probe] ($cx,$cy) path=${path.size} leaf=${leaf.name} ${leaf.bounds}")
      assertTrue(
        "tap at ($cx,$cy) on ${target.name} ${target.bounds} anchored at " +
          "${leaf.name} ${leaf.bounds} — bigger than the tapped component",
        leaf.bounds.area <= target.bounds.area,
      )
      assertTrue("path must include ancestors to cycle through", path.size >= 2)
    }
  }
}
