package com.familyai.client

import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

// Desktop TokenStore — a single JSON file, locked to 0600 (owner read/write).
// Injected at the desktop entrypoint with ~/.family-ai-dashboard/session.json;
// the file path is a ctor arg so tests can use a temp file.
class FileTokenStore(
  private val file: File,
  private val json: Json = Json { ignoreUnknownKeys = true },
) : TokenStore {

  override fun load(): Session? {
    if (!file.exists()) return null
    return runCatching { json.decodeFromString(Session.serializer(), file.readText()) }.getOrNull()
  }

  override fun save(session: Session) {
    file.parentFile?.mkdirs()
    file.writeText(json.encodeToString(Session.serializer(), session))
    // Best-effort 0600 — the refresh token is the long-lived secret.
    runCatching {
      Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
    }
  }

  override fun clear() {
    file.delete()
  }
}
