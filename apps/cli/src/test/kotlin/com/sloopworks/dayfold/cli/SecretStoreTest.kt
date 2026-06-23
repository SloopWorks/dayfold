package com.sloopworks.dayfold.cli

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

// AUTH-S6-D 7b — the keychain glue: the refresh token must leave the plaintext
// file and live in the OS keychain; the file keeps only the access token + config.
class SecretStoreTest {
  // in-memory keychain
  private class FakeStore(val m: MutableMap<String, String> = mutableMapOf()) : SecretStore {
    override fun get(account: String) = m[account]
    override fun put(account: String, secret: String) { m[account] = secret }
    override fun delete(account: String) { m.remove(account) }
  }

  private fun tempCreds() = Credentials(Files.createTempFile("creds", ".json"))
  private fun creds(refresh: String = "refresh-xyz") =
    Creds(api = "http://localhost", accessToken = "acc", refreshToken = refresh, familyId = "fam1", obtainedAt = "t0")

  @Test fun `saveCreds puts the refresh in the keychain and blanks it in the file`() {
    val store = tempCreds(); val kc = FakeStore()
    saveCreds(store, kc, creds("R1"))
    assertEquals("R1", kc.get(KEYCHAIN_ACCOUNT))            // keychain holds the secret
    assertEquals("", store.load()!!.refreshToken)          // file does NOT
    assertEquals("acc", store.load()!!.accessToken)        // non-secret config stays
  }

  @Test fun `loadCreds merges the refresh back from the keychain`() {
    val store = tempCreds(); val kc = FakeStore()
    saveCreds(store, kc, creds("R2"))
    assertEquals("R2", loadCreds(store, kc)!!.refreshToken)
  }

  @Test fun `loadCreds migrates a pre-7b file refresh when the keychain is empty`() {
    val store = tempCreds(); val kc = FakeStore()
    store.save(creds("LEGACY"))                            // old path: refresh in the file
    assertEquals("LEGACY", loadCreds(store, kc)!!.refreshToken)   // keychain empty → file value
  }

  @Test fun `without a keychain the file holds the refresh (allow-env-key path)`() {
    val store = tempCreds()
    saveCreds(store, null, creds("R3"))
    assertEquals("R3", store.load()!!.refreshToken)
    assertEquals("R3", loadCreds(store, null)!!.refreshToken)
  }

  @Test fun `deleteCreds clears both the file and the keychain`() {
    val store = tempCreds(); val kc = FakeStore()
    saveCreds(store, kc, creds("R4"))
    deleteCreds(store, kc)
    assertNull(store.load())
    assertNull(kc.get(KEYCHAIN_ACCOUNT))
  }

  // ── MacKeychain argv + output parsing, against a fake runner (no real keychain) ──
  @Test fun `MacKeychain get builds the find argv and trims output`() {
    var seen: List<String> = emptyList()
    val mac = MacKeychain { cmd, _ -> seen = cmd; ProcResult(0, "secret-value\n") }
    assertEquals("secret-value", mac.get("refresh-token"))
    assertTrue(seen.containsAll(listOf("/usr/bin/security", "find-generic-password", "-s", "dayfold-cli", "-a", "refresh-token", "-w")), "argv was: $seen")
  }

  @Test fun `MacKeychain get returns null on a miss (non-zero exit)`() {
    val mac = MacKeychain { _, _ -> ProcResult(44, "") }
    assertNull(mac.get("refresh-token"))
  }

  @Test fun `MacKeychain put passes -U and the secret, and throws on failure`() {
    var seen: List<String> = emptyList()
    MacKeychain { cmd, _ -> seen = cmd; ProcResult(0, "") }.put("refresh-token", "S3CRET")
    assertTrue(seen.contains("-U") && seen.contains("S3CRET"), "argv was: $seen")
    assertFailsWith<IllegalStateException> { MacKeychain { _, _ -> ProcResult(1, "") }.put("a", "b") }
  }

  @Test fun `LibSecret put feeds the secret on stdin (not argv)`() {
    var seenStdin: String? = null; var seen: List<String> = emptyList()
    LibSecret { cmd, stdin -> seen = cmd; seenStdin = stdin; ProcResult(0, "") }.put("refresh-token", "S3CRET")
    assertEquals("S3CRET", seenStdin)                       // secret on stdin
    assertTrue(seen.none { it == "S3CRET" }, "secret leaked into argv: $seen")
    assertTrue(seen.containsAll(listOf("secret-tool", "store", "service", "dayfold-cli", "account", "refresh-token")), "argv was: $seen")
  }
}
