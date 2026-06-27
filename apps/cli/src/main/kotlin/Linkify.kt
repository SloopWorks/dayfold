package com.sloopworks.dayfold.cli

import com.sloopworks.dayfold.client.cards.linkify
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// CL-LINK: author-side linkification at `dayfold push`. The server is content-blind
// (ADR 0015 E2EE) so all link intelligence happens here, before storage. Wraps bare
// phone/email entities in every body_md into explicit allowlisted markdown links via
// the shared linkrules `linkify`.

const val BODY_MD_CAP = 1_048_576 // F8: server caps EACH body_md field at 1 MB

data class LinkifyResult(val json: String, val diffs: List<Pair<String, String>>, val maxBodyLen: Int)

private val LJ = Json { prettyPrint = false }

/** Recursively rewrite every "body_md" string in the payload via linkify(). Returns
 *  the new JSON, a (before, after) pair per changed body, and the longest linkified
 *  body_md length (per-field F8 cap check — the server caps each field, not the whole
 *  payload). */
fun linkifyPayload(json: String): LinkifyResult {
  val diffs = mutableListOf<Pair<String, String>>()
  var maxBodyLen = 0
  fun walk(e: JsonElement): JsonElement = when (e) {
    is JsonObject -> JsonObject(e.mapValues { (k, v) ->
      if (k == "body_md" && v is JsonPrimitive && v.isString) {
        val before = v.content
        val after = linkify(before)
        if (after != before) diffs += before to after
        if (after.length > maxBodyLen) maxBodyLen = after.length
        JsonPrimitive(after)
      } else {
        walk(v)
      }
    })
    is JsonArray -> JsonArray(e.map { walk(it) })
    else -> e
  }
  val out = LJ.encodeToString(JsonElement.serializer(), walk(LJ.parseToJsonElement(json)))
  return LinkifyResult(out, diffs, maxBodyLen)
}
