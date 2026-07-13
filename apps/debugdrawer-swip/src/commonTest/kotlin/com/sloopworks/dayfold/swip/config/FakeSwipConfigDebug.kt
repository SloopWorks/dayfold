package com.sloopworks.dayfold.swip.config

import kotlinx.serialization.json.JsonElement
import works.sloop.swip.config.ConfigKeyInfo
import works.sloop.swip.config.ConfigResolution
import works.sloop.swip.config.ConfigType
import works.sloop.swip.config.EvaluationContext
import works.sloop.swip.config.Resolution
import works.sloop.swip.config.ResolutionReason
import works.sloop.swip.config.ResolutionTrace
import works.sloop.swip.config.SwipConfigDebug

/**
 * In-memory [SwipConfigDebug] for panel/model tests. It implements ONLY the debug seam —
 * there is no `boolean()/string()/variant()` to call — so a panel that compiled against
 * this fake cannot have recorded an exposure. [resolveCalls] proves reads go through the
 * side-effect-free path.
 */
class FakeSwipConfigDebug(
  keys: Map<String, Key> = emptyMap(),
  override val revision: String = "rev-1",
) : SwipConfigDebug {

  /** A key's authored state: what a real snapshot would evaluate to before overrides. */
  data class Key(
    val type: ConfigType,
    val value: JsonElement?,
    val reason: ResolutionReason = ResolutionReason.DEFAULT,
    val variant: String? = null,
    val default: JsonElement? = null,
    val trace: ResolutionTrace? = null,
  )

  private val keys = keys.toMutableMap()
  private val overrides = mutableMapOf<String, JsonElement>()
  private var version = 0L

  /** Recorded mutations — the assertions the override editor is tested against. */
  val setOverrideCalls = mutableListOf<Pair<String, JsonElement>>()
  val clearOverrideCalls = mutableListOf<String>()
  var clearAllCalls = 0
    private set
  var resolveCalls = 0
    private set

  var context = EvaluationContext(targetingKey = "device-abc", platform = "android", appVersion = "1.2.3")

  override fun keys(): List<ConfigKeyInfo> = keys.map { (k, v) -> ConfigKeyInfo(k, v.type) }

  override fun resolve(key: String): ConfigResolution {
    resolveCalls++
    val spec = keys[key]
    val ov = overrides[key]
    val base = when {
      ov != null -> Resolution(ov, ResolutionReason.OVERRIDE)
      spec != null -> Resolution(spec.value, spec.reason, variant = spec.variant)
      else -> Resolution(null, ResolutionReason.ERROR)
    }
    // Mirrors the seam: an override collapses the trace (no rule/bucket/variants).
    val trace = if (ov != null) ResolutionTrace(null, null, null, null) else spec?.trace
    return ConfigResolution(base, spec?.default, context, revision, trace)
  }

  override fun setOverride(key: String, value: JsonElement) {
    setOverrideCalls += key to value
    overrides[key] = value
    version++
  }

  override fun clearOverride(key: String) {
    clearOverrideCalls += key
    overrides -= key
    version++
  }

  override fun clearAllOverrides() {
    clearAllCalls++
    overrides.clear()
    version++
  }

  override fun overrides(): Map<String, JsonElement> = overrides.toMap()
  override fun version(): Long = version

  /** Simulate a snapshot swap / newly-tracked key: new state + a version bump to poll on. */
  fun push(key: String, spec: Key) {
    keys[key] = spec
    version++
  }
}
