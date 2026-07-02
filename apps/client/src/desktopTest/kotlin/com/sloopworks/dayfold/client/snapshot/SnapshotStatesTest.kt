package com.sloopworks.dayfold.client.snapshot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SnapshotStatesTest {
  @Test fun feedPresetsBuild() {
    assertEquals(6, SnapshotStates.feed("typed").cards.size)
    assertEquals(0, SnapshotStates.feed("empty").cards.size)
    assertNotNull(SnapshotStates.feed("busy"))
  }
  @Test fun hubAndDetailPresetsBuild() {
    assertEquals("sample", SnapshotStates.hubTree("canonical").hub.id)
    assertEquals("invite", SnapshotStates.detailCard("invite").type)
  }
}
