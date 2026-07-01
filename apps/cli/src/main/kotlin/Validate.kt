package com.sloopworks.dayfold.cli

import com.sloopworks.dayfold.schema.BlockType
import com.sloopworks.dayfold.schema.BriefingCard
import com.sloopworks.dayfold.schema.BriefingCardPayload
import com.sloopworks.dayfold.schema.Status
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

// CL-3 — local STRUCTURAL validation of a typed card against the GENERATED schema
// types (com.sloopworks.dayfold.schema.*, the single source of truth): variant↔type match,
// unknown/mistyped fields (strict decode). It is a fast pre-check, NOT a full
// replica of the server gate — format rules (url(), ISO datetimes, length caps,
// int-ness) are still enforced server-side (CL-2), which remains the authority.
// Note two codegen asymmetries: this requires `kind` and `provenance.at` even
// though the server defaults/relaxes them — so author from `dayfold template`.

// Strict: reject unknown fields (catches typos) + wrong types. Mirrors the
// server's .strict() zod posture.
private val STRICT = Json { ignoreUnknownKeys = false; isLenient = false }
private val LENIENT = Json { ignoreUnknownKeys = true }

/**
 * Returns a list of human-readable errors (empty = valid). [assertType], when
 * non-null (the `--type` flag), must equal the card's own `type`. The JSON must
 * be a COMPLETE card (incl. `id`); use [withId] to inject a path id first.
 */
fun validateCard(assertType: String?, json: String): List<String> {
  val card: BriefingCard = try {
    STRICT.decodeFromString(BriefingCard.serializer(), json)
  } catch (e: SerializationException) {
    return listOf("invalid card JSON: ${firstLine(e.message)}")
  } catch (e: IllegalArgumentException) {
    return listOf("invalid card JSON: ${firstLine(e.message)}")
  }

  val errors = mutableListOf<String>()
  val type = card.type?.value
  val payload = card.payload

  // type <-> payload presence + variant-key match (mirrors CL-2 crossValidateCard)
  if ((type == null) != (payload == null)) {
    errors += if (type == null) "payload is set but `type` is missing"
    else "type \"$type\" is set but `payload` is missing"
  } else if (type != null && payload != null) {
    when (val key = variantKey(payload)) {
      null -> errors += "payload has no recognized variant object"
      type -> {} // ok
      else -> errors += "payload variant \"$key\" does not match type \"$type\""
    }
  }

  if (assertType != null && type != assertType) {
    errors += "--type $assertType, but the card's type is ${type ?: "(none)"}"
  }

  // ADR 0036 — card.media (icon/accent/thumbnail). Mirrors the server gate.
  card.media?.let { m ->
    m.thumbnailURL?.let { MediaValidation.imageUrlError(it)?.let { e -> errors += "media.thumbnailUrl: $e" } }
    m.icon?.let { MediaValidation.iconError(it)?.let { e -> errors += "media.icon: $e" } }
    m.accentColor?.let { MediaValidation.accentHexError(it)?.let { e -> errors += "media.accentColor: $e" } }
  }
  return errors
}

