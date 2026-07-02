package com.sloopworks.dayfold.client

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

// Card presentation helpers (spec: 12-briefing-card-spec.md). Pure (no Composer)
// so they're unit-testable; the Composable applies theme colors.

// Kind → small label chip (info = no chip). Forward-compatible: unknown → null.
fun kindLabel(kind: String): String? = when (kind) {
  "action" -> "Action"
  "weather" -> "Weather"
  "countdown" -> "Countdown"
  else -> null // info / unknown
}

// provenance.source → honest source chip text (user = no chip).
fun sourceLabel(source: String?): String? = when {
  source == null || source == "user" -> null
  source == "claude" -> "Added by Claude"
  source == "email" -> "From your email"
  source.startsWith("http") -> "From a linked source"
  else -> "Added by $source"
}

// M0 action-links: render body_md, turning [label](url) into tappable links ONLY
// for allowlisted schemes (others → plain text, link dropped). This ships the
// value-prop "[list]"/"[reply]" now without a heavy markdown dep; the full
// markdown renderer (lists/tables/images) is a later upgrade. (OQ-markdown-render)
private val LINK = Regex("""\[([^\]]+)]\(([^)]+)\)""")
// ALLOWED_SCHEMES + schemeOf now live in the shared linkrules source (Schemes.kt,
// same package) so the renderer, the action effect layer, AND the author-side
// linkifier read one allowlist that can't drift.
private val LINK_STYLE = TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline))

fun renderCardBody(md: String): AnnotatedString = buildAnnotatedString {
  var i = 0
  for (m in LINK.findAll(md)) {
    append(md.substring(i, m.range.first))
    val label = m.groupValues[1]
    val url = m.groupValues[2]
    if (schemeOf(url) in ALLOWED_SCHEMES) {
      withLink(LinkAnnotation.Url(url, LINK_STYLE)) { append(label) }
    } else {
      append(label) // disallowed scheme → plain text (link stripped)
    }
    i = m.range.last + 1
  }
  append(md.substring(i))
}

// Are there any allowlisted, tappable links in this body? (test/UX helper)
fun hasActionLinks(md: String): Boolean =
  LINK.findAll(md).any { schemeOf(it.groupValues[2]) in ALLOWED_SCHEMES }

// ── block markdown (OQ-markdown-render) ──────────────────────────────────────
// Hub-block + card bodies are authored as real markdown. They used to render as
// RAW Text — `**Jul 1**` showed the asterisks. Render to an AnnotatedString:
// **bold**, _italic_, `- `/`- [ ]`/`- [x]` + ordered (`1.`) lists, | tables |,
// ATX `#`/`##` headings, and the same vetted [label](url) links as cards.
// inline tokens, in match order: ![alt](url) image | **bold** | [label](url) |
// _italic_ | bare autolink (https://…) | bare email (→ mailto:). The image alt is
// FIRST so `![a](u)` is taken as an image (consuming the `!`), not a link with a stray
// leading `!`. Bare email is LAST so an email inside a [label](mailto:…) link or a URL
// is consumed by those first. Images are still never inline-loaded (OQ: host-gated
// async) — they degrade to a 🖼 + alt label that links out (vetted) so the syntax
// never shows raw.
private val INLINE = Regex("""!\[([^\]]+)]\(([^)]+)\)|\*\*(.+?)\*\*|\[([^\]]+)]\(([^)]+)\)|_([^_]+?)_|(https?://[^\s)]+)|([A-Za-z0-9._%+-]+@[A-Za-z0-9-]+\.[A-Za-z0-9.-]+)""")
private val CHECKBOX = Regex("""^(\s*)[-*]\s+\[([ xX])]\s+(.*)$""")
private val BULLET = Regex("""^(\s*)[-*]\s+(.*)$""")
private val TABLE_ROW = Regex("""^\s*\|.*\|\s*$""")
private val TABLE_SEP = Regex("""^\s*\|[\s:|-]*-[\s:|-]*\|\s*$""")   // a |---|---| separator row
private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")                  // # / ## / … atx heading

// Heading run style: bold always, with a size bump for h1/h2 (h3+ = bold body size).
private fun headingSize(level: Int): TextUnit = when (level) {
  1 -> 20.sp
  2 -> 17.sp
  else -> TextUnit.Unspecified
}

