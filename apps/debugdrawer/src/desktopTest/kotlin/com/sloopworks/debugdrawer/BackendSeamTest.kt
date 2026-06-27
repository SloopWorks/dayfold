package com.sloopworks.debugdrawer

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The backend-override seam (R1/R2). install() is eager (not composition-time);
 * backendUrl() resolves the cached override → url, falling back to the passed
 * default for no-override / unknown-id.
 */
class BackendSeamTest {

  private val backends = listOf(Backend("prod", "Prod", "https://prod"), Backend("local", "Local", "http://localhost"))

  @AfterTest fun cleanup() {
    // install() may have persisted an override to the real desktop store.
    DebugDrawer.install(DebugDrawerConfig(BuildInfo("1", "1"), backends = backends))
    DebugDrawer.setOverride(null)
  }

  @Test
  fun no_override_returns_default() {
    DebugDrawer.install(DebugDrawerConfig(BuildInfo("1", "1"), backends = backends))
    DebugDrawer.setOverride(null)
    assertEquals("https://prod", DebugDrawer.backendUrl("https://prod"))
    assertNull(DebugDrawer.selectedBackendId())
  }

  @Test
  fun override_resolves_to_backend_url() {
    DebugDrawer.install(DebugDrawerConfig(BuildInfo("1", "1"), backends = backends))
    DebugDrawer.setOverride("local")
    assertEquals("http://localhost", DebugDrawer.backendUrl("https://prod"))
    assertEquals("local", DebugDrawer.selectedBackendId())
  }

  @Test
  fun unknown_override_falls_back_to_default() {
    DebugDrawer.install(DebugDrawerConfig(BuildInfo("1", "1"), backends = backends))
    DebugDrawer.setOverride("gone")
    assertEquals("https://prod", DebugDrawer.backendUrl("https://prod"))
  }

  @Test
  fun override_survives_a_reinstall_the_app_restart_path() {
    // pick a backend, then simulate an app restart: a fresh install() with the same
    // persistent store must RE-SEED the override (this is the "applies on restart"
    // contract — a regression here silently reverts to the build default).
    DebugDrawer.install(DebugDrawerConfig(BuildInfo("1", "1"), backends = backends))
    DebugDrawer.setOverride("local")
    DebugDrawer.install(DebugDrawerConfig(BuildInfo("1", "1"), backends = backends))   // restart
    assertEquals("local", DebugDrawer.selectedBackendId())
    assertEquals("http://localhost", DebugDrawer.backendUrl("https://prod"))
  }

  @Test
  fun a_persisted_override_for_a_since_removed_backend_is_dropped_on_install() {
    // persist an override, then "ship a build" whose backend list no longer has it →
    // install() must drop the stale id and fall to the default, not pin a dead backend.
    DebugDrawer.install(DebugDrawerConfig(BuildInfo("1", "1"), backends = backends))
    DebugDrawer.setOverride("local")
    DebugDrawer.install(DebugDrawerConfig(BuildInfo("1", "1"), backends = listOf(Backend("prod", "Prod", "https://prod"))))
    assertNull(DebugDrawer.selectedBackendId())
    assertEquals("https://prod", DebugDrawer.backendUrl("https://prod"))
  }
}
