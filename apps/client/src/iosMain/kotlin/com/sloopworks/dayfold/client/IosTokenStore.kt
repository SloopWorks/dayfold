package com.sloopworks.dayfold.client

import kotlinx.serialization.json.Json
import platform.Foundation.NSUserDefaults

// iOS TokenStore — slice-1 uses NSUserDefaults. FOLLOW: move the refresh token to
// the Keychain (a tracked S5 follow) before any non-dogfood build; NSUserDefaults
// is plist-backed, not the secure enclave.
class IosTokenStore(
  private val json: Json = Json { ignoreUnknownKeys = true },
) : TokenStore {
  private val defaults = NSUserDefaults.standardUserDefaults
  private val key = "dayfold_session"

  override fun load(): Session? =
    defaults.stringForKey(key)?.let { runCatching { json.decodeFromString(Session.serializer(), it) }.getOrNull() }

  override fun save(session: Session) {
    defaults.setObject(json.encodeToString(Session.serializer(), session), key)
  }

  override fun clear() {
    defaults.removeObjectForKey(key)
  }
}
