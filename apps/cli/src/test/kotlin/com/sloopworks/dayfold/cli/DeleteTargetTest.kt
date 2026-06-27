package com.sloopworks.dayfold.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// `delete <id> [--card]` removes a hub (default; the server cascades sections+blocks) or
// a card. Destructive, so the id must be resolved flag-position-agnostically — a `--card`
// before the id must NOT be mistaken for the id (which would target "/cards/--card").
class DeleteTargetTest {
  @Test fun `default deletes a hub, --card targets a card`() {
    assertEquals("hubs", deleteResource(arrayOf("delete", "h1")))
    assertEquals("cards", deleteResource(arrayOf("delete", "c1", "--card")))
    assertEquals("cards", deleteResource(arrayOf("rm", "--card", "c1")))   // flag-first
  }

  @Test fun `id is the first non-flag positional (flag may come before or after it)`() {
    assertEquals("h1", deleteId(arrayOf("delete", "h1")))
    assertEquals("c1", deleteId(arrayOf("rm", "c1", "--card")))
    assertEquals("c1", deleteId(arrayOf("delete", "--card", "c1")))   // was "--card" (footgun), now "c1"
  }

  @Test fun `no id yields null (caller falls to usage)`() {
    assertNull(deleteId(arrayOf("delete")))
    assertNull(deleteId(arrayOf("delete", "--card")))   // only a flag, no id
  }
}
