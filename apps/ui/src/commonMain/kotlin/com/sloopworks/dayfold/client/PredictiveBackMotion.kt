package com.sloopworks.dayfold.client

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

// ADR 0022 emphasized-decelerate — the settle curve played on commit/cancel (the
// tail animation; the drag itself tracks the finger via SeekableTransitionState).
val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

// Standard decelerate (≈ PathInterpolator(0,0,0,1)). Google: feed the predictive-back
// preview a decelerate curve, never raw-linear progress, so motion is more apparent
// at gesture start.
private val Decelerate: Easing = CubicBezierEasing(0f, 0f, 0f, 1f)

fun decelerateProgress(raw: Float): Float = Decelerate.transform(raw.coerceIn(0f, 1f))
