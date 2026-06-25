package com.sloopworks.dayfold.cli

import kotlin.test.Test
import kotlin.test.assertEquals

// `push` targets a briefing card by default, or a Hub with --hub → the PUT path's
// /families/:fid/<resource>/:id segment.
class PushResourceTest {
  @Test fun `default push targets cards`() {
    assertEquals("cards", pushResource(arrayOf("push", "c1", "card.json")))
    assertEquals("cards", pushResource(arrayOf("push", "c1", "card.json", "--type", "invite")))
  }

  @Test fun `--hub targets hubs (flag is position-agnostic)`() {
    assertEquals("hubs", pushResource(arrayOf("push", "h1", "hub.json", "--hub")))
    assertEquals("hubs", pushResource(arrayOf("push", "--hub", "h1", "hub.json")))
  }
}
