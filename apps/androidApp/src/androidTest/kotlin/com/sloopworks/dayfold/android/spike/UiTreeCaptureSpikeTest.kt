package com.sloopworks.dayfold.android.spike

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.tooling.data.Group
import androidx.compose.ui.tooling.data.UiToolingDataApi
import androidx.compose.ui.tooling.data.asTree
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gate C capture-feasibility spike (review §6 Gate C). NOT shipped product
 * code — evidence for freezing the debugdrawer-compose-inspector design.
 *
 * Proves on a real Android build:
 *  1. bounds capture works WITHOUT source activation, and source
 *     name/file/line are absent until activation (BoundsOnly semantics);
 *  2. collectParameterInformation() + a completed recomposition yields
 *     composable name/file/line via ui-tooling-data (SourceOnDemand);
 *  3. a recorded-roots registry excludes separate-window (Popup) content;
 *  4. tree snapshot + synchronous window draw form an aligned pair while a
 *     target moves every frame; async PixelCopy alignment is measured and
 *     logged for the coordinator design.
 */
@OptIn(UiToolingDataApi::class)
@RunWith(AndroidJUnit4::class)
class UiTreeCaptureSpikeTest {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  private data class CapturedNode(
    val name: String?,
    val file: String?,
    val line: Int?,
    val bounds: IntRect,
  )

  private fun newTables(): MutableSet<CompositionData> =
    Collections.newSetFromMap(WeakHashMap())

  /** Flattens every recorded composition into (name, file, line, bounds). */
  private fun capture(tables: Set<CompositionData>): List<CapturedNode> {
    val out = mutableListOf<CapturedNode>()
    fun walk(g: Group) {
      out.add(
        CapturedNode(
          name = g.name,
          file = g.location?.sourceFile,
          line = g.location?.lineNumber,
          bounds = g.box,
        ),
      )
      g.children.forEach { walk(it) }
    }
    for (data in tables.toList()) walk(data.asTree())
    return out
  }

  @Composable
  private fun SpikeProduct() {
    Box(Modifier.size(120.dp).testTag("spike-root")) {
      Box(Modifier.size(48.dp).background(Color.Blue).testTag("spike-child"))
    }
  }

  @Composable
  private fun SpikeOverlay() {
    Box(Modifier.size(30.dp).background(Color.Green).testTag("spike-overlay"))
  }

  @Test
  fun boundsOnly_capturesGeometry_withoutSourceActivation() {
    val tables = newTables()
    rule.setContent {
      tables.add(currentComposer.compositionData)
      SpikeProduct()
    }
    rule.waitForIdle()
    var nodes: List<CapturedNode> = emptyList()
    rule.runOnUiThread { nodes = capture(tables) }

    assertTrue(
      nodes.any { it.bounds.width > 0 && it.bounds.height > 0 },
      "expected at least one node with nonzero bounds; got ${nodes.size} nodes",
    )
    assertTrue(
      nodes.none { it.file != null || it.line != null },
      "expected NO source locations before activation; got " +
        nodes.filter { it.file != null }.take(3),
    )
  }

  @Test
  fun sourceOnDemand_activationYieldsNameFileLine() {
    val tables = newTables()
    var collect by mutableStateOf(false)
    rule.setContent {
      if (collect) {
        currentComposer.collectParameterInformation()
      }
      tables.add(currentComposer.compositionData)
      // key() forces a full teardown/recompose of the subtree when activation
      // flips, modeling SourceOnDemand's "wait for a completed recomposition".
      key(collect) { SpikeProduct() }
    }
    rule.waitForIdle()

    val before = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    rule.runOnUiThread { collect = true }
    rule.waitForIdle()
    val after = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    var nodes: List<CapturedNode> = emptyList()
    rule.runOnUiThread { nodes = capture(tables) }

    val located = nodes.filter { it.file?.endsWith(".kt") == true && (it.line ?: 0) > 0 }
    assertTrue(
      located.isNotEmpty(),
      "expected source file+line after activation; nodes=${nodes.size}",
    )
    assertTrue(
      nodes.any { it.name == "SpikeProduct" },
      "expected composable name SpikeProduct; names=" +
        nodes.mapNotNull { it.name }.distinct().take(12),
    )
    println(
      "[spike] sourceOnDemand: ${located.size}/${nodes.size} nodes located; " +
        "heap delta ≈ ${(after - before) / 1024} KiB (coarse, one-way)",
    )
  }

