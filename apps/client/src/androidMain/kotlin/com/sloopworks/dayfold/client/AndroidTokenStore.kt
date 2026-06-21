package com.sloopworks.dayfold.client

import android.content.Context
import kotlinx.serialization.json.Json

// Android TokenStore — slice-1 uses private SharedPreferences. FOLLOW: move the
// refresh token to EncryptedSharedPreferences / Tink (a tracked S5 follow) before
// any non-dogfood build; private-mode prefs are app-sandboxed but not encrypted.
class AndroidTokenStore(
  context: Context,
  private val json: Json = Json { ignoreUnknownKeys = true },
) : TokenStore {
  private val prefs = context.getSharedPreferences("dayfold_session", Context.MODE_PRIVATE)
  private val key = "session"

  override fun load(): Session? =
    prefs.getString(key, null)?.let { runCatching { json.decodeFromString(Session.serializer(), it) }.getOrNull() }

  override fun save(session: Session) {
    prefs.edit().putString(key, json.encodeToString(Session.serializer(), session)).apply()
  }

  override fun clear() {
    prefs.edit().remove(key).apply()
  }
}
