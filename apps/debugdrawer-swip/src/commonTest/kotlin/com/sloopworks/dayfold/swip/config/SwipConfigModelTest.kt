package com.sloopworks.dayfold.swip.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.sloop.swip.config.ConfigType
import works.sloop.swip.config.ResolutionReason
import works.sloop.swip.config.ResolutionTrace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun fake() = FakeSwipConfigDebug(
  mapOf(
    "hub.timeline.enabled" to FakeSwipConfigDebug.Key(
      type = ConfigType.BOOLEAN,
      value = JsonPrimitive(true),
      reason = ResolutionReason.TARGETING_MATCH,
      default = JsonPrimitive(false),
      trace = ResolutionTrace(
        matchedRuleIndex = 2,
        rule = buildJsonObject { put("if", buildJsonObject { put("platform", "android") }) },
        bucket = null,
        variants = null,
      ),
    ),
    "hub.refresh.intervalMs" to FakeSwipConfigDebug.Key(
      type = ConfigType.DURATION,
      value = JsonPrimitive(1500),
      reason = ResolutionReason.DEFAULT,
      default = JsonPrimitive(1500),
    ),
    "briefing.tone" to FakeSwipConfigDebug.Key(
      type = ConfigType.VARIANT,
      value = JsonPrimitive("warm"),
      reason = ResolutionReason.SPLIT,
      variant = "warm",
      trace = ResolutionTrace(matchedRuleIndex = 0, rule = null, bucket = 42, variants = listOf("warm", "terse")),
    ),
    "standalone" to FakeSwipConfigDebug.Key(type = ConfigType.STRING, value = JsonPrimitive("x")),
  ),
)

class SwipConfigModelTest {

  @Test
  fun `snapshot renders rows by declared type and reason`() {
    val snap = snapshotConfig(fake())

    assertEquals(listOf("briefing.tone", "hub.refresh.intervalMs", "hub.timeline.enabled", "standalone"), snap.rows.map { it.key })
    val byKey = snap.rows.associateBy { it.key }
    assertEquals("true", byKey.getValue("hub.timeline.enabled").value)
    assertEquals("1500ms", byKey.getValue("hub.refresh.intervalMs").value)
    assertEquals("warm", byKey.getValue("briefing.tone").value)
    assertEquals(ResolutionReason.SPLIT, byKey.getValue("briefing.tone").reason)
    assertEquals(0, snap.overrideCount)
  }

  @Test
  fun `keys group by leading namespace`() {
    val groups = groupRows(snapshotConfig(fake()).rows)

    assertEquals(listOf("(root)", "briefing", "hub"), groups.map { it.namespace })
    assertEquals(listOf("refresh.intervalMs", "timeline.enabled"), groups.first { it.namespace == "hub" }.rows.map { it.shortKey })
    assertEquals(listOf("standalone"), groups.first { it.namespace == ROOT_NAMESPACE }.rows.map { it.shortKey })
  }

  @Test
  fun `overridden filter and namespace filter narrow the rows`() {
    val debug = fake()
    debug.setOverride("hub.timeline.enabled", JsonPrimitive(false))
    val snap = snapshotConfig(debug)

    assertEquals(1, snap.overrideCount)
    assertEquals(
      listOf("hub.timeline.enabled"),
      applyFilter(snap.rows, ConfigFilter.OVERRIDDEN).map { it.key },
    )
    assertEquals(
      listOf("hub.refresh.intervalMs", "hub.timeline.enabled"),
      applyFilter(snap.rows, ConfigFilter.ALL, namespace = "hub").map { it.key },
    )
    assertEquals(listOf("(root)", "briefing", "hub"), namespaces(snap.rows))
  }

  @Test
  fun `override wins with reason OVERRIDE and re-renders by declared type`() {
    val debug = fake()
    debug.setOverride("hub.timeline.enabled", JsonPrimitive(false))
    val row = snapshotConfig(debug).rows.first { it.key == "hub.timeline.enabled" }

    assertEquals(ResolutionReason.OVERRIDE, row.reason)
    assertTrue(row.overridden)
    assertEquals("false", row.value)
  }