// ADR 0036 — validate a media/payload JsonObject's image fields (hub + block paths,
// which are JSON-structural rather than typed-decoded). [keys] = the url fields to
// host-check; icon/accentColor are checked when present.
private fun mediaJsonErrors(prefix: String, obj: JsonObject?, urlKeys: List<String>): List<String> {
  if (obj == null) return emptyList()
  val e = mutableListOf<String>()
  fun s(k: String) = (obj[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
  for (k in urlKeys) s(k)?.let { MediaValidation.imageUrlError(it)?.let { msg -> e += "$prefix.$k: $msg" } }
  s("icon")?.let { MediaValidation.iconError(it)?.let { msg -> e += "$prefix.icon: $msg" } }
  s("accentColor")?.let { MediaValidation.accentHexError(it)?.let { msg -> e += "$prefix.accentColor: $msg" } }
  return e
}

// The hub `type` is a free `string` server-side (ADR 0004/0006: "app-validated",
// no generated enum) — the catalog lives only in the schema's describe() text, so
// it stays hand-listed here. HUB_STATUS / BLOCK_TYPES, by contrast, ARE generated
// enums in Content.kt (the schema package the CLI already sources) — derive them so
// a schema change can't silently drift this pre-check into rejecting newly-valid
// content (or accepting a since-removed value). They match the hand-list today.
private val HUB_CATALOG = setOf("vacation", "starting-college", "move", "party-event", "new-baby", "medical", "school-year")
private val HUB_STATUS = Status.entries.map { it.value }.toSet()
private val BLOCK_TYPES = BlockType.entries.map { it.value }.toSet()

/**
 * Fast pre-check for a hub-tree PUT body (push --hub/--section/--block). Like
 * [validateCard] it is NOT a full server replica — it catches the common authoring
 * mistakes (missing routing fields, an off-catalog hub type, a bad status / block
 * type) lenient-structurally, so it stays robust to the server's strip-then-parse
 * details. The server (CL-2) remains the authority. [resource] = the pushResource
 * value ("hubs" | "sections" | "blocks").
 */
fun validateHubTree(resource: String, json: String): List<String> {
  val obj = try {
    LENIENT.parseToJsonElement(json).jsonObject
  } catch (ex: Exception) {
    return listOf("invalid $resource JSON: ${firstLine(ex.message)}")
  }
  fun str(k: String): String? = (obj[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
  val e = mutableListOf<String>()
  when (resource) {
    "hubs" -> {
      if (str("title").isNullOrBlank()) e += "hub: `title` is required"
      when (val t = str("type")) {
        null -> e += "hub: `type` is required"
        !in HUB_CATALOG -> e += "hub: type \"$t\" is not a catalog key (${HUB_CATALOG.joinToString("|")})"
      }
      str("status")?.let { if (it !in HUB_STATUS) e += "hub: status \"$it\" must be ${HUB_STATUS.joinToString("|")}" }
      // ADR 0036 — Hub.media (heroUrl/thumbnailUrl/icon/accentColor).
      e += mediaJsonErrors("media", obj["media"] as? JsonObject, listOf("heroUrl", "thumbnailUrl"))
      // ADR 0045 — Hub.timeline structural validation (mirrors server gate).
      e += hubTimelineErrors(obj["timeline"] as? JsonObject)
    }
    "sections" -> if (str("hubId").isNullOrBlank()) e += "section: `hubId` is required"
    "blocks" -> {
      if (str("sectionId").isNullOrBlank()) e += "block: `sectionId` is required"
      val t = str("type")
      when (t) {
        null -> e += "block: `type` is required"
        !in BLOCK_TYPES -> e += "block: type \"$t\" must be one of ${BLOCK_TYPES.joinToString("|")}"
        else -> {
          e += blockPayloadErrors(t, obj["payload"] as? JsonObject, str("body_md"))
          // ADR 0036 — block media rides the payload: link/document thumbnailUrl,
          // contact avatarUrl + accentColor.
          when (t) {
            "link", "document" -> e += mediaJsonErrors("payload", obj["payload"] as? JsonObject, listOf("thumbnailUrl"))
            "contact" -> e += mediaJsonErrors("payload", obj["payload"] as? JsonObject, listOf("avatarUrl"))
          }
        }
      }
    }
  }
  return e
}

// ── hub timeline structural pre-check (ADR 0045) ─────────────────────────────
private val ATTACH_KINDS = setOf("call", "nav", "link", "open")

internal fun hubTimelineErrors(timeline: JsonObject?): List<String> {
  if (timeline == null) return emptyList()
  val e = mutableListOf<String>()
  val tz = (timeline["tz"] as? JsonPrimitive)?.takeIf { it.isString }?.content
  if (tz.isNullOrBlank()) e += "timeline: `tz` (IANA) is required"
  val stops = timeline["stops"] as? JsonArray
  if (stops == null || stops.isEmpty()) { e += "timeline: `stops` must be a non-empty array"; return e }
  stops.forEachIndexed { i, s ->
    val stop = s as? JsonObject ?: run { e += "timeline.stops[$i]: must be an object"; return@forEachIndexed }
    if ((stop["at"] as? JsonPrimitive)?.takeIf { it.isString }?.content.isNullOrBlank()) e += "timeline.stops[$i]: `at` is required"
    if ((stop["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content.isNullOrBlank()) e += "timeline.stops[$i]: `title` is required"
    (stop["attachments"] as? JsonArray)?.forEachIndexed { j, a ->
      val kind = ((a as? JsonObject)?.get("kind") as? JsonPrimitive)?.content
      if (kind !in ATTACH_KINDS) e += "timeline.stops[$i].attachments[$j]: `kind` must be ${ATTACH_KINDS.joinToString("|")}"
    }
  }
  return e
}

// ── block payload structural pre-check (ADR 0035, Option C) ──────────────────
// IF a block carries a `payload`, the core field for its type must be present, so a
// structured block that can't render is caught before push. A payload-driven block
// with NO payload is fine — it renders its body_md, or a calm placeholder (#113).
// TOLERANT by design: accepts BOTH the canonical schema names and the current
// client-render names (document `ref` OR `docRef`; budget `items` OR `total`/`spent`)
// — it does NOT yet pick a side (that's the M1 single-representation unification,
// OQ-block-payload-schema / ADR 0035).
private fun JsonObject.has(vararg keys: String): Boolean =
  keys.any { this[it] != null && this[it] !is JsonNull }
private fun JsonObject.hasNonEmptyArray(key: String): Boolean =
  (this[key] as? JsonArray)?.isNotEmpty() == true

internal fun blockPayloadErrors(type: String, payload: JsonObject?, bodyMd: String?): List<String> {
  if (payload == null) return emptyList()                        // body_md path / placeholder — fine (#113)
  if (type == "text" || type == "markdown") return emptyList()   // these are body_md, not payload-driven
  val ok = when (type) {
    "checklist" -> payload.hasNonEmptyArray("items")
    "budget" -> payload.hasNonEmptyArray("items") || payload.has("total", "spent")
    "document" -> payload.has("ref", "docRef")
    "link" -> payload.has("url")
    "contact" -> payload.has("name")
    "location" -> payload.has("label")
    "milestone" -> payload.has("date", "label") || !bodyMd.isNullOrBlank()
    else -> true
  }
  return if (ok) emptyList()
  else listOf("block $type: `payload` is present but missing its core field — see apps/cli/templates/README.md")
}

private fun variantKey(p: BriefingCardPayload): String? = when {
  p.file != null -> "file"
  p.link != null -> "link"
  p.invite != null -> "invite"
  p.contact != null -> "contact"
  p.geo != null -> "geo"
  p.email != null -> "email"
  else -> null
}

private fun firstLine(s: String?): String = s?.lineSequence()?.firstOrNull()?.trim() ?: "could not parse"

/** Inject/overwrite the `id` (push takes it from the path arg) so validation sees
 *  a complete card; returns the merged JSON string (or the original on parse fail
 *  — the strict decode in [validateCard] then surfaces the real error). */
fun withId(json: String, id: String): String = try {
  val obj = LENIENT.parseToJsonElement(json).jsonObject
  JsonObject(obj + ("id" to JsonPrimitive(id))).toString()
} catch (_: Exception) {
  json
}
