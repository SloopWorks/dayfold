package com.sloopworks.dayfold.client.cards

// SHARED (packages/linkrules). Author-side linkifier: wrap bare phone/email entities
// in body_md into explicit allowlisted markdown links BEFORE storage, so dumb clients
// render them tappable (the dashboard renders intelligence produced elsewhere). Bare
// URLs are already autolinked by the renderer, so they pass through as protected spans.
// Addresses are NOT detected in prose (structured geo payload / location block only).
// Stdlib-only so it compiles in client commonMain AND the CLI.

enum class PhoneRegion { US }

// Protected spans — never modified, never scanned inside (priority order; fenced
// code first). Idempotency falls out: a 2nd pass finds every new link already inside
// a protected span. `[\s\S]` spans newlines without a DOT_MATCHES_ALL flag.
private val PROTECTED = Regex(
  "```[\\s\\S]*?```" +              // fenced code block
    "|`[^`]*`" +                    // inline code
    "|^\\[[^\\]]+]:\\s*\\S+" +      // reference-style link def `[ref]: url` (MULTILINE)
    "|!\\[[^\\]]*]\\([^)]*\\)" +    // image ![alt](url)
    "|\\[[^\\]]*]\\([^)]*\\)" +     // link [label](url)
    "|<[^>\\s]+>" +                 // angle autolink <…>
    "|https?://[^\\s)]+" +          // bare URL (phone/email-like substrings inside left alone)
    "|\\\\.",                       // backslash-escaped char
  setOf(RegexOption.MULTILINE),     // `^` matches each line start (for the ref-def alt)
)

// Strict NANP: optional +1/1, 3-3-4 with separators from [ .-], NOT preceded by a
// word char / $ / # / - (kills $1,200-1,500, #555…, version tails) and NOT followed
// by more digits or '-digit' (kills ZIP+4 94103-1234, dates 2026-06-27).
private val PHONE = Regex(
  "(?<![\\w\$#-])(?:\\+?1[ .-]?)?\\(?\\d{3}\\)?[ .-]?\\d{3}[ .-]?\\d{4}(?![\\d-])",
)

// Conservative ASCII addr-spec with a dotted TLD. Trailing '.' excluded by the TLD
// class boundary. vetMailto re-checks the address (params/CRLF/%/<>).
private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

private val COMBINED = Regex("(${PHONE.pattern})|(${EMAIL.pattern})")

private fun isAsciiPrintable(s: String) = s.all { it.code in 0x20..0x7E }

private fun phoneLink(match: String): String? {
  val tel = sanitizePhone(match) ?: return null
  val digits = tel.removePrefix("+")
  val e164 = when {
    digits.length == 10 -> "+1$digits"                          // bare NANP → +1
    digits.length == 11 && digits.startsWith("1") -> "+$digits" // already country-coded
    else -> return null                                          // not confidently NANP → don't link
  }
  return "[$match](tel:$e164)"
}

private fun emailLink(match: String): String? {
  if (!isAsciiPrintable(match)) return null         // reject unicode/homograph/RTL/zero-width
  val mailto = vetMailto("mailto:$match") ?: return null
  return "[$match]($mailto)"
}

// Linkify a gap (text known to be outside any protected span): wrap phones + emails
// in one left-to-right pass so matches can't overlap.
private fun linkifyGap(text: String): String {
  val sb = StringBuilder()
  var i = 0
  for (m in COMBINED.findAll(text)) {
    if (m.range.first < i) continue
    sb.append(text, i, m.range.first)
    val raw = m.value
    val replaced = (if (m.groupValues[1].isNotEmpty()) phoneLink(raw) else emailLink(raw)) ?: raw
    sb.append(replaced)
    i = m.range.last + 1
  }
  sb.append(text.substring(i))
  return sb.toString()
}

/** Author-side: wrap bare phone/email entities in [md] as explicit allowlisted
 *  markdown links, leaving protected spans untouched. Idempotent. */
fun linkify(md: String, region: PhoneRegion = PhoneRegion.US): String {
  val sb = StringBuilder()
  var i = 0
  for (p in PROTECTED.findAll(md)) {
    if (p.range.first < i) continue
    if (i < p.range.first) sb.append(linkifyGap(md.substring(i, p.range.first)))
    sb.append(md.substring(p.range.first, p.range.last + 1)) // verbatim
    i = p.range.last + 1
  }
  if (i < md.length) sb.append(linkifyGap(md.substring(i)))
  return sb.toString()
}
