package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class HubStateTest {
  @Test fun `default is an empty closed hub projection`() {
    val state = HubState()
    assertEquals(emptyList(), state.hubs)
    assertFalse(state.busy)
    assertEquals("all", state.filter)
    assertNull(state.currentHubId)
    assertNull(state.currentHubTree)
    assertNull(state.currentHubRequest)
    assertFalse(state.audienceSheetOpen)
  }
}
