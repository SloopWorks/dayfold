package com.sloopworks.dayfold.client

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
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
}

/** Non-200 from /sync, carrying the status so the engine can distinguish a tenancy
 *  revocation (403 removed / 404 non-member → wipe the cache) from a transient
 *  error or a token problem (401 → handled by refresh). */
class SyncHttpException(val status: Int) : Exception("HTTP $status")