private fun AnnotatedString.Builder.appendInline(text: String) {
  var i = 0
  for (m in INLINE.findAll(text)) {
    if (m.range.first < i) continue
    append(text.substring(i, m.range.first))
    when {
      m.groupValues[1].isNotEmpty() -> {                      // ![alt](url) image → 🖼 alt (links out, never inline-loaded)
        val label = "🖼 ${m.groupValues[1]}"; val url = m.groupValues[2]
        if (schemeOf(url) in ALLOWED_SCHEMES) withLink(LinkAnnotation.Url(url, LINK_STYLE)) { append(label) }
        else append(label)
      }
      m.groupValues[3].isNotEmpty() ->
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[3]) }
      m.groupValues[5].isNotEmpty() -> {                      // [label](url)
        val label = m.groupValues[4]; val url = m.groupValues[5]
        if (schemeOf(url) in ALLOWED_SCHEMES) withLink(LinkAnnotation.Url(url, LINK_STYLE)) { append(label) }
        else append(label)
      }
      m.groupValues[6].isNotEmpty() ->
        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(m.groupValues[6]) }
      m.groupValues[7].isNotEmpty() -> {                      // bare autolink https://…
        var url = m.groupValues[7]
        // don't swallow trailing sentence punctuation into the URL ("…edu." / "…edu)")
        var end = url.length
        while (end > 0 && url[end - 1] in ".,;:!?") end--
        val trail = url.substring(end); url = url.substring(0, end)
        if (schemeOf(url) in ALLOWED_SCHEMES) withLink(LinkAnnotation.Url(url, LINK_STYLE)) { append(url) }
        else append(url)                                      // non-allowlisted scheme → plain text
        append(trail)
      }
      m.groupValues[8].isNotEmpty() -> {                      // bare email → mailto: link
        var addr = m.groupValues[8]
        var end = addr.length
        while (end > 0 && addr[end - 1] in ".,;:!?") end--    // don't swallow trailing punctuation
        val trail = addr.substring(end); addr = addr.substring(0, end)
        withLink(LinkAnnotation.Url("mailto:$addr", LINK_STYLE)) { append(addr) }
        append(trail)
      }
    }
    i = m.range.last + 1
  }
  append(text.substring(i))
}

// Memoized renderBlockMarkdown for composables: the parse (line split + regex + the
// AnnotatedString build) depends only on [md], so cache it across recompositions —
// feed/hub items re-render on scroll-into-view and on every /sync emission, and without
// this each one re-parses its body every time. Keyed on the string; the underlying
// function is pure (non-@Composable), so the cache is always correct.
@Composable
fun rememberRenderedMarkdown(md: String): AnnotatedString = remember(md) { renderBlockMarkdown(md) }

fun renderBlockMarkdown(md: String): AnnotatedString = buildAnnotatedString {
  val lines = md.split("\n")
  var first = true
  lines.forEachIndexed { idx, line ->
    if (TABLE_SEP.matches(line)) return@forEachIndexed          // drop |---| separator rows
    if (!first) append("\n")                                    // prepend so dropped rows leave no blank line
    first = false
    val h = HEADING.find(line)
    val cb = if (h == null) CHECKBOX.find(line) else null
    val b = if (h == null && cb == null) BULLET.find(line) else null
    val isTable = h == null && cb == null && b == null && TABLE_ROW.matches(line)
    when {
      h != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = headingSize(h.groupValues[1].length))) { appendInline(h.groupValues[2]) }
      cb != null -> { append(if (cb.groupValues[2].trim().lowercase() == "x") "☑ " else "☐ "); appendInline(cb.groupValues[3]) }
      b != null -> { append("• "); appendInline(b.groupValues[2]) }
      isTable -> {                                              // | a | b | → "a  ·  b"; header (row above a separator) bold
        val cells = line.trim().trim('|').split("|").map { it.trim() }
        val header = idx + 1 < lines.size && TABLE_SEP.matches(lines[idx + 1])
        val joined = cells.joinToString("  ·  ")
        if (header) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(joined) } else appendInline(joined)
      }
      else -> appendInline(line)
    }
  }
}
