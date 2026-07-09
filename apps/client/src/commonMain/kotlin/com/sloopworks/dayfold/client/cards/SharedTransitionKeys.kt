package com.sloopworks.dayfold.client.cards

import com.sloopworks.dayfold.client.Card

// Which card-header elements are shareable (identical content) between the Now card and its
// detail → drive the card→detail shared-element transition. Pure so it's unit-tested and both
// render sites (card + detail) agree. Design: 2026-07-08-card-detail-shared-elements.
data class CardSharedKeys(val tile: Boolean, val kicker: Boolean, val title: Boolean, val button: Boolean)

fun sharedTransitionKeys(card: Card): CardSharedKeys = CardSharedKeys(
  tile = cardMonogram(card) == detailMonogram(card),
  kicker = kickerFor(card).isNotBlank(),
  title = true,
  button = primaryActionFor(card).second == detailActions(card).firstOrNull()?.action,
)

// Monogram on the Now card's accent tile — a contact shows name initials; every other type a glyph.
fun cardMonogram(card: Card): String = when (card.type) {
  "file" -> "F"; "link" -> "L"; "email" -> "@"; "invite" -> "!"; "geo" -> "G"
  "contact" -> monogramOf(card.payload?.contact?.name, "C")
  else -> "•"
}

// Monogram on the detail hero tile — the type glyph, always.
fun detailMonogram(card: Card): String = when (card.type) {
  "file" -> "F"; "link" -> "L"; "invite" -> "!"; "contact" -> "C"; "geo" -> "G"; "email" -> "@"
  else -> "•"
}

// Name → up-to-2-letter initials (falls back when blank). Moved here from TypedCards so the
// predicate and the card render agree on one definition.
internal fun monogramOf(text: String?, fallback: String): String =
  text?.trim()?.split(" ")?.filter { it.isNotEmpty() }?.take(2)?.joinToString("") { it.first().uppercase() }
    ?.ifBlank { fallback } ?: fallback
