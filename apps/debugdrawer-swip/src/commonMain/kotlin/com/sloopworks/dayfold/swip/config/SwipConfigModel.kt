package com.sloopworks.dayfold.swip.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import works.sloop.swip.config.ConfigResolution
import works.sloop.swip.config.ConfigType
import works.sloop.swip.config.ResolutionReason
import works.sloop.swip.config.SwipConfigDebug

/**
 * Pure model behind the config-debug panel (SwipConfigPlugin). No Compose here — the
 * panel is a thin renderer over [snapshotConfig], which is the ONLY place that touches
 * the [SwipConfigDebug] seam.
 *
 * EXPOSURE SAFETY: every read goes through [SwipConfigDebug.resolve], which is
 * side-effect-free. The product getters (`config.boolean/string/variant`) record a real
 * experiment exposure on read — calling them from a debug panel would forge assignments
 * and pollute experiment data. They are never called here.
 */

/** Row-list filter. Namespace filtering is orthogonal (see [applyFilter]). */
enum class ConfigFilter { ALL, OVERRIDDEN }

/** `hub.timeline.enabled` → `hub`; a key with no dot groups under [ROOT_NAMESPACE]. */
const val ROOT_NAMESPACE: String = "(root)"

fun namespaceOf(key: String): String =
  key.substringBefore('.').takeIf { it.isNotEmpty() && it != key } ?: ROOT_NAMESPACE

data class ConfigRow(
  val key: String,
  val type: ConfigType,
  val value: String,
  val reason: ResolutionReason,
  val overridden: Boolean,
) {
  val namespace: String get() = namespaceOf(key)
  /** Leaf name — the group header already carries the namespace. */
  val shortKey: String get() = if (namespace == ROOT_NAMESPACE) key else key.substringAfter('.')
}

data class ConfigGroup(val namespace: String, val rows: List<ConfigRow>)

/**
 * One consistent read of the seam: rows + the [ConfigResolution] behind each (so the
 * detail sheet re-renders from the same snapshot rather than re-resolving), plus the
 * variant labels seen so far.
 */
data class ConfigUiSnapshot(
  val rows: List<ConfigRow> = emptyList(),
  val resolutions: Map<String, ConfigResolution> = emptyMap(),
  /** key → declared variant labels. Accumulated across snapshots — see [mergeVariants]. */
  val knownVariants: Map<String, List<String>> = emptyMap(),
  val revision: String = "",
  val version: Long = -1L,
) {
  val overrideCount: Int get() = rows.count { it.overridden }
}

/**
 * Snapshot the seam. [previous] carries forward variant labels: once a key is overridden
 * its trace collapses to OVERRIDE (no `variants`), so the picker would lose its options
 * the moment you use it. Labels observed before the override survive for the session;
 * after a cold start with an override already applied the picker falls back to free text
 * until the override is cleared and the real trace returns.
 */
fun snapshotConfig(debug: SwipConfigDebug, previous: ConfigUiSnapshot = ConfigUiSnapshot()): ConfigUiSnapshot {
  val keys = debug.keys()
  val resolutions = keys.associate { it.key to debug.resolve(it.key) }
  val rows = keys
    .map { info ->
      val res = resolutions.getValue(info.key)
      ConfigRow(
        key = info.key,
        type = info.type,
        value = renderValue(res, info.type),
        reason = res.base.reason,
        overridden = res.base.reason == ResolutionReason.OVERRIDE,
      )
    }
    .sortedBy { it.key }
  return ConfigUiSnapshot(
    rows = rows,
    resolutions = resolutions,
    knownVariants = mergeVariants(previous.knownVariants, resolutions),
    revision = debug.revision,
    version = debug.version(),
  )
}

/** Union of previously-seen labels with any the current traces still expose. */
fun mergeVariants(
  previous: Map<String, List<String>>,
  resolutions: Map<String, ConfigResolution>,
): Map<String, List<String>> {
  val next = previous.toMutableMap()
  resolutions.forEach { (key, res) ->
    res.trace?.variants?.takeIf { it.isNotEmpty() }?.let { next[key] = it }
  }
  return next
}

fun groupRows(rows: List<ConfigRow>): List<ConfigGroup> =
  rows.groupBy { it.namespace }
    .toSortedMap()
    .map { (ns, rs) -> ConfigGroup(ns, rs) }

fun applyFilter(rows: List<ConfigRow>, filter: ConfigFilter, namespace: String? = null): List<ConfigRow> =
  rows
    .filter { filter != ConfigFilter.OVERRIDDEN || it.overridden }
    .filter { namespace == null || it.namespace == namespace }

