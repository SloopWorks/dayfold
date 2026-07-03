package com.sloopworks.dayfold.client

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// renderBlockMarkdown — the first-cut hub-block markdown renderer (OQ-markdown-render).
// Hub bodies are authored as real markdown; this proves the visible text has its
// markers stripped + the right styling/links, instead of showing raw `**`/`-`.
class BlockMarkdownTest {
  private fun boldSpans(s: androidx.compose.ui.text.AnnotatedString) =
    s.spanStyles.count { it.item.fontWeight == FontWeight.Bold }
  private fun italicSpans(s: androidx.compose.ui.text.AnnotatedString) =
    s.spanStyles.count { it.item.fontStyle == FontStyle.Italic }

  @Test fun `bold strips the asterisks and styles the run`() {
    val out = renderBlockMarkdown("**Jul 1** — accept aid")
    assertEquals("Jul 1 — accept aid", out.text)
    assertEquals(1, boldSpans(out))
  }

  @Test fun `bullets and checkboxes get glyphs, not raw dashes`() {
    assertEquals("• Residence hall TBD", renderBlockMarkdown("- Residence hall TBD").text)
    assertEquals("☐ Submit FAFSA", renderBlockMarkdown("- [ ] Submit FAFSA").text)
    assertEquals("☑ Uploaded photo", renderBlockMarkdown("- [x] Uploaded photo").text)
    assertEquals("• a\n• b", renderBlockMarkdown("- a\n- b").text)              // multiline preserved
  }

  @Test fun `inline link is vetted like card bodies (allowed tappable, others stripped)`() {
    val ok = renderBlockMarkdown("see the [portal](https://butler.edu)")
    assertEquals("see the portal", ok.text)
    assertTrue(ok.getLinkAnnotations(0, ok.length).isNotEmpty())
    val bad = renderBlockMarkdown("[x](javascript:alert)")
    assertEquals("x", bad.text)
    assertFalse(bad.getLinkAnnotations(0, bad.length).isNotEmpty())             // disallowed scheme → plain text
  }

  @Test fun `bare email autolinks to mailto, trailing punctuation excluded`() {
    val out = renderBlockMarkdown("email finaid@butler.edu.")
    assertEquals("email finaid@butler.edu.", out.text)                          // text unchanged (incl. the period)
    val links = out.getLinkAnnotations(0, out.length)
    assertEquals(1, links.size)
    assertEquals("mailto:finaid@butler.edu", (links[0].item as androidx.compose.ui.text.LinkAnnotation.Url).url)
  }

  @Test fun `an email inside an explicit mailto link is not double-linked`() {
    val out = renderBlockMarkdown("[write us](mailto:finaid@butler.edu)")
    assertEquals("write us", out.text)
    assertEquals(1, out.getLinkAnnotations(0, out.length).size)                  // the [label](url), not a second bare-email link
  }

  @Test fun `a non-email @ string is not autolinked (no false mailto on handles)`() {
    // requires local-part@domain.tld — a leading-@ handle, a no-dot domain, and a
    // tld-less host must NOT become a mailto link (else @mentions render as wrong links).
    for (md in listOf("ping @maya about it", "ask a@b for help", "user@localhost reboot")) {
      val out = renderBlockMarkdown(md)
      assertEquals(md, out.text)
      assertTrue(out.getLinkAnnotations(0, out.length).isEmpty(), "unexpected link for: $md")
    }
  }

  @Test fun `an email inside a bare URL is the URL link, not a second mailto`() {
    // the URL pattern precedes the email pattern, so user@host in https://user@host/…
    // is part of the ONE url link — not separately autolinked as an email.
    val out = renderBlockMarkdown("see https://user@host.example/path now")
    val links = out.getLinkAnnotations(0, out.length)
    assertEquals(1, links.size)
    assertEquals("https://user@host.example/path", (links[0].item as androidx.compose.ui.text.LinkAnnotation.Url).url)
  }

  @Test fun `plus-addressing and subdomains autolink to the full address`() {
    val out = renderBlockMarkdown("reply to admissions+fall@mail.butler.edu")
    val links = out.getLinkAnnotations(0, out.length)
    assertEquals(1, links.size)
    assertEquals("mailto:admissions+fall@mail.butler.edu", (links[0].item as androidx.compose.ui.text.LinkAnnotation.Url).url)
  }

  @Test fun `italic strips underscores and styles the run`() {
    val out = renderBlockMarkdown("_529 drawdown notes_")
    assertEquals("529 drawdown notes", out.text)
    assertEquals(1, italicSpans(out))
  }

