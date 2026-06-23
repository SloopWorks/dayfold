package com.sloopworks.debugdrawer.persistence

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DebugStoreTest {
  private val store = PreferencesDebugStore("com/sloopworks/debugdrawer/test")

  @AfterTest fun cleanup() {
    store.remove("k"); store.remove(DebugKeys.BACKEND_OVERRIDE)
  }

  @Test fun missing_key_is_null() {
    assertNull(store.get("never-set-key"))
  }

  @Test fun put_then_get_round_trips() {
    store.put("k", "v")
    assertEquals("v", store.get("k"))
  }

  @Test fun remove_clears() {
    store.put(DebugKeys.BACKEND_OVERRIDE, "staging")
    assertEquals("staging", store.get(DebugKeys.BACKEND_OVERRIDE))
    store.remove(DebugKeys.BACKEND_OVERRIDE)
    assertNull(store.get(DebugKeys.BACKEND_OVERRIDE))
  }
}
