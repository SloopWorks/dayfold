import com.sloopworks.dayfold.client.cards.linkify
import kotlin.test.Test
import kotlin.test.assertEquals

class LinkifyTest {
  private fun fixpoint(s: String) = assertEquals(linkify(s), linkify(linkify(s)), "not idempotent: $s")

  // ── phone true positives → tel:+1XXXXXXXXXX ──
  @Test fun phones_link() {
    assertEquals("[555-123-4567](tel:+15551234567)", linkify("555-123-4567"))
    assertEquals("[(555) 123-4567](tel:+15551234567)", linkify("(555) 123-4567"))
    assertEquals("[+1 555 123 4567](tel:+15551234567)", linkify("+1 555 123 4567"))
    assertEquals("[555.123.4567](tel:+15551234567)", linkify("555.123.4567"))
    // already 11-digit with country code → no double +1
    assertEquals("[1-555-123-4567](tel:+15551234567)", linkify("1-555-123-4567"))
  }

  // ── phone false positives → untouched ──
  @Test fun phone_false_positives_untouched() {
    for (s in listOf("2026-06-27", "94103-1234", "v2.3.4567", "\$1,200-1,500", "#5551234567", "1-800-FLOWERS")) {
      assertEquals(s, linkify(s), "should not link: $s")
    }
  }

  // ── email ──
  @Test fun emails_link() {
    assertEquals("[a@b.com](mailto:a@b.com)", linkify("a@b.com"))
    assertEquals("[a@b.com](mailto:a@b.com).", linkify("a@b.com."))       // trailing dot stays outside
    assertEquals("see https://a@b.com/p", linkify("see https://a@b.com/p")) // userinfo in URL → not an email
  }

  // ── protected spans → untouched + idempotent ──
  @Test fun protected_spans() {
    val cases = listOf(
      "[555-123-4567](tel:+15551234567)",          // already linked
      "`call 555-123-4567 or a@b.com`",            // inline code
      "```\ncall 555-123-4567\n```",               // fenced code
      "![call 555-123-4567](https://x.com)",       // image alt
      "https://x.com/o/5551234567",                // bare url with phone-like path
      "<a@b.com>",                                  // angle autolink
      "\\@notmail and \\[x\\]",                     // escaped
      "[ref]: 555-123-4567",                        // reference-style link def
    )
    for (s in cases) { assertEquals(s, linkify(s), "should be untouched: $s"); fixpoint(s) }
  }

  // ── table cell phone links, structure intact ──
  @Test fun table_cell() {
    assertEquals("| [555-123-4567](tel:+15551234567) | note |", linkify("| 555-123-4567 | note |"))
  }

  @Test fun idempotent_on_mixed() {
    fixpoint("Call 555-123-4567 or email a@b.com about https://x.com/5551234567")
  }
}
