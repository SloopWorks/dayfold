package com.sloopworks.dayfold.swip.config

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.sloopworks.debugdrawer.Backend
import com.sloopworks.debugdrawer.DebugScope
import com.sloopworks.debugdrawer.log.LogBuffer
import com.sloopworks.debugdrawer.persistence.DebugStore
import com.sloopworks.debugdrawer.theme.DebugSkins
import com.sloopworks.debugdrawer.theme.LocalDebugDrawerColors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.sloop.swip.config.ConfigType
import works.sloop.swip.config.ResolutionReason
import works.sloop.swip.config.ResolutionTrace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Panel-level tests: the REAL [SwipConfigPlugin] composable driven by [FakeSwipConfigDebug].
 * The fake implements only the debug seam (no product getters exist on it), so a panel that
 * renders here structurally cannot have recorded an experiment exposure — and
 * [FakeSwipConfigDebug.resolveCalls] proves reads went through the side-effect-free path.
 */
@OptIn(ExperimentalTestApi::class)
class SwipConfigPanelTest {

  private class NoopStore : DebugStore {
    private val m = mutableMapOf<String, String>()
    override fun get(key: String) = m[key]
    override fun put(key: String, value: String) { m[key] = value }
    override fun remove(key: String) { m.remove(key) }
  }

  private class FakeScope : DebugScope {
    override val store: DebugStore = NoopStore()
    override val backends: List<Backend> = emptyList()
    override val logs: LogBuffer = LogBuffer()
    val copied = mutableListOf<String>()
    override fun activeBackendId(): String = ""
    override fun stageBackend(id: String) {}
    override fun stagedBackendId(): String? = null
    override fun requestRestart() {}
    override fun copy(text: String) { copied += text }
  }

