package com.sloopworks.dayfold.cli

import kotlin.test.Test
import kotlin.test.assertTrue
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.BinaryBitmap
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.common.BitMatrix
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.DecodeHintType
import com.google.zxing.BarcodeFormat

class QrTest {
  private val sample = "https://family-ai-dashboard.vercel.app/device?user_code=WDJF-7K2P"

  @Test fun `encode produces a non-trivial square matrix`() {
    val m = Qr.encode(sample)
    assertTrue(m.width >= 21 && m.width == m.height, "expected a square QR matrix, got ${m.width}x${m.height}")
  }

  @Test fun `the encoded matrix round-trips through a QR decoder`() {
    val m = Qr.encode(sample, margin = 4)
    val decoded = decode(m)
    assertTrue(decoded == sample, "decoded payload mismatch: $decoded")
  }

  @Test fun `render emits half-block rows with ANSI resets`() {
    val out = Qr.render(sample)
    assertTrue(out.contains('▀'), "expected half-block glyphs")
    assertTrue(out.contains("[0m"), "expected ANSI reset per row")
    assertTrue(out.lines().count { it.isNotEmpty() } >= 10, "expected multiple rows")
  }

  // Rasterize the BitMatrix to an ARGB buffer (scaled up so zxing's detector can
  // locate the finder patterns) and decode it, proving the encoder output is a
  // real, scannable QR (not just non-empty).
  private fun decode(m: BitMatrix, scale: Int = 8): String {
    val w = m.width * scale; val h = m.height * scale
    val px = IntArray(w * h) { i ->
      val mx = (i % w) / scale; val my = (i / w) / scale
      if (m.get(mx, my)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    val src = RGBLuminanceSource(w, h, px)
    val bmp = BinaryBitmap(HybridBinarizer(src))
    val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
    return QRCodeReader().decode(bmp, hints).text
  }
}
