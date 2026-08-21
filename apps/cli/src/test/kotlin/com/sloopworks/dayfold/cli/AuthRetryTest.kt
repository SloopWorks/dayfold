package com.sloopworks.dayfold.cli

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The CLI's auth-orchestration rule: **one transparent refresh on 401, and only when
 * the caller actually holds a refreshable device credential.**
 *
 * This layer had no direct coverage — `backlog/now.md`'s 18th-pass entry called out
 * "the untested network/auth-orchestration layer (401-retry, legacy/device branch
 * selection)" as a real gap. It stayed untestable only because `authedGet`/
 * `authedDelete`/`authedPut` were `private` and called the transport directly; they
 * now take an injected transport + refresh (defaulting to the real ones), so the rule
 * can be exercised with no network, no keychain, and no credentials file.
 *
 * What matters here, and why:
 *  - A 401 on the **legacy `HOUSEHOLD_SECRET` env path** must surface as a 401. That
 *    path has no refresh token, so attempting a rotation would be both meaningless and
 *    a way to turn a clear auth error into a confusing one.
 *  - The retry happens **at most once**. A refresh loop against a persistently-401ing
 *    server would hammer `/auth/refresh` and rotate the stored token repeatedly.
 *  - A non-401 is returned untouched — including other failures like 403/404/500, which
 *    must not trigger a credential rotation.
 */
class AuthRetryTest {

  private fun creds(api: String = "https://api.example") = Creds(
    api = api, accessToken = "access-1", refreshToken = "refresh-1",
    familyId = "fam-1", obtainedAt = "2026-08-21T00:00:00Z",
  )

  /** A `Credentials` pointed at a temp dir — never touches the real ~/.config path. */
  private fun tempStore(): Credentials =
    Credentials(Files.createTempDirectory("dayfold-authretry").resolve("credentials.json"))

  // ── GET ───────────────────────────────────────────────────────────────────

  @Test
  fun `get returns a success without refreshing`() {
    var refreshes = 0
    val calls = mutableListOf<Pair<String, String?>>()
    val (code, body) = authedGet(
      tempStore(), null, "https://api.example", "access-1", creds(), "/families/fam-1/cards",
      transport = { url, token -> calls += url to token; 200 to """{"cards":[]}""" },
      refresh = { _, _ -> refreshes++; "access-2" },
    )
    assertEquals(200, code)
    assertEquals("""{"cards":[]}""", body)
    assertEquals(0, refreshes, "a 200 must not trigger a token refresh")
    assertEquals(1, calls.size, "a 200 must not be retried")
    assertEquals("https://api.example/families/fam-1/cards" to "access-1", calls.single())
  }

  @Test
  fun `get refreshes once on 401 and retries with the new token`() {
    var refreshes = 0
    val calls = mutableListOf<Pair<String, String?>>()
    val (code, body) = authedGet(
      tempStore(), null, "https://api.example", "access-1", creds(), "/families/fam-1/cards",
      transport = { url, token ->
        calls += url to token
        if (token == "access-1") 401 to "expired" else 200 to "ok"
      },
      refresh = { _, _ -> refreshes++; "access-2" },
    )
    assertEquals(200, code)
    assertEquals("ok", body)
    assertEquals(1, refreshes)
    assertEquals(2, calls.size)
    assertEquals("access-1", calls[0].second, "first attempt uses the current access token")
    assertEquals("access-2", calls[1].second, "the retry must use the REFRESHED token, not the stale one")
    assertTrue(calls.all { it.first == "https://api.example/families/fam-1/cards" })
  }

  @Test
  fun `get retries at most once when the refreshed token is also rejected`() {
    var refreshes = 0
    var attempts = 0
    val (code, body) = authedGet(
      tempStore(), null, "https://api.example", "access-1", creds(), "/families/fam-1/cards",
      transport = { _, _ -> attempts++; 401 to "still expired" },
      refresh = { _, _ -> refreshes++; "access-2" },
    )
    // The second 401 is surfaced, not refreshed again — otherwise a persistently-401ing
    // server would loop on /auth/refresh and rotate the stored credential every pass.
    assertEquals(401, code)
    assertEquals("still expired", body)
    assertEquals(2, attempts)
    assertEquals(1, refreshes)
  }

  @Test
  fun `get on the legacy env path surfaces a 401 instead of refreshing`() {
    var refreshes = 0
    var attempts = 0
    // Legacy HOUSEHOLD_SECRET: no credential store and no refreshable creds.
    val (code, _) = authedGet(
      null, null, "https://api.example", "household-secret", null, "/families/fam-1/cards",
      transport = { _, _ -> attempts++; 401 to "unauthorized" },
      refresh = { _, _ -> refreshes++; "unreachable" },
    )
    assertEquals(401, code)
    assertEquals(1, attempts, "the legacy path has no refresh token — it must not retry")
    assertEquals(0, refreshes)
  }

