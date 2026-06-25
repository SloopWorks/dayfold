package com.sloopworks.dayfold.cli

import com.sloopworks.dayfold.schema.BriefingCard
import com.sloopworks.dayfold.schema.BriefingCardPayload
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
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
  return errors
}

private val HUB_CATALOG = setOf("vacation", "starting-college", "move", "party-event", "new-baby", "medical", "school-year")
private val HUB_STATUS = setOf("planning", "active", "archived")
private val BLOCK_TYPES = setOf("text", "markdown", "link", "checklist", "document", "milestone", "contact", "location", "budget")

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
    }
    "sections" -> if (str("hubId").isNullOrBlank()) e += "section: `hubId` is required"
    "blocks" -> {
      if (str("sectionId").isNullOrBlank()) e += "block: `sectionId` is required"
      when (val t = str("type")) {
        null -> e += "block: `type` is required"
        !in BLOCK_TYPES -> e += "block: type \"$t\" must be one of ${BLOCK_TYPES.joinToString("|")}"
      }
    }
  }
  return e
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
