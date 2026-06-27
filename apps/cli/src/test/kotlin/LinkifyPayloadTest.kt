import com.sloopworks.dayfold.cli.linkifyPayload
import kotlin.test.Test
import kotlin.test.assertEquals
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

  @Test fun reports_longest_linkified_body_for_per_field_cap() {
    val r = linkifyPayload("""{"body_md":"call 555-123-4567"}""")
    assertEquals("call [555-123-4567](tel:+15551234567)".length, r.maxBodyLen)
  }
}