  @Test
  fun `detail lines carry the eval trace and mask targeting identity`() {
    val debug = fake()
    val snap = snapshotConfig(debug)
    val row = snap.rows.first { it.key == "briefing.tone" }
    val lines = detailLines(row, snap.resolutions.getValue("briefing.tone"))
    val byLabel = lines.associate { it.label to it.value }

    assertEquals("SPLIT", byLabel["reason"])
    assertEquals("warm", byLabel["variant"])
    assertEquals("0", byLabel["matchedRuleIndex"])
    assertEquals("42", byLabel["bucket"])
    assertEquals("warm, terse", byLabel["variants"])
    assertEquals("rev-1", byLabel["revision"])

    val targeting = lines.first { it.label == "ctx.targetingKey" }
    assertTrue(targeting.sensitive)
    assertEquals(MASK, renderLine(targeting, revealed = false))
    assertEquals("device-abc", renderLine(targeting, revealed = true))
  }

  @Test
  fun `winning rule json is shown for a targeting match`() {
    val snap = snapshotConfig(fake())
    val lines = detailLines(
      snap.rows.first { it.key == "hub.timeline.enabled" },
      snap.resolutions.getValue("hub.timeline.enabled"),
    )
    val byLabel = lines.associate { it.label to it.value }

    assertEquals("2", byLabel["matchedRuleIndex"])
    assertTrue(byLabel.getValue("rule").contains("android"), "winning rule conditions should be shown: ${byLabel["rule"]}")
    assertEquals("false", byLabel["default"])
  }

  @Test
  fun `variant labels survive an override that collapses the trace`() {
    val debug = fake()
    val before = snapshotConfig(debug)
    assertEquals(listOf("warm", "terse"), before.knownVariants["briefing.tone"])

    debug.setOverride("briefing.tone", JsonPrimitive("terse"))
    val after = snapshotConfig(debug, previous = before)

    // The seam's OVERRIDE trace has no `variants` — without the carry-forward the picker
    // would go blank the moment you used it.
    assertNull(after.resolutions.getValue("briefing.tone").trace?.variants)
    assertEquals(listOf("warm", "terse"), after.knownVariants["briefing.tone"])
  }

  @Test
  fun `buildOverride forms the right JsonElement per declared type`() {
    assertEquals(JsonPrimitive(false), buildOverride(ConfigType.BOOLEAN, "false"))
    assertNull(buildOverride(ConfigType.BOOLEAN, "yes"))
    assertEquals(JsonPrimitive("hello"), buildOverride(ConfigType.STRING, "hello"))
    assertEquals(JsonPrimitive("terse"), buildOverride(ConfigType.VARIANT, "terse"))
    assertEquals(JsonPrimitive(2500L), buildOverride(ConfigType.DURATION, " 2500 "))
    assertEquals(JsonPrimitive("2s"), buildOverride(ConfigType.DURATION, "2s"))
    assertNull(buildOverride(ConfigType.DURATION, ""))
    assertEquals(Json.parseToJsonElement("""{"a":1}"""), buildOverride(ConfigType.JSON, """{"a":1}"""))
    assertNull(buildOverride(ConfigType.JSON, "{nope"))
  }

  @Test
  fun `editor seeds from the current value`() {
    val snap = snapshotConfig(fake())
    assertEquals("true", editorSeed(snap.resolutions.getValue("hub.timeline.enabled"), ConfigType.BOOLEAN))
    assertEquals("1500", editorSeed(snap.resolutions.getValue("hub.refresh.intervalMs"), ConfigType.DURATION))
    assertEquals("warm", editorSeed(snap.resolutions.getValue("briefing.tone"), ConfigType.VARIANT))
  }

  @Test
  fun `snapshot reads only through the side-effect-free resolve seam`() {
    val debug = fake()
    snapshotConfig(debug)

    // One resolve per key, no other seam reads — and the fake exposes no product getter
    // to call, so the panel structurally cannot record an exposure.
    assertEquals(4, debug.resolveCalls)
    assertTrue(debug.setOverrideCalls.isEmpty())
    assertTrue(debug.clearOverrideCalls.isEmpty())
    assertEquals(0, debug.clearAllCalls)
  }

  @Test
  fun `version tracks seam mutations so the poll bridge re-snapshots`() {
    val debug = fake()
    val first = snapshotConfig(debug)
    assertEquals(0L, first.version)

    debug.setOverride("standalone", JsonPrimitive("y"))
    assertEquals(1L, snapshotConfig(debug).version)

    debug.push("late.key", FakeSwipConfigDebug.Key(ConfigType.STRING, JsonPrimitive("z")))
    val third = snapshotConfig(debug)
    assertEquals(2L, third.version)
    assertTrue(third.rows.any { it.key == "late.key" })

    debug.clearAllOverrides()
    assertEquals(3L, snapshotConfig(debug).version)
  }
}
