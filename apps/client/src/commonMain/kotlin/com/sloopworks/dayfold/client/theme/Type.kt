package com.sloopworks.dayfold.client.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.sp

// Dayfold type scale (designs/Brand.dc.html): Outfit (display/headline/title,
// weight 600, tight negative tracking) + Figtree (body/label). The SIZES,
// WEIGHTS and TRACKING below are brand-accurate now; the actual Outfit/Figtree
// typefaces are bundled in a follow (CL-0b — needs composeResources/font/),
// so both families currently resolve to FontFamily.Default. Swap the two
// params below to the bundled families and the whole scale inherits them.
private val Outfit: FontFamily = FontFamily.Default // TODO(CL-0b): bundle Outfit
private val Figtree: FontFamily = FontFamily.Default // TODO(CL-0b): bundle Figtree

private fun em(value: Float) = TextUnit(value, TextUnitType.Em)

fun dayfoldTypography(display: FontFamily, body: FontFamily): Typography {
  val base = Typography()
  return base.copy(
    displaySmall = base.displaySmall.copy(
      fontFamily = display, fontWeight = FontWeight.SemiBold,
      fontSize = 36.sp, letterSpacing = em(-0.025f),
    ),
    headlineSmall = base.headlineSmall.copy(
      fontFamily = display, fontWeight = FontWeight.SemiBold,
      fontSize = 24.sp, letterSpacing = em(-0.015f),
    ),
    titleLarge = base.titleLarge.copy(
      fontFamily = display, fontWeight = FontWeight.SemiBold,
      fontSize = 22.sp, letterSpacing = em(-0.02f),
    ),
    titleMedium = base.titleMedium.copy(
      fontFamily = display, fontWeight = FontWeight.SemiBold,
      fontSize = 17.sp, letterSpacing = em(-0.01f),
    ),
    bodyLarge = base.bodyLarge.copy(
      fontFamily = body, fontWeight = FontWeight.Normal, fontSize = 16.sp,
    ),
    bodyMedium = base.bodyMedium.copy(
      fontFamily = body, fontWeight = FontWeight.Normal, fontSize = 14.sp,
    ),
    labelLarge = base.labelLarge.copy(
      fontFamily = body, fontWeight = FontWeight.SemiBold,
    ),
    // Kicker / eyebrow — Figtree 600, positive tracking, UPPERCASE at call sites.
    labelSmall = base.labelSmall.copy(
      fontFamily = body, fontWeight = FontWeight.SemiBold,
      fontSize = 11.5f.sp, letterSpacing = em(0.06f),
    ),
  )
}

val DayfoldTypography: Typography = dayfoldTypography(Outfit, Figtree)