  @Test fun `bold inside a bullet, and plain text, both work`() {
    assertEquals("• Jul 1 — deadline", renderBlockMarkdown("- **Jul 1** — deadline").text)
    assertEquals(1, boldSpans(renderBlockMarkdown("- **Jul 1** — deadline")))
    assertEquals("Health insurance waiver", renderBlockMarkdown("Health insurance waiver").text)
  }

  @Test fun `tables drop the separator row, join cells, and bold the header`() {
    val out = renderBlockMarkdown(
      "| Office | Email | Phone |\n|--------|-------|-------|\n| Financial Aid | finaid@butler.edu | 888-940-8100 |",
    )
    // separator row gone (no raw dashes/pipes); cells joined; rows on their own lines
    assertEquals("Office  ·  Email  ·  Phone\nFinancial Aid  ·  finaid@butler.edu  ·  888-940-8100", out.text)
    assertEquals(1, boldSpans(out))   // only the header row is bold
  }

  @Test fun `a lone dashed line without pipes is NOT treated as a table separator`() {
    // a bullet with a dash, and a plain line, must survive (no over-greedy separator match)
    assertEquals("• item", renderBlockMarkdown("- item").text)
    assertEquals("a | b", renderBlockMarkdown("a | b").text)   // not a table row (no leading/trailing pipe)
  }

  @Test fun `headings strip the hashes, bold the line, and size h1 h2`() {
    val h1 = renderBlockMarkdown("# Health & Forms")
    assertEquals("Health & Forms", h1.text)
    assertTrue(h1.spanStyles.any { it.item.fontWeight == FontWeight.Bold && it.item.fontSize == 20.sp })
    val h2 = renderBlockMarkdown("## Immunizations")
    assertEquals("Immunizations", h2.text)
    assertTrue(h2.spanStyles.any { it.item.fontSize == 17.sp })
    val h3 = renderBlockMarkdown("### Notes")
    assertEquals("Notes", h3.text)                 // bold, default size
    assertEquals(1, boldSpans(h3))
  }

  @Test fun `ordered lists keep their numbers and still format inline`() {
    assertEquals("1. First\n2. Second", renderBlockMarkdown("1. First\n2. Second").text)
    assertEquals("1. Do it", renderBlockMarkdown("1. **Do** it").text)   // inline bold inside an ordered item
    assertEquals(1, boldSpans(renderBlockMarkdown("1. **Do** it")))
  }

  @Test fun `bare https URLs autolink, with trailing punctuation kept as text`() {
    val a = renderBlockMarkdown("Apply at https://butler.edu today")
    assertEquals("Apply at https://butler.edu today", a.text)            // text unchanged
    assertEquals(1, a.getLinkAnnotations(0, a.length).size)
    val b = renderBlockMarkdown("See https://butler.edu.")
    assertEquals("See https://butler.edu.", b.text)                      // the "." survives as text
    val ann = b.getLinkAnnotations(0, b.length).single()
    assertEquals("https://butler.edu", (ann.item as androidx.compose.ui.text.LinkAnnotation.Url).url) // link excludes the "."
  }

  @Test fun `a bare http URL is not linkified (https-only policy)`() {
    val out = renderBlockMarkdown("old http://x.org link")
    assertEquals("old http://x.org link", out.text)
    assertTrue(out.getLinkAnnotations(0, out.length).isEmpty())
  }

  @Test fun `a markdown link is not double-matched by the autolinker`() {
    val out = renderBlockMarkdown("Tap [here](https://butler.edu) now")
    assertEquals("Tap here now", out.text)                               // the label, not the url
    assertEquals(1, out.getLinkAnnotations(0, out.length).size)
  }

  @Test fun `image markdown degrades to a labeled link — no stray bang, never inline-loaded`() {
    val ok = renderBlockMarkdown("see ![the map](https://butler.edu/map.png) here")
    assertEquals("see 🖼 the map here", ok.text)                         // 🖼 + alt; no "!", no raw ()/url
    assertEquals(1, ok.getLinkAnnotations(0, ok.length).size)            // taps out to the image (vetted), not inline-loaded
    val bad = renderBlockMarkdown("![x](javascript:alert)")
    assertEquals("🖼 x", bad.text)
    assertTrue(bad.getLinkAnnotations(0, bad.length).isEmpty())          // disallowed scheme → plain label
  }
}
