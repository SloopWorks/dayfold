package com.sloopworks.dayfold.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission

@Serializable
data class Creds(val v: Int = 1, val api: String, val accessToken: String, val refreshToken: String, val familyId: String, val obtainedAt: String)

class Credentials(private val file: Path = defaultPath()) {
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
  companion object {
    fun defaultPath(): Path = Path.of(System.getProperty("user.home"), ".config", "dayfold", "credentials.json")
  }
  fun path(): Path = file
  fun load(): Creds? = if (Files.exists(file)) runCatching { json.decodeFromString<Creds>(Files.readString(file)) }.getOrNull() else null
  fun save(c: Creds) {
    Files.createDirectories(file.parent)
    val tmp = file.resolveSibling("credentials.json.tmp")
    Files.writeString(tmp, json.encodeToString(Creds.serializer(), c))
    runCatching { Files.setPosixFilePermissions(tmp, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)) }
    Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    runCatching { Files.setPosixFilePermissions(file, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)) }
  }
  fun delete() { Files.deleteIfExists(file) }
  fun <T> withRefreshLock(block: () -> T): T {
    Files.createDirectories(file.parent)
    val lock = file.resolveSibling("refresh.lock")
    FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { ch ->
      ch.lock().use { return block() }
    }
  }
}
