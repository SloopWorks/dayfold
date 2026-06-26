package com.sloopworks.dayfold.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WhoamiTest {
  @Test fun `not signed in (no device creds, no legacy token) gives actionable guidance, not blanks`() {
    val s = whoamiStatus(signedInDevice = false, hasToken = false, family = "", api = "")
    assertTrue(s.startsWith("not signed in"), "got: $s")
    assertTrue(s.contains("dayfold login"), "should point at login; got: $s")
    assertTrue(!s.contains("family="), "must not print the blank family= line; got: $s")
  }

  @Test fun `device login reports the family on the device path`() {
    assertEquals(
      "family=fam1 api=https://api (device)",
      whoamiStatus(signedInDevice = true, hasToken = true, family = "fam1", api = "https://api"),
    )
  }

  @Test fun `legacy token (env path, no device login) reports the family on the legacy path`() {
    assertEquals(
      "family=fam1 api=https://api (legacy)",
      whoamiStatus(signedInDevice = false, hasToken = true, family = "fam1", api = "https://api"),
    )
  }

  // pull/push guard: only guide to login when there's NEITHER a device login NOR the
  // legacy DAYFOLD_API env — otherwise let the normal path (or env() ) proceed.
  @Test fun `guide to login only when neither device login nor legacy env is present`() {
    assertTrue(shouldGuideToLogin(signedInDevice = false, hasLegacyApiEnv = false))   // fresh user → guide
    assertEquals(false, shouldGuideToLogin(signedInDevice = true, hasLegacyApiEnv = false))   // logged in → no
    assertEquals(false, shouldGuideToLogin(signedInDevice = false, hasLegacyApiEnv = true))   // legacy env path → no (env() validates the rest)
  }
}
