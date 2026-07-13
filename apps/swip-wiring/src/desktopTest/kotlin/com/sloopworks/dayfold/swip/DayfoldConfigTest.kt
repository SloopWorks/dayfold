package com.sloopworks.dayfold.swip

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import works.sloop.swip.SwipStorage
import works.sloop.swip.config.ConfigDeps
import works.sloop.swip.config.ResolutionReason
import works.sloop.swip.config.SwipConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The real `analytics.events.enabled` spec evaluated through a real [SwipConfig] against
 * constructed device contexts. This is the key that gates whether redux actions produce
 * analytics events at all (SwipAnalyticsGlue.debugStoreEnhancer), so a wrong resolution here
 * silently switches dogfood analytics off.
 */
class DayfoldConfigTest {

  private class InMemoryStorage : SwipStorage {
    private val m = mutableMapOf<String, String>()
    override fun get(key: String) = m[key]
    override fun set(key: String, value: String) { m[key] = value }
    override fun remove(key: String) { m.remove(key) }
  }

  private fun config(
    scope: CoroutineScope,
    io: CoroutineDispatcher,
    platform: String = "android",
    osVersion: String? = "17",
    canOverride: () -> Boolean = { false },
  ) = SwipConfig(
    ConfigDeps(
      defaults = DayfoldConfig.defaults(),
      storage = InMemoryStorage(),
      nowMs = { 0L },
      targetingKey = { "device-1" },
      distinctId = { "user-1" },
      appVersion = "1.0.0",
      os = platform,
      platform = platform,
      osVersion = osVersion,
      emitExposure = {},
      scope = scope,
      ioDispatcher = io,
      canOverride = canOverride,
    ),
  )

  @Test
  fun `android 15+ matches the rule and enables analytics`() = runTest {
    val c = config(this, StandardTestDispatcher(testScheduler)); advanceUntilIdle()

    assertTrue(DayfoldConfig.analyticsEnabled(c))
    assertEquals(ResolutionReason.TARGETING_MATCH, c.peek(DayfoldConfig.ANALYTICS_EVENTS).reason)
  }

  @Test
  fun `android below 15 falls through to the default and disables analytics`() = runTest {
    val c = config(this, StandardTestDispatcher(testScheduler), osVersion = "14"); advanceUntilIdle()

    assertFalse(DayfoldConfig.analyticsEnabled(c))
    assertEquals(ResolutionReason.DEFAULT, c.peek(DayfoldConfig.ANALYTICS_EVENTS).reason)
  }

  @Test
  fun `a non-android platform never matches, even on a new OS`() = runTest {
    val c = config(this, StandardTestDispatcher(testScheduler), platform = "ios"); advanceUntilIdle()

    assertFalse(DayfoldConfig.analyticsEnabled(c))
    assertEquals(ResolutionReason.DEFAULT, c.peek(DayfoldConfig.ANALYTICS_EVENTS).reason)
  }

  // THE TRAP: a null os_version doesn't just fail to match — the condition resolves ERROR,
  // which falls back to the compiled default (false), i.e. analytics SILENTLY OFF. The host
  // must supply SwipPlatformDeps.osVersion (SwipAnalyticsGlue passes Build.VERSION.RELEASE).
  @Test
  fun `a missing os_version resolves ERROR and disables analytics`() = runTest {
    val c = config(this, StandardTestDispatcher(testScheduler), osVersion = null); advanceUntilIdle()

    assertFalse(DayfoldConfig.analyticsEnabled(c))
    assertEquals(ResolutionReason.ERROR, c.peek(DayfoldConfig.ANALYTICS_EVENTS).reason)
  }

  @Test
  fun `a debug override wins over the targeting rule`() = runTest {
    val c = config(this, StandardTestDispatcher(testScheduler), osVersion = "14", canOverride = { true })
    advanceUntilIdle()
    assertFalse(DayfoldConfig.analyticsEnabled(c))

    c.setOverride(DayfoldConfig.ANALYTICS_EVENTS, JsonPrimitive(true))

    assertTrue(DayfoldConfig.analyticsEnabled(c))
    assertEquals(ResolutionReason.OVERRIDE, c.peek(DayfoldConfig.ANALYTICS_EVENTS).reason)
  }

  // The Config panel promises to show the WINNING RULE's conditions in the detail sheet.
  // That comes from ConfigRule.raw, which ConfigKeySpec.fromJson retains.
  @Test
  fun `the debug trace carries the winning rule json`() = runTest {
    val c = config(this, StandardTestDispatcher(testScheduler), canOverride = { true }); advanceUntilIdle()

    val trace = c.resolve(DayfoldConfig.ANALYTICS_EVENTS).trace

    assertEquals(0, trace?.matchedRuleIndex)
    assertTrue(trace?.rule != null, "trace.rule was null — the panel cannot show the winning rule")
    assertTrue(trace.rule.toString().contains("os_version"), trace.rule.toString())
  }

  @Test
  fun `the product read never records an exposure`() = runTest {
    val emitted = mutableListOf<works.sloop.swip.SwipEvent>()
    val c = SwipConfig(
      ConfigDeps(
        defaults = DayfoldConfig.defaults(),
        storage = InMemoryStorage(),
        nowMs = { 0L },
        targetingKey = { "device-1" },
        distinctId = { "user-1" },
        appVersion = "1.0.0",
        os = "android",
        platform = "android",
        osVersion = "17",
        emitExposure = { emitted += it },
        scope = this,
        ioDispatcher = StandardTestDispatcher(testScheduler),
      ),
    )
    advanceUntilIdle()

    repeat(5) { DayfoldConfig.analyticsEnabled(c) }
    advanceUntilIdle()

    // TARGETING_MATCH, not SPLIT — the gate is read on every dispatch, so an exposure here
    // would forge thousands of assignments into experiment data.
    assertTrue(emitted.isEmpty(), "gate read must not emit an exposure, got: $emitted")
  }
}
