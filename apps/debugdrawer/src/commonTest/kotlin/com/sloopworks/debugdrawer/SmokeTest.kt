package com.sloopworks.debugdrawer

import kotlin.test.Test
import kotlin.test.assertEquals

class SmokeTest {
  @Test
  fun backendUrl_returns_default_when_no_override() {
    assertEquals("https://api.example", DebugDrawer.backendUrl("https://api.example"))
  }
}
