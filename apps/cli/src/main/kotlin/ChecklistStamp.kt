package com.sloopworks.dayfold.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// ADR 0038 — stamp-on-push for checklist items.
//
// Before members can toggle to-do items, every item needs a STABLE per-item id:
// concurrent toggles on two devices merge per-item (client LWW on the done-triple),
// so an id-less item would clobber. Ids are minted client-side (the server can't —
// the payload is ciphertext at M1). `dayfold push` stamps a fresh ULID `id` (+ a
// sequential `ord`) onto any checklist item that lacks one, and PRESERVES ids that
// came from `pull` — so re-push is idempotent and a member's toggles keep their key.
//
// Hub-tree resources only (hubs/sections/blocks). Cards carry no checklist payload.
// Pure + injectable mint() so it's deterministically testable.

private val STAMP_JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun stampChecklistIds(resource: String, json: String, mint: () -> String): String {
  val root: JsonElement = try {
    STAMP_JSON.parseToJsonElement(json)
  } catch (_: Exception) {
    return json // malformed — leave it for the validator/server to reject with a real error
  }
  if (root !is JsonObject) return json
  val stamped = when (resource) {
    "blocks" -> stampBlock(root, mint)
    "sections" -> stampSection(root, mint)
    "hubs" -> stampHub(root, mint)
    else -> return json // cards / unknown — never stamped
  }
  return stamped.toString()
}

private fun stampHub(hub: JsonObject, mint: () -> String): JsonObject =
  mapArray(hub, "sections") { sec -> if (sec is JsonObject) stampSection(sec, mint) else sec }

private fun stampSection(section: JsonObject, mint: () -> String): JsonObject =
  mapArray(section, "blocks") { blk -> if (blk is JsonObject) stampBlock(blk, mint) else blk }

private fun stampBlock(block: JsonObject, mint: () -> String): JsonObject {
  val type = (block["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
  if (type != "checklist") return block
  val payload = block["payload"] as? JsonObject ?: return block
  val newPayload = mapArrayIndexed(payload, "items") { item, idx ->
    if (item is JsonObject) stampItem(item, idx, mint) else item
  }
  return JsonObject(block + ("payload" to newPayload))
}

private fun stampItem(item: JsonObject, index: Int, mint: () -> String): JsonObject {
  val extra = LinkedHashMap<String, JsonElement>()
  if (!item.hasValue("id")) extra["id"] = JsonPrimitive(mint())
  if (!item.hasValue("ord")) extra["ord"] = JsonPrimitive(index)
  return if (extra.isEmpty()) item else JsonObject(item + extra)
}

// ── helpers ──────────────────────────────────────────────────────────────────

private fun JsonObject.hasValue(key: String): Boolean {
  val v = this[key]
  return v != null && v !is JsonNull
}

private inline fun mapArray(obj: JsonObject, key: String, transform: (JsonElement) -> JsonElement): JsonObject {
  val arr = obj[key] as? JsonArray ?: return obj
  return JsonObject(obj + (key to JsonArray(arr.map(transform))))
}

private inline fun mapArrayIndexed(obj: JsonObject, key: String, transform: (JsonElement, Int) -> JsonElement): JsonObject {
  val arr = obj[key] as? JsonArray ?: return obj
  return JsonObject(obj + (key to JsonArray(arr.mapIndexed { i, e -> transform(e, i) })))
}
