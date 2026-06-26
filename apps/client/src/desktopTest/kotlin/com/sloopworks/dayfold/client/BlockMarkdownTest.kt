package com.sloopworks.dayfold.client

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
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
}
