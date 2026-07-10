package com.sloopworks.dayfold.client

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

// Transport for the hub content API (ADR 0006 render). Same posture as SyncClient/
// AuthClient: ktor in commonMain, explicit kotlinx-serialization (no
// ContentNegotiation plugin). All I/O, no state — HubEngine sequences these.
// Reuses AuthHttpException so HubEngine.callWithRefresh can branch on 401 like the
// auth path. GET /families/:fid/hubs returns a BARE ARRAY; /hubs/:id/tree returns
// the {hub,sections,blocks} envelope (404 = restricted/absent, ADR 0030 omit-don't-403).
class HubClient(
  private val api: String,
  private val http: HttpClient = HttpClient(),
  private val json: Json = Json { ignoreUnknownKeys = true },
) {
  suspend fun familyHubs(access: String, fid: String): List<Hub> {
    val resp = http.get("$api/families/$fid/hubs") { header("authorization", "Bearer $access") }
    if (resp.status.value != 200) throw AuthHttpException(resp.status.value, "family-hubs")
    return json.decodeFromString(ListSerializer(Hub.serializer()), resp.bodyAsText())
  }

  suspend fun audience(access: String, fid: String, hubId: String): HubAudience {
    val resp = http.get("$api/families/$fid/hubs/$hubId/audience") { header("authorization", "Bearer $access") }
    if (resp.status.value != 200) throw AuthHttpException(resp.status.value, "hub-audience")
    return json.decodeFromString(HubAudience.serializer(), resp.bodyAsText())
  }

  suspend fun hubTree(access: String, fid: String, hubId: String): HubTreeResult {
    val resp = http.get("$api/families/$fid/hubs/$hubId/tree") { header("authorization", "Bearer $access") }
    return when (resp.status.value) {
      200 -> HubTreeResult.Loaded(json.decodeFromString(HubTree.serializer(), resp.bodyAsText()))
      404 -> HubTreeResult.NotFound                         // restricted (not in audience) or deleted
      else -> throw AuthHttpException(resp.status.value, "hub-tree")  // 401 → engine refresh-and-retry
    }
  }

  // ── participant/visibility management (ADR 0053 DC2/DC4) ─────────────────────
  // Gated server-side to the hub author/co_owner (canManageHub) — a 403 here means
  // the caller isn't a manager; HubEngine surfaces it via HubManageFailed like any
  // other non-2xx. The response bodies (the upserted allow-list row / updated hub
  // row) aren't modeled here — the caller reloads the audience for the fresh roster
  // (server is truth), so only the status matters.

  /** PUT .../participants/:uid {role} — upsert one member's allow-list role. */
  suspend fun setParticipant(access: String, fid: String, hubId: String, uid: String, role: String) {
    val resp = http.put("$api/families/$fid/hubs/$hubId/participants/$uid") {
      header("authorization", "Bearer $access")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(SetParticipantReq.serializer(), SetParticipantReq(role)))
    }
    if (resp.status.value != 200) throw AuthHttpException(resp.status.value, "hub-set-participant")
  }

  /** DELETE .../participants/:uid — drop one member's allow-list row. */
  suspend fun removeParticipant(access: String, fid: String, hubId: String, uid: String) {
    val resp = http.delete("$api/families/$fid/hubs/$hubId/participants/$uid") {
      header("authorization", "Bearer $access")
    }
    if (resp.status.value !in 200..204) throw AuthHttpException(resp.status.value, "hub-remove-participant")
  }

  /** PUT .../visibility {visibility} — flip family<->restricted. */
  suspend fun setVisibility(access: String, fid: String, hubId: String, visibility: String) {
    val resp = http.put("$api/families/$fid/hubs/$hubId/visibility") {
      header("authorization", "Bearer $access")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(SetVisibilityReq.serializer(), SetVisibilityReq(visibility)))
    }
    if (resp.status.value != 200) throw AuthHttpException(resp.status.value, "hub-set-visibility")
  }
}

@Serializable private data class SetParticipantReq(val role: String)
@Serializable private data class SetVisibilityReq(val visibility: String)

sealed interface HubTreeResult {
  data class Loaded(val tree: HubTree) : HubTreeResult
  data object NotFound : HubTreeResult
}
