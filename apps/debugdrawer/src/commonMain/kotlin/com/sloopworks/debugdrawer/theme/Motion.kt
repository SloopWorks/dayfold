package com.sloopworks.debugdrawer.theme

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Reduced-motion seam (R8). The host sets this from the OS preference (platform
 * expect/actual lands with the platform shells); when true, spring/slide
 * transitions collapse to instant cross-fades / snaps. Defaults to false so the
 * library is usable before a platform provides the real value.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }
