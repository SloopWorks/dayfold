import com.sloopworks.dayfold.cli.linkifyPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkifyPayloadTest {
  @Test fun rewrites_card_body_md_and_reports_diff() {
    val r = linkifyPayload("""{"id":"x","kind":"info","body_md":"call 555-123-4567"}""")
    assertTrue(r.json.contains("[555-123-4567](tel:+15551234567)"))
    assertEquals(1, r.diffs.size)
  }

  @Test fun rewrites_nested_block_body_md() {
    val r = linkifyPayload("""{"sections":[{"blocks":[{"body_md":"a@b.com"}]}]}""")
    assertTrue(r.json.contains("[a@b.com](mailto:a@b.com)"))
  }

  @Test fun no_change_returns_empty_diff() {
    val r = linkifyPayload("""{"body_md":"nothing here"}""")
    assertTrue(r.diffs.isEmpty())
  }

  @Test fun only_body_md_is_linkified_never_title_or_structured_fields() {
    // A phone in a title, or a contact's STRUCTURED payload.phone, must stay PLAIN —
    // linkify targets body_md ONLY. Linkifying a structured field would corrupt it (the
    // renderer formats payload.phone itself; a markdown link in that slot is wrong).
    val card = """{"id":"x","kind":"info","title":"Call 555-123-4567 today",""" +
      """"payload":{"phone":"888-555-0100","email":"a@b.com"},"body_md":"reach us"}"""
    val r = linkifyPayload(card)
    assertTrue(r.diffs.isEmpty())                       // body_md ("reach us") has no entities → no change
    assertFalse(r.json.contains("tel:"), "a phone outside body_md was linkified")
    assertFalse(r.json.contains("mailto:"), "an email outside body_md was linkified")
    assertFalse(r.json.contains("]("), "a markdown link was created outside body_md")
  }

  @Test fun reports_longest_linkified_body_for_per_field_cap() {
    val r = linkifyPayload("""{"body_md":"call 555-123-4567"}""")
    assertEquals("call [555-123-4567](tel:+15551234567)".length, r.maxBodyLen)
  }

  // End-to-end (Task 4): a realistic card body with a phone, an email, and a URL
  // whose path contains phone-like digits. Phone+email link; the in-URL digits don't.
  @Test fun e2e_realistic_card_links_phone_email_skips_url_and_is_idempotent() {
    val card = """{"id":"01J0000000000000000000TEST","kind":"info","title":"t",""" +
      """"body_md":"Call 555-123-4567 or email coach@school.edu. Field: https://maps.x/o/5551234567",""" +
      """"provenance":{"source":"user","at":"2026-06-27T00:00:00Z"}}"""
    val r = linkifyPayload(card)
    assertTrue(r.json.contains("[555-123-4567](tel:+15551234567)"), "phone linked")
    assertTrue(r.json.contains("[coach@school.edu](mailto:coach@school.edu)"), "email linked")
    assertTrue(r.json.contains("https://maps.x/o/5551234567"), "url preserved")
    assertFalse(r.json.contains("o/[5551234567]"), "phone-in-url NOT linked")
    assertEquals(1, r.diffs.size) // one body_md field changed → one before/after pair
    // idempotent at the payload level: re-linkify the output → byte-identical
    assertEquals(r.json, linkifyPayload(r.json).json)
  }
}
