package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PredictiveBackMotionTest {
  @Test fun `decelerateProgress pins the endpoints`() {
    assertEquals(0f, decelerateProgress(0f), 0.0001f)
    assertEquals(1f, decelerateProgress(1f), 0.0001f)
  }

  @Test fun `decelerateProgress clamps out-of-range input`() {
    assertEquals(0f, decelerateProgress(-0.5f), 0.0001f)
    assertEquals(1f, decelerateProgress(1.7f), 0.0001f)
  }

  @Test fun `decelerateProgress is monotonic non-decreasing`() {
    var prev = -1f
    var x = 0f
    while (x <= 1f) {
      val y = decelerateProgress(x)
      assertTrue(y >= prev - 0.0001f, "non-monotonic at x=$x (y=$y, prev=$prev)")
      prev = y; x += 0.05f
    }
  }

  @Test fun `decelerate front-loads motion (faster near the start than linear)`() {
    // a decelerate curve is above the y=x line in the first half
    assertTrue(decelerateProgress(0.25f) > 0.25f, "expected front-loaded motion at 0.25")
  }
}
