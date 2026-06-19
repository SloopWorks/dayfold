package com.familyai.client

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import org.reduxkotlin.Store

// Pulls /sync (M0 household token) and dispatches the delta into the store.
// ktor-client = cross-platform HTTP (cio desktop · okhttp android · darwin iOS),
// so this stays in commonMain (was java.net, JVM-only). suspend → the shells call
// it directly inside their LaunchedEffect coroutine (no Dispatchers.IO wrap).
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

  suspend fun sync(store: Store<AppState>) {
    store.dispatch(SyncStarted)
    try {
      // [F4] drain all pages — each SyncSucceeded advances the cursor on commit.
      do {
        val cursor = store.state.cursor
        val resp = http.get("$api/families/$familyId/sync") {
          if (cursor != null) parameter("since", cursor)
          header("authorization", "Bearer $secret")
        }
        if (resp.status.value != 200) {
          store.dispatch(SyncFailed("HTTP ${resp.status.value}")); return
        }
        val parsed = json.decodeFromString(SyncResponse.serializer(), resp.bodyAsText())
        store.dispatch(SyncSucceeded(parsed))
        if (!parsed.hasMore) break
      } while (true)
    } catch (e: Exception) {
      store.dispatch(SyncFailed(e.message ?: "sync error"))
    }
  }
}
