package com.sloopworks.dayfold.client.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.sloopworks.dayfold.client.generated.Res
import com.sloopworks.dayfold.client.generated.figtree
import com.sloopworks.dayfold.client.generated.outfit
import org.jetbrains.compose.resources.Font

// The single Dayfold theme entry point — every shell (android/desktop/iOS)
// renders through FeedApp, which wraps content in DayfoldTheme.
//
// CL-0 ships the brand ColorScheme + Shapes + type scale via the STANDARD
// MaterialTheme (zero dependency risk, fully verifiable). The expressive
// upgrade — MaterialExpressiveTheme + MotionScheme.expressive() — is the only
// change that lands here later (one function body), coupled to CL-7 where the
// motion is actually used and gated on confirming the material3-expressive
// artifact at Compose-MP 1.9.3. Android dynamic color (dynamicColorScheme,
// androidMain) is likewise a follow; the Dayfold scheme is the cross-platform
// default and the dynamic-color fallback.
@Composable
fun DayfoldTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DayfoldDarkColors else DayfoldLightColors
  val extended = if (darkTheme) DayfoldDarkExtended else DayfoldLightExtended
  CompositionLocalProvider(LocalDayfoldColors provides extended) {
    MaterialTheme(
      colorScheme = colorScheme,
      shapes = DayfoldShapes,
      typography = brandTypography(),
      content = content,
    )
  }
}

// CL-0b: the brand type scale (DayfoldTypography) rendered in the BUNDLED Outfit +
// Figtree typefaces. The fonts are variable .ttf in commonMain/composeResources/font/;
// Compose maps each declared FontWeight to the wght axis, so one file per family
// covers the scale's weights. Built here (not as a top-level val) because Font(Res…)
// is @Composable. dayfoldTypography keeps the sizes/weights/tracking (DayfoldThemeTest).
@Composable
private fun brandTypography(): Typography {
  val outfit = FontFamily(   // display/headline/title — 600/700
    Font(Res.font.outfit, FontWeight.SemiBold),
    Font(Res.font.outfit, FontWeight.Bold),
  )
  val figtree = FontFamily(  // body/label — 400/500/600/700
    Font(Res.font.figtree, FontWeight.Normal),
    Font(Res.font.figtree, FontWeight.Medium),
    Font(Res.font.figtree, FontWeight.SemiBold),
    Font(Res.font.figtree, FontWeight.Bold),
  )
  return dayfoldTypography(display = outfit, body = figtree)
}
