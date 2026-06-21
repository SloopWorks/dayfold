package com.familyai.client

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

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
// Single source of truth for outbound-handoff schemes — shared by the body-link
// renderer AND the card action effect layer (cards/PlatformActions). `sms` added
// per the epic finding (contact Text action). https only (never plain http).
internal val ALLOWED_SCHEMES = setOf("https", "mailto", "tel", "geo", "sms")
internal fun schemeOf(url: String) = url.trim().substringBefore(":", "").lowercase()
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
