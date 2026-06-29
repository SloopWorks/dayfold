package com.sloopworks.dayfold.client

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Slice 5b — the client learns its own user id from the access-token `sub` so the
// author-only delete gate (createdBy == userId) can fire. Decode-only (no verify — the
// server verifies; the client never trusts this for authz, only to shape the UI).
@OptIn(ExperimentalEncodingApi::class)
class JwtDecodeTest {
  private fun token(payloadJson: String): String {
    val p = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(payloadJson.encodeToByteArray())
    return "eyJhbGciOiJFZERTQSJ9.$p.sig"
  }

  @Test fun `jwtSub extracts the sub claim`() {
    assertEquals("u_dev", jwtSub(token("""{"sub":"u_dev","cid":"c1","exp":1893456000}""")))
  }

  @Test fun `jwtSub tolerates base64url payloads without padding`() {
    // a sub length that yields a payload needing padding — the decoder must not require it
    assertEquals("usr_abcdef", jwtSub(token("""{"sub":"usr_abcdef"}""")))
  }

  @Test fun `jwtSub returns null on a malformed or sub-less token`() {
    assertNull(jwtSub("onlyonesegment"))
    assertNull(jwtSub("not.a.jwt"))                          // middle segment isn't JSON
    assertNull(jwtSub(token("""{"cid":"c1"}""")))            // no sub claim
    assertNull(jwtSub(""))
  }
}
