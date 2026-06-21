package com.sloopworks.dayfold.client.cards

import com.sloopworks.dayfold.client.Card

// Pure (no Composer) per-type derivations — accent, kicker, primary action, body
// summary. Kept out of the composables so they're fast unit tests (golden-stable,
// no PNG diffing) and so derivation never runs on the recomposition hot path
// beyond a cheap field read. CL-5 (ADR 0022). Kicker is DERIVED (CL-1 has no
// `kicker` field yet) — coarse type/urgency label; rich date kickers are a follow.

enum class CardAccent { Primary, Secondary, Tertiary }

/** type → brand accent role (mockup: teal=secondary, violet=tertiary, coral=primary). */
fun accentFor(type: String?): CardAccent = when (type) {
  "invite" -> CardAccent.Primary
  "link", "email" -> CardAccent.Tertiary
  else -> CardAccent.Secondary // file / contact / geo / unknown
}

/** Short uppercase kicker per type (+ a payload hint where it sharpens meaning). */
fun kickerFor(card: Card): String = when (card.type) {
  "file" -> "FILE"
  "link" -> if (card.payload?.link?.kind == "form") "FORM" else "LINK"
  "invite" -> "INVITATION · RSVP"
  "contact" -> "CONTACT"
  "geo" -> "OUTING"
  "email" -> "EMAIL"
  else -> card.type?.uppercase() ?: ""
}

/** A one-line body summary derived from the payload when the card has no body_md. */
fun bodySummaryFor(card: Card): String? {
  card.bodyMd?.takeIf { it.isNotBlank() }?.let { return it }
  val p = card.payload ?: return null
  return when (card.type) {
    "file" -> listOfNotNull(p.file?.filename, p.file?.pages?.let { "$it pages" }).joinToString(" · ").ifBlank { null }
    "link" -> p.link?.domain
    "invite" -> listOfNotNull(p.invite?.host, p.invite?.guestCount?.let { "$it guests" }).joinToString(" · ").ifBlank { null }
    "contact" -> listOfNotNull(p.contact?.company, p.contact?.role).joinToString(" · ").ifBlank { null }
    "geo" -> listOfNotNull(p.geo?.address, p.geo?.etaMin?.let { "$it min away" }).joinToString(" · ").ifBlank { null }
    "email" -> listOfNotNull(p.email?.from, p.email?.subject).joinToString(" · ").ifBlank { null }
    else -> null
  }
}

private fun isHttp(s: String?) = s != null && (s.startsWith("http://") || s.startsWith("https://"))

/** Primary action label + the [CardAction] it emits. Falls back to OpenDetail when
 *  the type-specific target is missing — never throws, never builds a bad scheme. */
fun primaryActionFor(card: Card): Pair<String, CardAction> {
  val detail = "Details" to CardAction.OpenDetail(card.id)
  val p = card.payload ?: return detail
  return when (card.type) {
    "file" -> p.file?.docRef?.takeIf(::isHttp)?.let { "Open" to CardAction.OpenUrl(it) } ?: detail
    "link" -> p.link?.url?.takeIf(::isHttp)
      ?.let { (if (p.link?.kind == "form") "Open form" else "Open") to CardAction.OpenUrl(it) } ?: detail
    "invite" -> detail // RSVP is display-only at M0 (ADR 0020/0016); reply lands with detail (CL-6)
    "contact" -> detail // contact's quick actions are the inline Call/Text row — primary opens detail (no dup)
    "geo" -> (p.geo?.address ?: p.geo?.label)?.let { "Navigate" to CardAction.Navigate(it) } ?: detail
    "email" -> p.email?.fromAddr?.let { "Reply" to CardAction.Email("mailto:$it") } ?: detail
    else -> detail
  }
}