  @Test
  fun `get does not refresh when creds are absent even with a store`() {
    var refreshes = 0
    val (code, _) = authedGet(
      tempStore(), null, "https://api.example", "tok", null, "/families/fam-1/cards",
      transport = { _, _ -> 401 to "unauthorized" },
      refresh = { _, _ -> refreshes++; "unreachable" },
    )
    assertEquals(401, code)
    assertEquals(0, refreshes)
  }

  @Test
  fun `get does not refresh on a non-401 failure`() {
    for (status in listOf(403, 404, 409, 500)) {
      var refreshes = 0
      var attempts = 0
      val (code, _) = authedGet(
        tempStore(), null, "https://api.example", "access-1", creds(), "/families/fam-1/cards",
        transport = { _, _ -> attempts++; status to "nope" },
        refresh = { _, _ -> refreshes++; "access-2" },
      )
      assertEquals(status, code)
      assertEquals(1, attempts, "$status must not be retried")
      assertEquals(0, refreshes, "$status must not rotate the credential")
    }
  }

  // ── DELETE ────────────────────────────────────────────────────────────────

  @Test
  fun `delete refreshes once on 401 and retries with the new token`() {
    var refreshes = 0
    val tokens = mutableListOf<String?>()
    val (code, _) = authedDelete(
      tempStore(), null, "https://api.example", "access-1", creds(), "/families/fam-1/hubs/h1",
      transport = { _, token -> tokens += token; if (token == "access-1") 401 to "expired" else 200 to "" },
      refresh = { _, _ -> refreshes++; "access-2" },
    )
    assertEquals(200, code)
    assertEquals(1, refreshes)
    assertEquals(listOf<String?>("access-1", "access-2"), tokens)
  }

  @Test
  fun `delete on the legacy env path surfaces a 401 instead of refreshing`() {
    var refreshes = 0
    var attempts = 0
    val (code, _) = authedDelete(
      null, null, "https://api.example", "household-secret", null, "/families/fam-1/hubs/h1",
      transport = { _, _ -> attempts++; 401 to "unauthorized" },
      refresh = { _, _ -> refreshes++; "unreachable" },
    )
    assertEquals(401, code)
    assertEquals(1, attempts)
    assertEquals(0, refreshes)
  }

  // ── PUT ───────────────────────────────────────────────────────────────────

  @Test
  fun `put replays the SAME body on the refreshed retry`() {
    var refreshes = 0
    val sent = mutableListOf<Pair<String, String?>>()
    val payload = """{"kind":"file","title":"Permission slip"}"""
    val (code, _) = authedPut(
      tempStore(), null, "https://api.example", "access-1", creds(),
      "/families/fam-1/cards/c1", payload,
      transport = { _, requestBody, token ->
        sent += requestBody to token
        if (token == "access-1") 401 to "expired" else 200 to "ok"
      },
      refresh = { _, _ -> refreshes++; "access-2" },
    )
    assertEquals(200, code)
    assertEquals(1, refreshes)
    assertEquals(2, sent.size)
    // A retry that dropped or mutated the body would silently push different content
    // than the author wrote — the failure this assertion exists to catch.
    assertEquals(payload, sent[0].first)
    assertEquals(payload, sent[1].first, "the refreshed retry must replay the identical payload")
    assertEquals("access-2", sent[1].second)
  }

  @Test
  fun `put on the legacy env path surfaces a 401 instead of refreshing`() {
    var refreshes = 0
    var attempts = 0
    val (code, _) = authedPut(
      null, null, "https://api.example", "household-secret", null,
      "/families/fam-1/cards/c1", "{}",
      transport = { _, _, _ -> attempts++; 401 to "unauthorized" },
      refresh = { _, _ -> refreshes++; "unreachable" },
    )
    assertEquals(401, code)
    assertEquals(1, attempts)
    assertEquals(0, refreshes)
  }

  @Test
  fun `put does not refresh on a non-401 failure`() {
    var refreshes = 0
    val (code, body) = authedPut(
      tempStore(), null, "https://api.example", "access-1", creds(),
      "/families/fam-1/cards/c1", "{}",
      transport = { _, _, _ -> 409 to "muted subject" },
      refresh = { _, _ -> refreshes++; "access-2" },
    )
    // 409 is the ADR 0064 muted-subject rejection — a legitimate answer, not an auth
    // problem. Rotating the credential here would hide a product signal behind a retry.
    assertEquals(409, code)
    assertEquals("muted subject", body)
    assertEquals(0, refreshes)
  }
}
