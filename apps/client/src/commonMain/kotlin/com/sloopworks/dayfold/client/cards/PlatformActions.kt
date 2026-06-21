package com.sloopworks.dayfold.client.cards

import com.sloopworks.dayfold.client.ALLOWED_SCHEMES
import com.sloopworks.dayfold.client.schemeOf

// CL-PLAT — the platform effect layer (epic "Platform shims"). commonMain owns
// the EXPECT + the pure, vetted URI builder; each actual just opens what this
// returns. Mirrors the DriverFactory expect/actual-with-Context precedent: the
// Android actual takes a Context ctor; common code never constructs it (each
// shell does). Read-only (ADR 0020) — every effect is an OS handoff.
expect class PlatformActions {
  /** Perform an [CardAction] as an OS handoff. No-op for OpenDetail (in-app nav,
   *  CL-6) and for any action that yields no vetted URI (fail-safe). */
  fun perform(action: CardAction)
}

/**
 * Pure: the VETTED URI a [CardAction] opens, or null if it isn't URI-openable or
 * fails vetting. Centralizes scheme-allowlisting at ONE seam (shared with the
 * body-link renderer via [com.sloopworks.dayfold.client.ALLOWED_SCHEMES]) so the
 * composables never construct arbitrary intents. Copy/Share are handled directly
 * by the actual (not URIs); OpenDetail is in-app nav (CL-6).
 */
fun cardActionUri(action: CardAction): String? = when (action) {
  is CardAction.OpenUrl -> action.url.trim().takeIf { schemeOf(it) == "https" } // links are https-only
  is CardAction.Call -> sanitizePhone(action.number)?.let { "tel:$it" }
  is CardAction.Message -> sanitizePhone(action.number)?.let { "sms:$it" } // no ?body — number only
  is CardAction.Email -> vetMailto(action.mailto)
  is CardAction.Navigate -> action.query.takeIf { it.isNotBlank() }?.let { "geo:0,0?q=${percentEncode(it)}" }
  is CardAction.Copy, is CardAction.Share, is CardAction.OpenDetail -> null
}
  // defense-in-depth: never hand back a non-allowlisted scheme.
  ?.takeIf { schemeOf(it) in ALLOWED_SCHEMES }

/**
 * mailto is authored content (untrusted) — vet the ADDRESS only, reject params
 * (`?subject=&body=`), multi-recipient (`,`), and any whitespace/CRLF header
 * injection; rebuild as a clean `mailto:<addr>`. Returns null if it doesn't vet.
 */
private fun vetMailto(raw: String): String? {
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
