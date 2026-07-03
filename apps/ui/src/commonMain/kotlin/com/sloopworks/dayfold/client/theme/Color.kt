package com.sloopworks.dayfold.client.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Dayfold brand palette (ADR 0022 D5) — exact tokens from designs/Brand.dc.html.
// Warm terracotta/coral primary, teal secondary, violet tertiary, on a warm
// off-white/brown neutral ramp. Light is the hero; dark is first-class.
// (Real Outfit/Figtree typefaces + the MaterialExpressiveTheme/MotionScheme
//  upgrade are deferred follows — see Type.kt / Theme.kt.)

val DayfoldLightColors = lightColorScheme(
  primary = Color(0xFFC0381E),
  onPrimary = Color(0xFFFFFFFF),
  primaryContainer = Color(0xFFFFDAD2),
  onPrimaryContainer = Color(0xFF5A1100),
  secondary = Color(0xFF00796F),
  onSecondary = Color(0xFFFFFFFF),
  secondaryContainer = Color(0xFF9DF2E4),
  onSecondaryContainer = Color(0xFF00201C),
  tertiary = Color(0xFF6438AE),
  onTertiary = Color(0xFFFFFFFF),
  tertiaryContainer = Color(0xFFECDCFF),
  onTertiaryContainer = Color(0xFF23005C),
  background = Color(0xFFFFF8F6),
  onBackground = Color(0xFF271814),
  surface = Color(0xFFFFF8F6),
  onSurface = Color(0xFF271814),
  onSurfaceVariant = Color(0xFF5A423C),
  surfaceContainerLowest = Color(0xFFFFFFFF),
  surfaceContainerLow = Color(0xFFFFF1EC),
  surfaceContainer = Color(0xFFFCEBE6),
  surfaceContainerHigh = Color(0xFFF6E5E0),
  surfaceContainerHighest = Color(0xFFF0DED8),
  outline = Color(0xFF8C726B),
  outlineVariant = Color(0xFFEBD3CB),
)

val DayfoldDarkColors = darkColorScheme(
  primary = Color(0xFFFFB4A3),
  onPrimary = Color(0xFF5F1500),
  primaryContainer = Color(0xFF9A2A12),
  onPrimaryContainer = Color(0xFFFFDAD2),
  secondary = Color(0xFF50DBC9),
  onSecondary = Color(0xFF00382F),
  secondaryContainer = Color(0xFF005048),
  onSecondaryContainer = Color(0xFFA8F4E7),
  tertiary = Color(0xFFD3BBFF),
  onTertiary = Color(0xFF38067D),
  tertiaryContainer = Color(0xFF4C2092),
  onTertiaryContainer = Color(0xFFECDCFF),
  background = Color(0xFF1A110E),
  onBackground = Color(0xFFF0DFDA),
  surface = Color(0xFF1A110E),
  onSurface = Color(0xFFF0DFDA),
  onSurfaceVariant = Color(0xFFD8C2BB),
  surfaceContainerLowest = Color(0xFF140B08),
  surfaceContainerLow = Color(0xFF211512),
  surfaceContainer = Color(0xFF271D1A),
  surfaceContainerHigh = Color(0xFF322824),
  surfaceContainerHighest = Color(0xFF3D332E),
  outline = Color(0xFFA08D87),
  outlineVariant = Color(0xFF3D332E),
)

// Custom Dayfold roles the M3 ColorScheme has no slot for — the on-device
// privacy chip (honesty posture, ADR 0014/0015), the source/provider chip, and
// the stylized map strip. Surfaced via [LocalDayfoldColors] (set in DayfoldTheme).
data class DayfoldExtendedColors(
  val privacyContainer: Color,
  val onPrivacyContainer: Color,
  val providerChip: Color,
  val onProviderChip: Color,
  val providerChipOutline: Color,
  val mapBackground: Color,
  val mapLine: Color,
  val mapRoad: Color,
)

val DayfoldLightExtended = DayfoldExtendedColors(
  privacyContainer = Color(0xFFE2F4EF),
  onPrivacyContainer = Color(0xFF0B5048),
  providerChip = Color(0xFFF4ECFB),
  onProviderChip = Color(0xFF4C2092),
  providerChipOutline = Color(0xFFE0CDF5),
  mapBackground = Color(0xFFEAE0DB),
  mapLine = Color(0xFFDBCBC4),
  mapRoad = Color(0xFFCDB9B1),
)

val DayfoldDarkExtended = DayfoldExtendedColors(
  privacyContainer = Color(0xFF005048),
  onPrivacyContainer = Color(0xFFA8F4E7),
  providerChip = Color(0xFF2A1F3D),
  onProviderChip = Color(0xFFD3BBFF),
  providerChipOutline = Color(0xFF4C2092),
  mapBackground = Color(0xFF231A16),
  mapLine = Color(0xFF322824),
  mapRoad = Color(0xFF3D332E),
)

// Read with LocalDayfoldColors.current inside DayfoldTheme; defaults to light.
val LocalDayfoldColors = staticCompositionLocalOf { DayfoldLightExtended }
