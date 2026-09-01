package com.sloopworks.dayfold.client

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
// Transport layer for the /sync endpoint.
// ktor-client = cross-platform HTTP (cio desktop · okhttp android · darwin iOS),
// so this stays in commonMain. fetchPage is called by SyncEngine.
//
// Family and credentials are explicit request arguments. SyncEngine captures one
// FamilySessionContext for a complete drain, preventing independent state reads
// from combining one family's URL with another identity's bearer token.
class SyncClient(
  private val api: String,
  private val http: HttpClient = HttpClient(),
  private val json: Json = Json { ignoreUnknownKeys = true },
) {
  /**
   * Transport-only page fetch using one caller-captured tenant/session snapshot.
   * Runtime-owned sync uses this overload so a multi-page drain cannot combine a
   * family read from one Redux version with a token read from another.
   */
  suspend fun fetchPage(familyId: String, accessToken: String, since: String?): SyncResponse {
    val resp = http.get("$api/families/$familyId/sync") {
      if (since != null) parameter("since", since)
      header("authorization", "Bearer $accessToken")
    }
    if (resp.status.value != 200) throw SyncHttpException(resp.status.value)
    return json.decodeFromString(SyncResponse.serializer(), resp.bodyAsText())
  }

  /**
   * Egress (ADR 0038 §6.2): PUT one caller-captured family/session snapshot with
   * If-Match (the optimistic-concurrency base) and Idempotency-Key (the op ID).
   */
  suspend fun putBlock(
    familyId: String,
    accessToken: String,
    blockId: String,
    body: String,
    baseVersion: Long?,
    opId: String,
  ): PutResult {
    val resp = http.put("$api/families/$familyId/blocks/$blockId") {
      header("authorization", "Bearer $accessToken")
      header("content-type", "application/json")
      if (baseVersion != null) header("if-match", baseVersion.toString())
      header("idempotency-key", opId)
      setBody(body)
    }
    val status = resp.status.value
    val version = if (status == 200) runCatching {
      json.parseToJsonElement(resp.bodyAsText()).jsonObject["version"]?.jsonPrimitive?.longOrNull
    }.getOrNull() else null
    return PutResult(status, version)
  }

  /**
   * CAL-10 (ADR 0063 §6, import contract spec §3.1) — PUT one Hub. Used only by the outbox
   * sender's "hub" dispatch arm; `baseVersion` is always null for that caller (a client-minted,
   * not-yet-existing row — spec §3.4), but the parameter mirrors [putBlock] for consistency.
   */
  suspend fun putHub(
    familyId: String,
    accessToken: String,
    hubId: String,
    body: String,
    baseVersion: Long?,
    opId: String,
  ): PutResult {
    val resp = http.put("$api/families/$familyId/hubs/$hubId") {
      header("authorization", "Bearer $accessToken")
      header("content-type", "application/json")
      if (baseVersion != null) header("if-match", baseVersion.toString())
      header("idempotency-key", opId)
      setBody(body)
    }
    val status = resp.status.value
    val version = if (status == 200) runCatching {
      json.parseToJsonElement(resp.bodyAsText()).jsonObject["version"]?.jsonPrimitive?.longOrNull
    }.getOrNull() else null
    return PutResult(status, version)
  }

  /** CAL-10 (ADR 0063 §6, import contract spec §3.1) — PUT one Hub section. Same posture as [putHub]. */
  suspend fun putSection(
    familyId: String,
    accessToken: String,
    sectionId: String,
    body: String,
    baseVersion: Long?,
    opId: String,
  ): PutResult {
    val resp = http.put("$api/families/$familyId/sections/$sectionId") {
      header("authorization", "Bearer $accessToken")
      header("content-type", "application/json")
      if (baseVersion != null) header("if-match", baseVersion.toString())
      header("idempotency-key", opId)
      setBody(body)
    }
    val status = resp.status.value
    val version = if (status == 200) runCatching {
      json.parseToJsonElement(resp.bodyAsText()).jsonObject["version"]?.jsonPrimitive?.longOrNull
    }.getOrNull() else null
    return PutResult(status, version)
  }

  /** Member-reviewed Calendar field resolution on an existing briefing card. */
  suspend fun putCard(
    familyId: String,
    accessToken: String,
    cardId: String,
    body: String,
    opId: String,
  ): PutResult {
    val resp = http.put("$api/families/$familyId/cards/$cardId") {
      header("authorization", "Bearer $accessToken")
      header("content-type", "application/json")
      header("idempotency-key", opId)
      setBody(body)
    }
    val status = resp.status.value
    val version = if (status == 200) runCatching {
      json.parseToJsonElement(resp.bodyAsText()).jsonObject["version"]?.jsonPrimitive?.longOrNull
    }.getOrNull() else null
    return PutResult(status, version)
  }

  /**
   * Egress (ADR 0038 §W4): DELETE one block with an Idempotency-Key and no body
   * or If-Match. The caller supplies one captured family/session snapshot.
   */
  suspend fun deleteBlock(
    familyId: String,
    accessToken: String,
    blockId: String,
    opId: String,
  ): PutResult {
    val resp = http.delete("$api/families/$familyId/blocks/$blockId") {
      header("authorization", "Bearer $accessToken")
      header("idempotency-key", opId)
    }
    return PutResult(resp.status.value, null)
  }

  /**
   * Egress (ADR 0064): PUT one response row. No If-Match — a response has no local merge to
   * re-run and the server's identity columns are immutable after creation, so a stale base
   * has nothing to clobber. The Idempotency-Key still matters: a drained retry must not
   * create a second rule.
   */
  suspend fun putResponse(
    familyId: String,
    accessToken: String,
    responseId: String,
    body: String,
    opId: String,
  ): PutResult {
    val resp = http.put("$api/families/$familyId/responses/$responseId") {
      header("authorization", "Bearer $accessToken")
      header("content-type", "application/json")
      header("idempotency-key", opId)
      setBody(body)
    }
    val status = resp.status.value
    val version = if (status == 200) runCatching {
      json.parseToJsonElement(resp.bodyAsText()).jsonObject["version"]?.jsonPrimitive?.longOrNull
    }.getOrNull() else null
    return PutResult(status, version)
  }

  /** Egress (ADR 0064): DELETE one response row. Idempotent server-side — absent is still 204. */
  suspend fun deleteResponse(
    familyId: String,
    accessToken: String,
    responseId: String,
    opId: String,
  ): PutResult {
    val resp = http.delete("$api/families/$familyId/responses/$responseId") {
      header("authorization", "Bearer $accessToken")
      header("idempotency-key", opId)
    }
    return PutResult(resp.status.value, null)
  }
}

/** Result of an egress PUT — the status drives the sender state machine; version (on 200)
 *  is stored for echo-suppression. */
data class PutResult(val status: Int?, val version: Long?)

/** Non-200 from /sync, carrying the status so the engine can distinguish a tenancy
 *  revocation (403 removed / 404 non-member → wipe the cache) from a transient
 *  error or a token problem (401 → handled by refresh). */
class SyncHttpException(val status: Int) : Exception("HTTP $status")