fun namespaces(rows: List<ConfigRow>): List<String> = rows.map { it.namespace }.distinct().sorted()

/** Human value for a row, keyed by the DECLARED type (not the JSON shape). */
fun renderValue(res: ConfigResolution, type: ConfigType): String {
  val v = res.base.value ?: return "—"
  val p = v as? JsonPrimitive ?: return v.toString()
  return when (type) {
    ConfigType.VARIANT -> res.base.variant ?: p.content
    ConfigType.DURATION -> if (p.isString) p.content else "${p.longOrNull ?: p.content}ms"
    ConfigType.BOOLEAN, ConfigType.STRING -> p.content
    ConfigType.JSON -> v.toString()
  }
}

/** A detail-sheet line. `sensitive` values stay masked until the user reveals them. */
data class DetailLine(val label: String, val value: String, val sensitive: Boolean = false)

const val MASK: String = "••••"

fun renderLine(line: DetailLine, revealed: Boolean): String =
  if (line.sensitive && !revealed) MASK else line.value

/**
 * The eval trace: why this key resolved the way it did. `targetingKey` is the bucketing
 * identity and `custom` attributes can carry household-shaped targeting data → both are
 * masked by default (FLAG_SECURE covers the reveal).
 */
fun detailLines(row: ConfigRow, res: ConfigResolution): List<DetailLine> = buildList {
  add(DetailLine("key", row.key))
  add(DetailLine("type", row.type.name))
  add(DetailLine("reason", res.base.reason.name))
  add(DetailLine("value", row.value))
  res.base.variant?.let { add(DetailLine("variant", it)) }
  res.base.salt?.let { add(DetailLine("salt", it)) }
  res.base.unit?.let { add(DetailLine("unit", it)) }
  res.trace?.let { t ->
    t.matchedRuleIndex?.let { add(DetailLine("matchedRuleIndex", it.toString())) }
    t.bucket?.let { add(DetailLine("bucket", it.toString())) }
    t.variants?.takeIf { it.isNotEmpty() }?.let { add(DetailLine("variants", it.joinToString(", "))) }
    t.rule?.let { add(DetailLine("rule", it.toString())) }
  }
  add(DetailLine("default", res.default?.toString() ?: "—"))
  add(DetailLine("revision", res.revision))
  with(res.context) {
    add(DetailLine("ctx.targetingKey", targetingKey.ifEmpty { "—" }, sensitive = true))
    platform?.let { add(DetailLine("ctx.platform", it)) }
    appVersion?.let { add(DetailLine("ctx.appVersion", it)) }
    osVersion?.let { add(DetailLine("ctx.osVersion", it)) }
    custom.forEach { (k, v) -> add(DetailLine("ctx.$k", v, sensitive = true)) }
  }
}

fun copyText(row: ConfigRow, res: ConfigResolution, revealed: Boolean): String =
  detailLines(row, res).joinToString("\n") { "${it.label}: ${renderLine(it, revealed)}" }

/**
 * Editor input → the `JsonElement` handed to [SwipConfigDebug.setOverride]; null when the
 * text can't form a value of [type] at all (empty JSON, non-numeric duration). The seam
 * re-validates and no-ops on anything invalid — this is UX, not the security boundary.
 */
fun buildOverride(type: ConfigType, raw: String): JsonElement? = when (type) {
  ConfigType.BOOLEAN -> raw.trim().toBooleanStrictOrNull()?.let { JsonPrimitive(it) }
  ConfigType.STRING, ConfigType.VARIANT -> JsonPrimitive(raw)
  // The seam accepts a number (ms) or a duration string ("1500ms", "2s"); prefer the
  // number when the field is bare digits, else pass the string through for it to parse.
  ConfigType.DURATION -> raw.trim().let { t ->
    when {
      t.isEmpty() -> null
      t.toLongOrNull() != null -> JsonPrimitive(t.toLong())
      else -> JsonPrimitive(t)
    }
  }
  ConfigType.JSON -> try {
    raw.trim().takeIf { it.isNotEmpty() }?.let { Json.parseToJsonElement(it) }
  } catch (_: Throwable) { null }
}

/** Prefill for the editor: the current value in the shape [buildOverride] expects back. */
fun editorSeed(res: ConfigResolution, type: ConfigType): String {
  val v = res.base.value ?: return ""
  val p = v as? JsonPrimitive ?: return v.toString()
  return when (type) {
    ConfigType.BOOLEAN -> (p.booleanOrNull ?: false).toString()
    ConfigType.VARIANT -> res.base.variant ?: p.content
    ConfigType.JSON -> v.toString()
    ConfigType.STRING, ConfigType.DURATION -> p.content
  }
}
