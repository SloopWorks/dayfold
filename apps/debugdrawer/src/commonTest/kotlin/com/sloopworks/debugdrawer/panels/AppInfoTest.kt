package com.sloopworks.debugdrawer.panels

import com.sloopworks.debugdrawer.BuildInfo
import com.sloopworks.debugdrawer.DebugDrawerConfig
import com.sloopworks.debugdrawer.internal.builtinPlugins
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppInfoTest {

  @Test
  fun rows_include_build_and_platform_facts_in_order() {
    val rows = appInfoRows(
      BuildInfo("1.2.0", "451", buildType = "debug", commit = "abc123", flavor = "free", extras = mapOf("region" to "us")),
      PlatformInfo(os = "TestOS", osVersion = "9", device = "Pixel 10", locale = "en_US"),
    )
    val map = rows.toMap()
    assertEquals("1.2.0", map["Version"])
    assertEquals("451", map["Build"])
    assertEquals("debug", map["Build type"])
    assertEquals("abc123", map["Commit"])
    assertEquals("free", map["Flavor"])
    assertEquals("TestOS 9", map["OS"])
    assertEquals("Pixel 10", map["Device"])
    assertEquals("en_US", map["Locale"])
    assertEquals("us", map["region"])
    assertEquals("Version", rows.first().first) // stable ordering
  }

  @Test
  fun optional_fields_omitted_when_absent() {
    val map = appInfoRows(BuildInfo("1", "1"), PlatformInfo("o", "1", "d", "l")).toMap()
    assertFalse(map.containsKey("Commit"))
    assertFalse(map.containsKey("Flavor"))
  }

  @Test
  fun builtins_include_appinfo_and_logs_when_enabled_and_empty_when_not() {
    assertEquals(
      listOf("appinfo", "logs"),
      builtinPlugins(DebugDrawerConfig(BuildInfo("1", "1"), includeBuiltins = true)).map { it.id },
    )
    assertTrue(builtinPlugins(DebugDrawerConfig(BuildInfo("1", "1"), includeBuiltins = false)).isEmpty())
  }
}