  @Test
  fun recordedRootsRegistry_excludesSeparateWindowContent() {
    val tables = newTables()
    rule.setContent {
      currentComposer.collectParameterInformation()
      tables.add(currentComposer.compositionData)
      SpikeProduct()
      // Popup composes into a separate window + composition whose table we
      // deliberately do NOT record — the tooling/separate-window exclusion.
      Popup { SpikeOverlay() }
    }
    rule.waitForIdle()
    var nodes: List<CapturedNode> = emptyList()
    rule.runOnUiThread { nodes = capture(tables) }

    assertTrue(nodes.any { it.name == "SpikeProduct" }, "product content must be captured")
    assertTrue(
      nodes.none { it.name == "SpikeOverlay" },
      "separate-window content leaked into the recorded registry",
    )
  }

  @Test
  fun pairedCapture_synchronousDraw_alignsWhileTargetMoves() {
    val tables = newTables()
    var x by mutableIntStateOf(0)
    rule.mainClock.autoAdvance = false
    rule.setContent {
      currentComposer.collectParameterInformation()
      tables.add(currentComposer.compositionData)
      val pos by remember { derivedStateOfSafe { x } }
      Box(Modifier.fillMaxSize().background(Color.White).testTag("spike-bg")) {
        Box(
          Modifier
            .offset { IntOffset(pos, 320) }
            .size(80.dp)
            .background(Color.Red)
            .testTag("spike-mover"),
        )
      }
    }

    var syncMismatches = 0
    var pixelCopyMismatches = 0
    val iterations = 12
    repeat(iterations) { i ->
      rule.runOnUiThread { x = 16 * (i + 1) }
      rule.mainClock.advanceTimeByFrame()
      rule.waitForIdle()

      // (a) Atomic pair: tree snapshot + synchronous window draw in ONE
      // main-thread runnable — no frame boundary can interleave.
      var aligned = false
      rule.runOnUiThread {
        val nodes = capture(tables)
        val mover = moverBounds(nodes) ?: return@runOnUiThread
        val decor = rule.activity.window.decorView
        if (decor.width == 0 || decor.height == 0) return@runOnUiThread
        val bmp = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bmp))
        aligned = sampleAligned(bmp, mover)
        bmp.recycle()
      }
      if (!aligned) syncMismatches++

      // (b) Async PixelCopy after the tree snapshot — measured, not asserted.
      var treeAtCopy: IntRect? = null
      rule.runOnUiThread { treeAtCopy = moverBounds(capture(tables)) }
      val mover = treeAtCopy
      if (mover != null) {
        val decor = rule.activity.window.decorView
        if (decor.width > 0 && decor.height > 0) {
          val bmp = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
          val latch = CountDownLatch(1)
          var copyOk = false
          PixelCopy.request(
            rule.activity.window,
            Rect(0, 0, decor.width, decor.height),
            bmp,
            { result ->
              copyOk = result == PixelCopy.SUCCESS
              latch.countDown()
            },
            Handler(Looper.getMainLooper()),
          )
          // advance the virtual clock so the copy callback can land
          repeat(5) { rule.mainClock.advanceTimeByFrame() }
          latch.await(2, TimeUnit.SECONDS)
          if (copyOk && !sampleAligned(bmp, mover)) pixelCopyMismatches++
          bmp.recycle()
        }
      }
    }

    println(
      "[spike] paired capture over $iterations moving frames: " +
        "syncDraw mismatches=$syncMismatches, pixelCopy mismatches=$pixelCopyMismatches",
    )
    assertTrue(
      syncMismatches == 0,
      "synchronous decor draw must always align with the tree snapshot " +
        "(got $syncMismatches/$iterations mismatches)",
    )
  }

  /** Largest red-ish node ≈ the mover (source names may be absent). */
  private fun moverBounds(nodes: List<CapturedNode>): IntRect? =
    nodes
      .filter { it.bounds.width in 100..400 && it.bounds.height in 100..400 }
      .maxByOrNull { it.bounds.width * it.bounds.height }
      ?.bounds

  private fun sampleAligned(bmp: Bitmap, r: IntRect): Boolean {
    val cx = (r.left + r.right) / 2
    val cy = (r.top + r.bottom) / 2
    if (cx !in 0 until bmp.width || cy !in 0 until bmp.height) return false
    val center = bmp.getPixel(cx, cy)
    val outsideX = (r.right + 60).coerceAtMost(bmp.width - 1)
    val outside = bmp.getPixel(outsideX, cy)
    return isRed(center) && !isRed(outside)
  }

  private fun isRed(argb: Int): Boolean {
    val rC = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return rC > 180 && g < 90 && b < 90
  }
}

/** Tiny helper so the moving offset reads from one state object. */
private fun <T> derivedStateOfSafe(calc: () -> T) = androidx.compose.runtime.derivedStateOf(calc)

/** kotlin.test-style argument order over JUnit's assert. */
private fun assertTrue(condition: Boolean, message: String) {
  org.junit.Assert.assertTrue(message, condition)
}
