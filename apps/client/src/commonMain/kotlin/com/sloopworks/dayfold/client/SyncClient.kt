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
// AUTH-S5: family + bearer are now PROVIDERS, read fresh per request — the active
// family is only known after sign-in, and the bearer is the session access token
// (rotated by AuthEngine on refresh). The entrypoint supplies the legacy
// HOUSEHOLD_SECRET / FAMILY_ID via the same providers as a dev fallback until the
// S3 cutover. fetchPage returns an empty page when no family/token is set yet, so
// the poll loop can run idle before onboarding completes.
class SyncClient(
  private val api: String,
  private val familyId: () -> String?,
  private val token: () -> String?,
  private val http: HttpClient = HttpClient(),
  private val json: Json = Json { ignoreUnknownKeys = true },
) {
  /** Transport only: GET one /sync page. Throws on non-200 or network error. */
  suspend fun fetchPage(since: String?): SyncResponse {
    val fam = familyId()
    val tok = token()
    if (fam.isNullOrEmpty() || tok.isNullOrEmpty()) return SyncResponse()   // not signed in yet
    val resp = http.get("$api/families/$fam/sync") {
      if (since != null) parameter("since", since)
      header("authorization", "Bearer $tok")
    }
    if (resp.status.value != 200) throw SyncHttpException(resp.status.value)
    return json.decodeFromString(SyncResponse.serializer(), resp.bodyAsText())
  }

  /**
   * Egress (ADR 0038 §6.2): PUT one whole block with If-Match (the optimistic-concurrency
   * base) + Idempotency-Key (the op_id). Returns the HTTP status + (on 200) the new server
   * version, so the sender's OutboxSender.classify can decide ack / re-merge / drop / backoff.
   * Returns status=null when not signed in. Network errors propagate (the sender treats a
   * thrown call as a transient/network outcome).
   */
  suspend fun putBlock(blockId: String, body: String, baseVersion: Long?, opId: String): PutResult {
    val fam = familyId()
    val tok = token()
    if (fam.isNullOrEmpty() || tok.isNullOrEmpty()) return PutResult(null, null)
    val resp = http.put("$api/families/$fam/blocks/$blockId") {
      header("authorization", "Bearer $tok")
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
   * Egress (ADR 0038 §W4): DELETE one block with an Idempotency-Key (the op_id) and no body /
   * no If-Match — delete is idempotent (a re-delete returns 204) and author-gated server-side.
   * Returns the HTTP status (204 = OK, 403 = not author / no scope, 404 = absent/can't-see) so
   * OutboxSender.classify decides ack / drop / backoff. status=null when not signed in.
   */
  suspend fun deleteBlock(blockId: String, opId: String): PutResult {
    val fam = familyId()
    val tok = token()
    if (fam.isNullOrEmpty() || tok.isNullOrEmpty()) return PutResult(null, null)
    val resp = http.delete("$api/families/$fam/blocks/$blockId") {
      header("authorization", "Bearer $tok")
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
