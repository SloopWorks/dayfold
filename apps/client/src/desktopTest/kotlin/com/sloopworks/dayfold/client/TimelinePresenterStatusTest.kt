package com.sloopworks.dayfold.client
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class TimelinePresenterStatusTest {
  private val ny = TimeZone.of("America/New_York")
  private fun s(at: String, done: Boolean = false) = Stop(at = at, title = at, done = done)
  @Test fun `past is done, first future is next, rest upcoming`() {
    val stops = listOf(s("2026-08-24T08:00:00-04:00"), s("2026-08-24T09:50:00-04:00"),
                       s("2026-08-24T11:00:00-04:00"), s("2026-08-24T14:00:00-04:00"))
    val out = stopStatuses(stops, "2026-08-24T10:40:00-04:00", ny)
    assertEquals(listOf(StopStatus.Done, StopStatus.Done, StopStatus.Next, StopStatus.Upcoming), out.map { it.status })
  }
  @Test fun `author done overrides even a future date`() {
    assertEquals(StopStatus.Done, stopStatuses(listOf(s("2026-12-01", done = true)), "2026-08-24T10:00:00-04:00", ny).first().status)
  }
  @Test fun `not-today makes everything upcoming, no next-before-now`() {
    val out = stopStatuses(listOf(s("2026-09-19"), s("2026-09-20")), "2026-08-24T10:00:00-04:00", ny)
    assertEquals(listOf(StopStatus.Next, StopStatus.Upcoming), out.map { it.status })
  }
}
