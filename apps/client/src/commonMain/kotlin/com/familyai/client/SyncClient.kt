package com.familyai.client

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
// Transport layer for the /sync endpoint (M0 household token).
// ktor-client = cross-platform HTTP (cio desktop · okhttp android · darwin iOS),
// so this stays in commonMain. fetchPage is called by SyncEngine.
class SyncClient(
  private val api: String,
  private val familyId: String,
  private val secret: String,
  private val http: HttpClient = HttpClient(),
  private val json: Json = Json { ignoreUnknownKeys = true },
) {
  /** Transport only: GET one /sync page. Throws on non-200 or network error. */
  suspend fun fetchPage(since: String?): SyncResponse {
    val resp = http.get("$api/families/$familyId/sync") {
      if (since != null) parameter("since", since)
      header("authorization", "Bearer $secret")
    }
    if (resp.status.value != 200) throw IllegalStateException("HTTP ${resp.status.value}")
    return json.decodeFromString(SyncResponse.serializer(), resp.bodyAsText())
  }
}
