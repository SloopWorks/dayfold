package com.sloopworks.dayfold.client

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith

// Motion tokens — the single home for durations + easings across the app's navigation
// AND the hero container transforms (Feed↔Detail, Hub↔Timeline read HeroMs in Task 6).
object NavMotion {
  const val StandardMs = 400
  const val HeroMs = 460
  const val FastMs = 250
  const val ReducedMs = 0
  const val FadeThroughMs = 90   // fade-through cross-fade: outgoing duration + incoming delay

  // Material 3 Expressive easings.
  val Emphasized: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
  val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
  val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)
}

// Build the enter/exit pair for a resolved NavAnim. slidePx = axis travel in px
// (host passes ~30dp worth). Incoming uses decelerate (settles in); outgoing accelerates out.
fun NavAnim.toContentTransform(slidePx: Int): ContentTransform {
  val enterMs = NavMotion.StandardMs
  val dec = NavMotion.EmphasizedDecelerate
  val acc = NavMotion.EmphasizedAccelerate
  return when (this) {
    NavAnim.SharedXForward ->
      (slideInHorizontally(tween(enterMs, easing = dec)) { slidePx } + fadeIn(tween(enterMs))) togetherWith
        (slideOutHorizontally(tween(enterMs, easing = acc)) { -slidePx } + fadeOut(tween(enterMs)))
    NavAnim.SharedXBackward ->
      (slideInHorizontally(tween(enterMs, easing = dec)) { -slidePx } + fadeIn(tween(enterMs))) togetherWith
        (slideOutHorizontally(tween(enterMs, easing = acc)) { slidePx } + fadeOut(tween(enterMs)))
    NavAnim.SharedZForward ->
      (scaleIn(tween(enterMs, easing = dec), initialScale = 0.85f) + fadeIn(tween(enterMs))) togetherWith
        (scaleOut(tween(enterMs, easing = acc), targetScale = 1.1f) + fadeOut(tween(enterMs)))
    NavAnim.SharedZBackward ->
      (scaleIn(tween(enterMs, easing = dec), initialScale = 1.1f) + fadeIn(tween(enterMs))) togetherWith
        (scaleOut(tween(enterMs, easing = acc), targetScale = 0.85f) + fadeOut(tween(enterMs)))
    NavAnim.ModalEnter ->
      (slideInVertically(tween(enterMs, easing = dec)) { it } + fadeIn(tween(enterMs))) togetherWith
        fadeOut(tween(enterMs))
    NavAnim.ModalExit ->
      fadeIn(tween(enterMs)) togetherWith
        (slideOutVertically(tween(enterMs, easing = acc)) { it } + fadeOut(tween(enterMs)))
    NavAnim.FadeThrough ->
      fadeIn(tween(enterMs, delayMillis = NavMotion.FadeThroughMs)) togetherWith fadeOut(tween(NavMotion.FadeThroughMs))
    NavAnim.Snap ->
      EnterTransition.None togetherWith ExitTransition.None
  }
}
