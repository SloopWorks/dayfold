package com.sloopworks.debugdrawer

import com.sloopworks.debugdrawer.internal.DebugScopeImpl
import com.sloopworks.debugdrawer.internal.PluginRegistry
import com.sloopworks.debugdrawer.log.LogBuffer
import com.sloopworks.debugdrawer.log.LogLevel
import com.sloopworks.debugdrawer.persistence.DebugKeys
import com.sloopworks.debugdrawer.persistence.DebugStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class InMemoryStore : DebugStore {
  private val m = mutableMapOf<String, String>()
  override fun get(key: String) = m[key]
  override fun put(key: String, value: String) { m[key] = value }
  override fun remove(key: String) { m.remove(key) }
}

private class FakePlugin(override val id: String, override val title: String) : DebugPlugin {
  @androidx.compose.runtime.Composable override fun Content(scope: DebugScope) {}
}

class PluginScopeTest {

  @Test
  fun registry_preserves_insertion_order() {
    val reg = PluginRegistry(listOf(FakePlugin("a", "A"), FakePlugin("b", "B"), FakePlugin("c", "C")))
    assertEquals(listOf("a", "b", "c"), reg.plugins.map { it.id })
    assertEquals("B", reg.find("b")?.title)
    assertNull(reg.find("missing"))
  }

  @Test
  fun active_backend_falls_back_to_first_when_no_override() {
    val scope = DebugScopeImpl(InMemoryStore(), listOf(Backend("prod", "Prod", "A"), Backend("local", "Local", "B")), LogBuffer())
    assertEquals("prod", scope.activeBackendId())
  }

  @Test
  fun active_backend_uses_valid_override() {
    val store = InMemoryStore().apply { put(DebugKeys.BACKEND_OVERRIDE, "local") }
    val scope = DebugScopeImpl(store, listOf(Backend("prod", "Prod", "A"), Backend("local", "Local", "B")), LogBuffer())
    assertEquals("local", scope.activeBackendId())
  }

  @Test
  fun stale_override_id_falls_back_to_first() {
    val store = InMemoryStore().apply { put(DebugKeys.BACKEND_OVERRIDE, "gone") }
    val scope = DebugScopeImpl(store, listOf(Backend("prod", "Prod", "A")), LogBuffer())
    assertEquals("prod", scope.activeBackendId())
  }

  @Test
  fun request_restart_default_is_inert_warning_not_dead() {
    val logs = LogBuffer()
    val scope = DebugScopeImpl(InMemoryStore(), emptyList(), logs)
    scope.requestRestart()
    val e = logs.snapshot().single()
    assertEquals(LogLevel.W, e.level)
  }
}
