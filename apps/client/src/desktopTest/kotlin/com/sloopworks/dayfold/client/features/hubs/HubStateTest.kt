package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

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

  @Test fun `unrelated content transition preserves hub slice identity`() {
    val state = AppState(hubs = HubState(hubs = listOf(Hub(id = "hub_1", title = "Hub"))))

    assertSame(state.hubs, rootReducer(state, SyncStarted).hubs)
  }

  @Test fun `account reset clears the complete hub projection`() {
    val state = AppState(hubs = HubState(filter = "mine", currentHubId = "hub_1", hiddenIds = setOf("hub_2")))

    assertEquals(HubState(), rootReducer(state, SignedOut).hubs)
  }
}
