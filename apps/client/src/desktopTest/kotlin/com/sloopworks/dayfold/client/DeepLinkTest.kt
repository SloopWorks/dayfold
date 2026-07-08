package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// AUTH-S6-D Phase 2 — parseDeviceCode is fed untrusted scans/links; pin the
// extraction + the false-positive guards.
class DeepLinkTest {
  @Test fun `full device link yields the user_code`() {
    assertEquals("WDJF-7K2P", parseDeviceCode("https://api.dayfold.app/device?user_code=WDJF-7K2P"))
  }

  @Test fun `user_code among other params, any order`() {
    assertEquals("WDJF-7K2P", parseDeviceCode("https://x/device?foo=1&user_code=WDJF-7K2P&bar=2"))
    assertEquals("WDJF-7K2P", parseDeviceCode("https://x/device?user_code=WDJF-7K2P#section"))
  }

  @Test fun `bare code is tolerated and normalized (lowercase, dash optional)`() {
    assertEquals("WDJF-7K2P", parseDeviceCode("wdjf-7k2p"))
    assertEquals("WDJF-7K2P", parseDeviceCode("WDJF7K2P"))
    assertEquals("WDJF-7K2P", parseDeviceCode("  WDJF-7K2P  "))
  }

  @Test fun `a URL without a user_code is null, never a code minted from URL text`() {
    assertNull(parseDeviceCode("https://api.dayfold.app/device"))
    assertNull(parseDeviceCode("https://api.dayfold.app/"))
  }

  @Test fun `wrong-length or empty input is null`() {
    assertNull(parseDeviceCode(""))
    assertNull(parseDeviceCode("ABC"))                                  // too short
    assertNull(parseDeviceCode("https://x/device?user_code=AB12"))      // too short in query
  }

  @Test fun `extra chars after the code are tolerated to 8 alnum`() {
    assertEquals("WDJF-7K2P", parseDeviceCode("https://x/device?user_code=WDJF-7K2P-extra"))
  }

  // ── invite deep-link (ADR 0048) ──
  @Test fun `invite link yields the token`() {
    assertEquals("TOK_abc123", parseInviteToken("https://family-ai-dashboard.vercel.app/invite/TOK_abc123"))
  }

  @Test fun `invite token strips query and fragment`() {
    assertEquals("TOK_abc123", parseInviteToken("https://x/invite/TOK_abc123?ref=sms"))
    assertEquals("TOK_abc123", parseInviteToken("https://x/invite/TOK_abc123#x"))
  }

  @Test fun `bare invite token is tolerated`() {
    assertEquals("TOK_abc123", parseInviteToken("TOK_abc123"))
  }

  @Test fun `a non-invite URL yields no token`() {
    assertNull(parseInviteToken("https://x/device?user_code=WDJF-7K2P"))  // never a false token
    assertNull(parseInviteToken("https://x/invite/"))                      // empty token
  }
}
