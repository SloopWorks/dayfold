package com.sloopworks.dayfold.client

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

// Standard decelerate (≈ PathInterpolator(0,0,0,1)). Google: feed the predictive-back
// preview a decelerate curve, never raw-linear progress, so motion is more apparent
// at gesture start.
private val Decelerate: Easing = CubicBezierEasing(0f, 0f, 0f, 1f)

fun decelerateProgress(raw: Float): Float = Decelerate.transform(raw.coerceIn(0f, 1f))
