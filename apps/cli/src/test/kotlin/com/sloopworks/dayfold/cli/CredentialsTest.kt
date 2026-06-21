package com.sloopworks.dayfold.cli
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

class CredentialsTest {
  @Test fun `save then load round-trips and file is 0600`() {
    val tmp = Files.createTempDirectory("fad-cli").resolve("credentials.json")
    val store = Credentials(tmp)
    store.save(Creds(v = 1, api = "http://x", accessToken = "a", refreshToken = "r", familyId = "fam1", obtainedAt = "t"))
    val got = store.load()!!
    assertEquals("fam1", got.familyId)
    assertEquals("a", got.accessToken)
    val perms = Files.getPosixFilePermissions(tmp)
    assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms)
  }
  @Test fun `delete removes the file and load returns null`() {
    val tmp = Files.createTempDirectory("fad-cli").resolve("credentials.json")
    val store = Credentials(tmp)
    store.save(Creds(1, "http://x", "a", "r", "fam1", "t"))
    store.delete()
    assertNull(store.load())
  }
}
