package com.familyai.client

import kotlinx.serialization.json.Json
import org.reduxkotlin.Store
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// Pulls /sync (M0 household token) and dispatches the delta into the store.
// HttpURLConnection works on BOTH desktop JVM and Android (java.net.http is
// JVM-only) so this stays in the shared source. The KMP/ktor swap is a later
// cleanup; the contract is identical.
class SyncClient(
  private val api: String,
  private val familyId: String,
  private val secret: String,
  private val json: Json = Json { ignoreUnknownKeys = true },
) {
  fun sync(store: Store<AppState>) {
    store.dispatch(SyncStarted)
    try {
      // [F4] drain all pages — each SyncSucceeded advances the cursor on commit.
      do {
        val cursor = store.state.cursor
        val qs = cursor?.let { "?since=" + URLEncoder.encode(it, "UTF-8") } ?: ""
        val conn = (URL("$api/families/$familyId/sync$qs").openConnection() as HttpURLConnection).apply {
          requestMethod = "GET"
          setRequestProperty("authorization", "Bearer $secret")
          connectTimeout = 10_000
          readTimeout = 10_000
        }
        val code = conn.responseCode
        if (code != 200) {
          conn.disconnect(); store.dispatch(SyncFailed("HTTP $code")); return
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val resp = json.decodeFromString(SyncResponse.serializer(), body)
        store.dispatch(SyncSucceeded(resp))
        if (!resp.hasMore) break
      } while (true)
    } catch (e: Exception) {
      store.dispatch(SyncFailed(e.message ?: "sync error"))
    }
  }
}
