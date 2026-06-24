package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HubDateTest {
  // DB-shaped timestamptz → ISO the parser accepts
  @Test fun normalizesDbTimestamps() {
    assertEquals("2026-06-24T07:23:51.41-07:00", normalizeTs("2026-06-24 07:23:51.41-07"))
    assertEquals("2026-06-24T07:23:51+05:30", normalizeTs("2026-06-24 07:23:51+0530"))
    assertEquals("2026-06-24T07:23:51Z", normalizeTs("2026-06-24 07:23:51Z"))
    assertNull(normalizeTs(null))
    assertNull(normalizeTs("  "))
  }

  private val now = "2026-06-24T12:00:00Z"
  @Test fun countdownReadsAcrossTheRange() {
    assertEquals("in 12 days", countdownLabel("2026-07-06 12:00:00Z", now))
    assertEquals("Tomorrow", countdownLabel("2026-06-25 13:00:00Z", now))
    assertEquals("Today", countdownLabel("2026-06-24 18:00:00Z", now))
    assertEquals("Yesterday", countdownLabel("2026-06-23 06:00:00Z", now))
    assertEquals("3 days ago", countdownLabel("2026-06-21 06:00:00Z", now))
    assertNull(countdownLabel(null, now))           // no date → no badge
    assertNull(countdownLabel("not-a-date", now))   // junk → no crash, no badge
  }
}
