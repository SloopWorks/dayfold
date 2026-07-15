package com.sloopworks.dayfold.android

import org.junit.Assert.assertEquals
import org.junit.Test

class DebugLeakRegressionTest {
  private class Host

  @Test fun weakSecureWindowStopsTouchingAReleasedHost() {
    val host = Host()
    var sets = 0
    var clears = 0
    val secure = SwipInspectorGlue.WeakHostSecureWindow(
      host = host,
      setSecure = { sets += 1 },
      clearSecure = { clears += 1 },
    )

    secure.set()
    secure.clear()
    secure.release()
    secure.set()
    secure.clear()

    assertEquals(1, sets)
    assertEquals(1, clears)
  }
}
