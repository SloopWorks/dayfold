package com.sloopworks.dayfold.swip

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import works.sloop.swip.SloopConfig
import works.sloop.swip.config.ConfigKeySpec

/**
 * Dayfold's compiled-in SWIP config keys.
 *
 * Debug-only instrument (ADR 0055 keeps analytics out of release entirely): this module is
 * consumed `debugImplementation`, so nothing here is on the release classpath.
 *
 * Specs are authored as JSON and parsed with [ConfigKeySpec.fromJson] rather than
 * hand-constructed — this is the same shape a registry rule has, so these port to registry
 * codegen (`registry/products/dayfold.yaml`) without a rewrite.
 */
object DayfoldConfig {

  /**
   * Gates whether redux actions produce analytics events at all.
   *
   * Reason is always TARGETING_MATCH or DEFAULT — never SPLIT — so reading it records NO
   * exposure and it cannot pollute experiment data.
   */
  const val ANALYTICS_EVENTS: String = "analytics.events.enabled"

  /**
   * `os_version` comparisons with gte are NUMERIC (`toDoubleOrNull`), not semver, so
   * `Build.VERSION.RELEASE` ("17", "15") parses cleanly. Only `eq` on a `*_version`
   * attribute takes the semver-range path.
   *
   * Fails CLOSED: a device that doesn't match the rule falls through to `default: false`.
   * That also means a null/unparsable `os_version` resolves ERROR → default → analytics
   * OFF, which is why the host MUST supply `SwipPlatformDeps.osVersion` (see
   * SwipAnalyticsGlue). Pinned by DayfoldConfigTest.
   */
  private val ANALYTICS_EVENTS_SPEC = """
    {
      "key": "$ANALYTICS_EVENTS",
      "type": "boolean",
      "default": false,
      "rules": [
        { "if": { "platform": "android", "os_version": { "gte": 15 } }, "value": true }
      ]
    }
  """.trimIndent()

  /** Compiled defaults for `SwipPlatformDeps.configDefaults`. */
  fun defaults(): Map<String, ConfigKeySpec> = listOf(ANALYTICS_EVENTS_SPEC)
    .map { ConfigKeySpec.fromJson(Json.parseToJsonElement(it).jsonObject) }
    .associateBy { it.key }

  /**
   * The product read. Goes through the typed getter (not the debug seam), so it read-tracks
   * the key and surfaces it in the debug drawer's Config panel.
   */
  fun analyticsEnabled(config: SloopConfig): Boolean = config.boolean(ANALYTICS_EVENTS)
}
