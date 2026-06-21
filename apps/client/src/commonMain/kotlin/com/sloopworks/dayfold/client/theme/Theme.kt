package com.sloopworks.dayfold.client.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

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
      typography = DayfoldTypography,
      content = content,
    )
  }
}
