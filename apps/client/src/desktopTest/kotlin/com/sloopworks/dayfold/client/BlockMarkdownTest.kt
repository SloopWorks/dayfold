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
}
