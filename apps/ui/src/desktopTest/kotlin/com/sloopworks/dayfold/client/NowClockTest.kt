package com.sloopworks.dayfold.client

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class NowClockTest {

  @Test fun `live Now clock advances without a store update`() = runComposeUiTest {
    mainClock.autoAdvance = false
    var supplied = Instant.parse("2026-08-28T12:00:00Z")
    var rendered: Instant? = null
    setContent {
      rendered = rememberNowClock(enabled = true, nowProvider = { supplied }, tickMillis = 1_000L)
    }
    waitForIdle()
    assertEquals(Instant.parse("2026-08-28T12:00:00Z"), rendered)

    supplied = Instant.parse("2026-08-28T12:01:00Z")
    mainClock.advanceTimeBy(1_001L)
    waitForIdle()

    assertEquals(Instant.parse("2026-08-28T12:01:00Z"), rendered)
  }

  @Test fun `pinned Now clock stays fixed for deterministic renders`() = runComposeUiTest {
    mainClock.autoAdvance = false
    var supplied = Instant.parse("2026-08-28T12:00:00Z")
    var rendered: Instant? = null
    setContent {
      rendered = rememberNowClock(enabled = false, nowProvider = { supplied }, tickMillis = 1_000L)
    }
    waitForIdle()

    supplied = Instant.parse("2026-08-28T12:01:00Z")
    mainClock.advanceTimeBy(1_001L)
    waitForIdle()

    assertEquals(Instant.parse("2026-08-28T12:00:00Z"), rendered)
  }
}
