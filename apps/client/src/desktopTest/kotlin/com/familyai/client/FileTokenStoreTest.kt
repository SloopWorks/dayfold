package com.familyai.client

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// AUTH-S5 T3 — desktop session persistence round-trip + 0600 lockdown.
class FileTokenStoreTest {
  private fun tempFile(): File {
    val f = File.createTempFile("fad-session", ".json")
    f.delete()                 // start absent — load() must return null
    f.deleteOnExit()
    return f
  }

  @Test fun `load is null when no session has been saved`() {
    assertNull(FileTokenStore(tempFile()).load())
  }

  @Test fun `save then load round-trips the session`() {
    val f = tempFile()
    val store = FileTokenStore(f)
    val s = Session(access = "ax", refresh = "rx", userId = "u1")
    store.save(s)
    assertEquals(s, store.load())
  }

  @Test fun `saved file is locked to owner-only 0600`() {
    val f = tempFile()
    FileTokenStore(f).save(Session("a", "r"))
    val perms = PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath()))
    assertEquals("rw-------", perms)
  }

  @Test fun `clear removes the session`() {
    val f = tempFile()
    val store = FileTokenStore(f)
    store.save(Session("a", "r"))
    assertTrue(store.load() != null)
    store.clear()
    assertNull(store.load())
  }

  @Test fun `load tolerates a corrupt file (returns null, no throw)`() {
    val f = tempFile()
    f.writeText("{ not json")
    assertNull(FileTokenStore(f).load())
  }
}
