package com.sloopworks.dayfold.cli

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

// [S3] Render a QR of the device verification URL into the terminal so the owner
// can scan it with the app instead of typing the user_code. Encoding via zxing
// (hand-rolling ECC + masking is bug-prone). The text user_code/URI is ALWAYS
// printed alongside — terminals/SSH/CI that can't render the QR still work.
object Qr {
  private const val ESC = ""
  private const val UPPER_HALF = "▀" // ▀

  fun encode(text: String, margin: Int = 2): BitMatrix {
    val hints = mapOf(
      EncodeHintType.MARGIN to margin,
      EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
    )
    // width/height 0 → minimal one-module-per-cell matrix (incl. the quiet zone).
    return QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
  }

  // Two modules per text row via the upper-half-block glyph: foreground = the top
  // module, background = the bottom one. Dark module → black, light → white, so it
  // scans dark-on-light on any terminal theme (every cell sets an explicit bg, so
  // the quiet zone is real white space).
  fun render(text: String): String {
    val m = encode(text)
    val sb = StringBuilder()
    var y = 0
    while (y < m.height) {
      for (x in 0 until m.width) {
        val top = m.get(x, y)
        val bottom = y + 1 < m.height && m.get(x, y + 1)
        val fg = if (top) "30" else "37"     // dark module -> black foreground
        val bg = if (bottom) "40" else "47"  // dark module -> black background
        sb.append(ESC).append("[").append(fg).append(';').append(bg).append('m').append(UPPER_HALF)
      }
      sb.append(ESC).append("[0m\n")
      y += 2
    }
    return sb.toString()
  }
}
