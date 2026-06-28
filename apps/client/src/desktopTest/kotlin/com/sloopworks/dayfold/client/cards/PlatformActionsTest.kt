package com.sloopworks.dayfold.client.cards

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Pure vetting logic for the platform effect layer (CL-PLAT). Security core:
// scheme allowlist, mailto address-only vetting, phone allowlist, geo encoding.
class PlatformActionsTest {

  @Test fun `openUrl is https-only`() {
    assertEquals("https://x.org/f", cardActionUri(CardAction.OpenUrl("https://x.org/f")))
    assertNull(cardActionUri(CardAction.OpenUrl("http://x.org")))        // plain http dropped
    assertNull(cardActionUri(CardAction.OpenUrl("javascript:alert(1)"))) // injection dropped
    assertNull(cardActionUri(CardAction.OpenUrl("file:///etc/passwd")))
    assertEquals("https://x.org", cardActionUri(CardAction.OpenUrl(" https://x.org"))) // leading space trimmed
  }

  @Test fun `call and message sanitize the phone to + and digits`() {
    assertEquals("tel:+15551234567", cardActionUri(CardAction.Call("+1 (555) 123-4567")))
    assertEquals("sms:5551234567", cardActionUri(CardAction.Message("555.123.4567")))
    // DTMF / USSD / pause chars dropped (no auto-dial abuse)
    assertEquals("tel:1234", cardActionUri(CardAction.Call("12,34#*p")))
    assertNull(cardActionUri(CardAction.Call("no digits here")))
  }

  @Test fun `mailto is address-only, params and injection rejected`() {
    assertEquals("mailto:a@x.com", cardActionUri(CardAction.Email("mailto:a@x.com")))
    // params dropped (kept clean address)
    assertEquals("mailto:a@x.com", cardActionUri(CardAction.Email("mailto:a@x.com?subject=hi&body=yo")))
    assertNull(cardActionUri(CardAction.Email("mailto:a@x.com,b@y.com")))      // multi-recipient
    assertNull(cardActionUri(CardAction.Email("mailto:a@x.com\r\ncc:v@e.com"))) // CRLF header injection
    assertNull(cardActionUri(CardAction.Email("mailto:notanemail")))            // no @
    assertNull(cardActionUri(CardAction.Email("https://x.org")))                // wrong scheme
  }

  // The plain \r\n CRLF case is covered above; these lock the OTHER injection
  // guards vetMailto enforces — percent-encoded CRLF/header smuggling (the literal
  // '%' reject, which survives a whitespace-only check) and angle-addr <> framing.
  @Test fun `mailto rejects percent-encoded and angle-addr injection vectors`() {
    assertNull(cardActionUri(CardAction.Email("mailto:a@x.com%0D%0Acc:v@e.com"))) // %-encoded CRLF
    assertNull(cardActionUri(CardAction.Email("mailto:a@x.com%20")))              // %-encoded space
    assertNull(cardActionUri(CardAction.Email("mailto:<a@x.com>")))               // angle-addr framing
  }

  @Test fun `navigate is a percent-encoded geo query, never coordinates`() {
    assertEquals("geo:0,0?q=Riverside%20Park", cardActionUri(CardAction.Navigate("Riverside Park")))
    // multibyte UTF-8 percent-encoded (café), space → %20 not +
    assertEquals("geo:0,0?q=caf%C3%A9", cardActionUri(CardAction.Navigate("café")))
    assertNull(cardActionUri(CardAction.Navigate("   ")))
  }

  @Test fun `copy share and openDetail are not URI handoffs`() {
    assertNull(cardActionUri(CardAction.Copy("x")))
    assertNull(cardActionUri(CardAction.Share("x")))
    assertNull(cardActionUri(CardAction.OpenDetail("id")))
  }

  @Test fun `sanitizePhone allowlist`() {
    assertEquals("+15551234567", sanitizePhone("+1 555-123-4567"))
    assertEquals("8005551234", sanitizePhone("800.555.1234"))
    assertNull(sanitizePhone("###"))
    assertNull(sanitizePhone(""))
  }

  @Test fun `percentEncode keeps unreserved, encodes the rest as UTF-8`() {
    assertEquals("Aa1-_.~", percentEncode("Aa1-_.~"))
    assertEquals("a%20b", percentEncode("a b"))
    assertEquals("%E6%97%A5", percentEncode("日"))
  }

  @Test fun `percentEncode encodes URI-injection chars (no geo query smuggling)`() {
    // a Navigate label is authored content dropped into geo:0,0?q=<label>; &, =, #, and
    // CR/LF must encode so a label can't smuggle a second query param, a fragment, or a line.
    assertEquals("a%26q%3Devil", percentEncode("a&q=evil"))   // & and = → no extra param
    assertEquals("x%23frag", percentEncode("x#frag"))          // # → no fragment
    assertEquals("a%0D%0Ab", percentEncode("a\r\nb"))          // CRLF → no line smuggling
    // and end-to-end through the geo action
    assertEquals("geo:0,0?q=Park%20%26%20Rec", cardActionUri(CardAction.Navigate("Park & Rec")))
  }

  @Test fun `vettedOpenUri allows only allowlisted schemes`() {
    // allowlisted → returned (trimmed)
    assertEquals("tel:+15551234567", vettedOpenUri("tel:+15551234567"))
    assertEquals("mailto:a@b.com", vettedOpenUri("mailto:a@b.com"))
    assertEquals("https://x.com", vettedOpenUri("https://x.com"))
    assertEquals("geo:0,0?q=x", vettedOpenUri("geo:0,0?q=x"))
    assertEquals("sms:+15551234567", vettedOpenUri("sms:+15551234567"))
    // disallowed → null (defense-in-depth; never opened)
    assertNull(vettedOpenUri("javascript:alert(1)"))
    assertNull(vettedOpenUri("data:text/html,x"))
    assertNull(vettedOpenUri("http://x.com")) // http never allowed (https only)
  }

  @Test fun `vettedOpenUri is evasion-resistant — scheme is normalized (trim + case)`() {
    // the inline-tap defense-in-depth must not be bypassable by casing/whitespace
    // (separate seam from the render-time markdown guard; both use schemeOf).
    assertEquals("HTTPS://x.com", vettedOpenUri("HTTPS://x.com"))        // uppercase scheme allowed (case preserved)
    assertEquals("mailto:a@b.com", vettedOpenUri("  mailto:a@b.com  "))  // surrounding whitespace trimmed
    assertNull(vettedOpenUri("JavaScript:alert(1)"))                     // mixed-case still rejected
    assertNull(vettedOpenUri(" javascript:alert(1)"))                    // leading whitespace still rejected
  }

  @Test fun `desktop perform does not throw on any action`() {
    val pa = PlatformActions()
    // smoke: none of these should throw even with no browser/handler (runCatching)
    listOf(
      CardAction.OpenUrl("https://x.org"), CardAction.Call("+1555"), CardAction.Message("555"),
      CardAction.Email("mailto:a@x.com"), CardAction.Navigate("Park"), CardAction.Copy("c"),
      CardAction.Share("s"), CardAction.OpenDetail("id"),
    ).forEach { pa.perform(it) }
  }
}
