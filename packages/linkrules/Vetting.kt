package com.sloopworks.dayfold.client.cards

import com.sloopworks.dayfold.client.schemeOf

// SHARED (packages/linkrules). Pure, stdlib-only URI-component vetting reused by
// the client effect layer (cardActionUri/vettedOpenUri) AND the author-side
// linkifier (linkify). Moved here from PlatformActions.kt so there is exactly one
// copy — author-side production and client-side render/open can never drift.

/**
 * mailto is authored content (untrusted) — vet the ADDRESS only, reject params
 * (`?subject=&body=`), multi-recipient (`,`), and any whitespace/CRLF header
 * injection; rebuild as a clean `mailto:<addr>`. Returns null if it doesn't vet.
 */
// public: consumed cross-module by :ui (cards/PlatformActions)
fun vetMailto(raw: String): String? {
  if (schemeOf(raw) != "mailto") return null
  val addr = raw.trim().removePrefix("mailto:").substringBefore("?") // drop any params
  if (addr.isBlank()) return null
  // reject multi-recipient, angle-addr, whitespace/CRLF, and literal '%' (blocks
  // percent-encoded CRLF/header smuggling that survives the plain whitespace check).
  if (addr.any { it.isWhitespace() || it == ',' || it == '<' || it == '>' || it == '%' }) return null
  if (!addr.contains('@')) return null
  return "mailto:$addr"
}

/** Keep a single leading '+' and digits only — drops spaces, DTMF/comma dialing,
 *  and any injection chars. Null if nothing dialable remains. */
fun sanitizePhone(raw: String): String? {
  val plus = if (raw.trimStart().startsWith("+")) "+" else ""
  val digits = raw.filter { it.isDigit() }
  return (plus + digits).takeIf { digits.isNotEmpty() }
}

/** Minimal RFC 3986 percent-encoding for a query value (no java.net in commonMain). */
fun percentEncode(s: String): String {
  val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"
  val sb = StringBuilder()
  for (b in s.encodeToByteArray()) {
    val c = b.toInt() and 0xFF
    if (c.toChar() in unreserved) sb.append(c.toChar())
    else { sb.append('%'); sb.append("0123456789ABCDEF"[c shr 4]); sb.append("0123456789ABCDEF"[c and 0xF]) }
  }
  return sb.toString()
}