  private fun seam() = FakeSwipConfigDebug(
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
      ),
      "briefing.tone" to FakeSwipConfigDebug.Key(
        type = ConfigType.VARIANT,
        value = JsonPrimitive("warm"),
        reason = ResolutionReason.SPLIT,
        variant = "warm",
        trace = ResolutionTrace(matchedRuleIndex = 0, rule = null, bucket = 42, variants = listOf("warm", "terse")),
      ),
    ),
  )

  @Test
  fun `renders keys grouped by namespace with value and reason chips`() = runComposeUiTest {
    val debug = seam()
    setContent {
      CompositionLocalProvider(LocalDebugDrawerColors provides DebugSkins.colors(DebugSkins.sloopworks(), dark = true)) {
        SwipConfigPlugin(debug).Content(FakeScope())
      }
    }

    // "hub" appears twice by design — the namespace filter chip AND the group header.
    assertEquals(2, onAllNodesWithText("hub").fetchSemanticsNodes().size)
    assertEquals(2, onAllNodesWithText("briefing").fetchSemanticsNodes().size)
    onNodeWithText("timeline.enabled").assertIsDisplayed()
    onNodeWithText("1500ms").assertIsDisplayed()        // DURATION rendered by declared type
    onNodeWithText("TARGETING_MATCH").assertIsDisplayed()
    onNodeWithText("SPLIT").assertIsDisplayed()
    onNodeWithText("read-so-far · authored catalog pending").assertIsDisplayed()

    assertTrue(debug.resolveCalls > 0, "panel must read through the side-effect-free resolve()")
    assertTrue(debug.setOverrideCalls.isEmpty(), "rendering must not mutate")
  }

  @Test
  fun `overridden filter narrows the list`() = runComposeUiTest {
    val debug = seam()
    debug.setOverride("briefing.tone", JsonPrimitive("terse"))
    setContent {
      CompositionLocalProvider(LocalDebugDrawerColors provides DebugSkins.colors(DebugSkins.sloopworks(), dark = true)) {
        SwipConfigPlugin(debug).Content(FakeScope())
      }
    }

    onNodeWithText("timeline.enabled").assertIsDisplayed()
    onNodeWithText("OVERRIDDEN").performClick()

    onNodeWithText("tone").assertIsDisplayed()
    onNodeWithText("timeline.enabled").assertDoesNotExist()
  }

  @Test
  fun `detail shows the eval trace and masks the targeting identity until revealed`() = runComposeUiTest {
    val debug = seam()
    setContent {
      CompositionLocalProvider(LocalDebugDrawerColors provides DebugSkins.colors(DebugSkins.sloopworks(), dark = true)) {
        SwipConfigPlugin(debug).Content(FakeScope())
      }
    }

    onNodeWithText("tone").performClick()

    onNodeWithText("reason: SPLIT").assertIsDisplayed()
    onNodeWithText("bucket: 42").assertIsDisplayed()
    onNodeWithText("matchedRuleIndex: 0").assertIsDisplayed()
    onNodeWithText("variants: warm, terse").assertIsDisplayed()
    onNodeWithText("ctx.targetingKey: $MASK").assertIsDisplayed()

    onNodeWithText("Reveal").performClick()
    onNodeWithText("ctx.targetingKey: device-abc").assertIsDisplayed()
  }

  @Test
  fun `detail shows the winning rule conditions for a targeting match`() = runComposeUiTest {
    val debug = seam()
    setContent {
      CompositionLocalProvider(LocalDebugDrawerColors provides DebugSkins.colors(DebugSkins.sloopworks(), dark = true)) {
        SwipConfigPlugin(debug).Content(FakeScope())
      }
    }

    onNodeWithText("timeline.enabled").performClick()

    onNodeWithText("matchedRuleIndex: 2").assertIsDisplayed()
    onNodeWithText("""rule: {"if":{"platform":"android"}}""").assertIsDisplayed()   // winning rule conditions
    onNodeWithText("default: false").assertIsDisplayed()
  }

  @Test
  fun `boolean editor writes a JSON boolean to setOverride`() = runComposeUiTest {
    val debug = seam()
    setContent {
      CompositionLocalProvider(LocalDebugDrawerColors provides DebugSkins.colors(DebugSkins.sloopworks(), dark = true)) {
        SwipConfigPlugin(debug).Content(FakeScope())
      }
    }

    onNodeWithText("timeline.enabled").performClick()
    onNodeWithText("false").performClick()   // the BOOLEAN toggle's "false" side

    assertEquals(listOf<Pair<String, JsonElement>>("hub.timeline.enabled" to JsonPrimitive(false)), debug.setOverrideCalls.toList())
  }

  @Test
  fun `variant editor writes the picked label`() = runComposeUiTest {
    val debug = seam()
    setContent {
      CompositionLocalProvider(LocalDebugDrawerColors provides DebugSkins.colors(DebugSkins.sloopworks(), dark = true)) {
        SwipConfigPlugin(debug).Content(FakeScope())
      }
    }

    onNodeWithText("tone").performClick()
    onNodeWithText("terse").performClick()

    assertEquals(listOf<Pair<String, JsonElement>>("briefing.tone" to JsonPrimitive("terse")), debug.setOverrideCalls.toList())
  }

  @Test
  fun `duration editor writes a numeric ms value`() = runComposeUiTest {
    val debug = seam()
    setContent {
      CompositionLocalProvider(LocalDebugDrawerColors provides DebugSkins.colors(DebugSkins.sloopworks(), dark = true)) {
        SwipConfigPlugin(debug).Content(FakeScope())
      }
    }

    onNodeWithText("refresh.intervalMs").performClick()
    onNodeWithTag(EDITOR_FIELD_TAG).performTextClearance()
    onNodeWithTag(EDITOR_FIELD_TAG).performTextInput("2500")
    onNodeWithText("Set").performClick()

    assertEquals(listOf<Pair<String, JsonElement>>("hub.refresh.intervalMs" to JsonPrimitive(2500L)), debug.setOverrideCalls.toList())
  }

  @Test
  fun `clear and clear-all reach the seam`() = runComposeUiTest {
    val debug = seam()
    debug.setOverride("briefing.tone", JsonPrimitive("terse"))
    setContent {
      CompositionLocalProvider(LocalDebugDrawerColors provides DebugSkins.colors(DebugSkins.sloopworks(), dark = true)) {
        SwipConfigPlugin(debug).Content(FakeScope())
      }
    }

    onNodeWithText("tone").performClick()
    onNodeWithText("Clear").performClick()
    assertEquals(listOf("briefing.tone"), debug.clearOverrideCalls)

    onNodeWithText("Close").performClick()

    debug.setOverride("hub.timeline.enabled", JsonPrimitive(false))
    // Wait on the UI, not the fake's counter: the header only re-renders once the poll
    // bridge has advanced past its interval and re-snapshotted.
    waitUntil { onAllNodesWithText("Clear all").fetchSemanticsNodes().isNotEmpty() }
    onNodeWithText("Clear all").performClick()
    assertEquals(1, debug.clearAllCalls)
  }

  @Test
  fun `version bump re-snapshots the panel`() = runComposeUiTest {
    val debug = seam()
    setContent {
      CompositionLocalProvider(LocalDebugDrawerColors provides DebugSkins.colors(DebugSkins.sloopworks(), dark = true)) {
        SwipConfigPlugin(debug).Content(FakeScope())
      }
    }

    onNodeWithText("true").assertIsDisplayed()          // hub.timeline.enabled, pre-override

    // Mutate the seam from OUTSIDE the panel (as the app would) — the poll bridge must
    // notice on its own. Waiting on the UI (not on the fake's counter, which is already
    // bumped) is what forces the clock past the poll interval.
    debug.setOverride("hub.timeline.enabled", JsonPrimitive(false))
    waitUntil { onAllNodesWithText("OVERRIDE").fetchSemanticsNodes().isNotEmpty() }
    onNodeWithText("1 override", substring = true).assertIsDisplayed()   // header: "rev rev-1 · 1 override"

    // A newly read-tracked key appears without any panel interaction. (Dot-free, so the
    // row's leaf label IS the key — a "late.key" would render as "key" under a "late" header.)
    debug.push("latekey", FakeSwipConfigDebug.Key(ConfigType.STRING, JsonPrimitive("z")))
    waitUntil { onAllNodesWithText("latekey").fetchSemanticsNodes().isNotEmpty() }
  }

  @Test
  fun `copy exports the trace through the drawer scope`() = runComposeUiTest {
    val debug = seam()
    val scope = FakeScope()
    setContent {
      CompositionLocalProvider(LocalDebugDrawerColors provides DebugSkins.colors(DebugSkins.sloopworks(), dark = true)) {
        SwipConfigPlugin(debug).Content(scope)
      }
    }

    onNodeWithText("tone").performClick()
    onNodeWithText("Copy").performClick()

    assertEquals(1, scope.copied.size)
    assertTrue(scope.copied.single().contains("reason: SPLIT"), scope.copied.single())
    // Masked by default: a copied trace must not leak the targeting identity.
    assertTrue(scope.copied.single().contains("ctx.targetingKey: $MASK"), scope.copied.single())
  }

  @Test
  fun `json editor rejects malformed input before it reaches the seam`() = runComposeUiTest {
    val debug = FakeSwipConfigDebug(
      mapOf("raw.blob" to FakeSwipConfigDebug.Key(ConfigType.JSON, Json.parseToJsonElement("""{"a":1}"""))),
    )
    setContent {
      CompositionLocalProvider(LocalDebugDrawerColors provides DebugSkins.colors(DebugSkins.sloopworks(), dark = true)) {
        SwipConfigPlugin(debug).Content(FakeScope())
      }
    }

    onNodeWithText("blob").performClick()
    onNodeWithTag(EDITOR_FIELD_TAG).performTextClearance()
    onNodeWithTag(EDITOR_FIELD_TAG).performTextInput("{nope")
    onNodeWithText("Set").performClick()
    assertTrue(debug.setOverrideCalls.isEmpty(), "malformed JSON must not reach the seam")

    onNodeWithTag(EDITOR_FIELD_TAG).performTextClearance()
    onNodeWithTag(EDITOR_FIELD_TAG).performTextInput("""{"a":2}""")
    onNodeWithText("Set").performClick()
    assertEquals(listOf<Pair<String, JsonElement>>("raw.blob" to Json.parseToJsonElement("""{"a":2}""")), debug.setOverrideCalls.toList())
  }
}
