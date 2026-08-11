package com.sloopworks.dayfold.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import com.sloopworks.debugdrawer.uitree.ComponentSelection
import com.sloopworks.debugdrawer.uitree.FrozenComponentCapture
import java.io.ByteArrayOutputStream
import works.sloop.swip.bugreport.DraftReports
import works.sloop.swip.bugreport.model.BugReportManifest
import works.sloop.swip.bugreport.model.CaptureBundle
import works.sloop.swip.bugreport.model.ContextBlock
import works.sloop.swip.bugreport.model.Part
import works.sloop.swip.bugreport.model.PartKind
import works.sloop.swip.bugreport.model.ReportType
import works.sloop.swip.bugreport.model.UiTreeBounds
import works.sloop.swip.bugreport.model.UiTreeEncoder
import works.sloop.swip.bugreport.model.UiTreeNode
import works.sloop.swip.bugreport.model.UiTreePart
import works.sloop.swip.bugreport.model.UiTreeSource
import works.sloop.swip.bugreport.model.UiTreeViewport
import works.sloop.swip.bugreport.ui.annotate.AnnotationLayer
import works.sloop.swip.bugreport.ui.annotate.Highlight
import works.sloop.swip.bugreport.ui.annotate.NormRect
import works.sloop.swip.bugreport.model.SwipJson

/**
 * Drawer → SWIP preseeded handoff (Gate G / review §4.4 entry point 2 in
 * reverse): the frozen component capture becomes a Bug-only preseeded draft —
 * typed UI-tree part (SWIP-owned encoder; dayfold never authors raw JSON),
 * screenshot from a synchronous decor draw (capture-consistency mechanism
 * proven by the Gate C spike), and a preseeded component highlight carrying
 * the node id. Consent: all of it rides the screenshot toggle.
 */
object ComponentsHandoffGlue {

  fun openPreseededComponentReport(
    capture: FrozenComponentCapture,
    selection: ComponentSelection,
  ) {
    val controller = BugReporterGlue.controllerOrNull() ?: return
    val bundle = buildBundle(capture, selection, screenshotPng())
    controller.openPreseeded(DraftReports.forPreseeded(bundle))
  }

  internal fun buildBundle(
    capture: FrozenComponentCapture,
    selection: ComponentSelection,
    screenshot: ByteArray?,
  ): CaptureBundle {
    val tree = capture.toUiTreePart(selection)
    val parts = buildList {
      if (screenshot != null) add(Part(PartKind.SCREENSHOT, "image/png", screenshot))
      add(Part(PartKind.UI_TREE, "application/json", UiTreeEncoder.encode(tree)))
      add(Part(PartKind.ANNOTATIONS, "application/json", preseededHighlight(capture, selection)))
    }
    return CaptureBundle(manifest(hasScreenshot = screenshot != null), parts)
  }

  /** Allowlisted typed projection — the SWIP model cannot express text/state. */
  internal fun FrozenComponentCapture.toUiTreePart(selection: ComponentSelection): UiTreePart =
    UiTreePart(
      buildRef = buildRef?.buildId ?: "dayfold-android-debug-unregistered",
      viewport = UiTreeViewport(viewportWidth, viewportHeight),
      selected = selection.nodeId,
      nodes = nodes.map { n ->
        UiTreeNode(
          id = n.id,
          parentId = n.parentId,
          composeGroupKey = n.composeGroupKey,
          name = n.name,
          bounds = UiTreeBounds(n.bounds.left, n.bounds.top, n.bounds.right, n.bounds.bottom),
          source = n.source?.let { s ->
            UiTreeSource(
              displayFile = s.displayFile,
              repoPath = s.repositoryRelativePath,
              line = s.line,
              function = s.function,
              packageHash = s.packageHash,
              sourceOffset = s.sourceOffset,
            )
          },
        )
      },
    )

  private fun preseededHighlight(
    capture: FrozenComponentCapture,
    selection: ComponentSelection,
  ): ByteArray {
    val w = capture.viewportWidth.coerceAtLeast(1).toFloat()
    val h = capture.viewportHeight.coerceAtLeast(1).toFloat()
    val layer = AnnotationLayer(
      highlights = listOf(
        Highlight(
          rect = NormRect(
            (selection.bounds.left / w).coerceIn(0f, 1f),
            (selection.bounds.top / h).coerceIn(0f, 1f),
            (selection.bounds.right / w).coerceIn(0f, 1f),
            (selection.bounds.bottom / h).coerceIn(0f, 1f),
          ),
          nodeId = selection.nodeId,
        ),
      ),
    )
    return SwipJson.encodeToString(AnnotationLayer.serializer(), layer).encodeToByteArray()
  }

  /** Synchronous decor draw on the main thread (Gate C pairing mechanism). */
  private fun screenshotPng(): ByteArray? {
    val decor = BugReporterGlue.currentActivityOrNull()?.window?.decorView ?: return null
    if (decor.width <= 0 || decor.height <= 0) return null
    return try {
      val bmp = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
      decor.draw(Canvas(bmp))
      val out = ByteArrayOutputStream()
      bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
      bmp.recycle()
      out.toByteArray()
    } catch (t: Throwable) {
      null
    }
  }

  private fun manifest(hasScreenshot: Boolean): BugReportManifest = BugReportManifest(
    reportId = "rpt_cmp_${System.currentTimeMillis()}",
    type = ReportType.BUG,
    trigger = "component_picker",
    description = "",
    context = ContextBlock(
      appVersion = BuildConfig.VERSION_NAME,
      osName = "android",
      osVersion = Build.VERSION.SDK_INT.toString(),
      device = Build.MODEL,
      locale = java.util.Locale.getDefault().toLanguageTag(),
      channel = "debug",
    ),
    hasScreenshot = hasScreenshot,
    hasTimeline = false,
    hasLogs = false,
    hasBreadcrumbs = false,
  )
}
