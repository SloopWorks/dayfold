package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals

class SelectorsTest {
  private val now = "2026-06-24T12:00:00Z"

  @Test fun `feed hides expired cards, keeps live, undated, and fail-open on bad data`() {
    val state = AppState(content = ContentState(cards = listOf(
      Card("expired", title = "Old RSVP", expiresAt = "2026-06-23 12:00:00Z"),   // past → hidden
      Card("live", title = "Soon", expiresAt = "2026-06-25 12:00:00Z"),          // future → shown
      Card("undated", title = "Evergreen"),                                       // null → shown
      Card("badexp", title = "Bad", expiresAt = "not-a-date"),                    // unparseable → shown (fail open)
    )))
    val ids = feedCards(state, now).map { it.id }
    assertEquals(false, ids.contains("expired"))
    assertEquals(listOf("badexp", "live", "undated"), ids)   // expired dropped; rest by id (no not_before)
  }

  @Test fun `unparseable now keeps every card (never hide on a broken clock)`() {
    val state = AppState(content = ContentState(cards = listOf(Card("x", title = "X", expiresAt = "2000-01-01 00:00:00Z"))))
    assertEquals(listOf("x"), feedCards(state, "not-a-time").map { it.id })
  }

  @Test fun `not_before still orders nulls-last then by value`() {
    val state = AppState(content = ContentState(cards = listOf(
      Card("z", title = "no time"),
      Card("b", title = "later", notBefore = "2026-06-24T16:00:00Z"),
      Card("a", title = "earlier", notBefore = "2026-06-24T15:30:00Z"),
    )))
    assertEquals(listOf("a", "b", "z"), feedCards(state, now).map { it.id })
  }
}
