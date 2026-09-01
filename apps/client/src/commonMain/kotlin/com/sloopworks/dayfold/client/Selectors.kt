package com.sloopworks.dayfold.client

// [review F1] Feed render order = the API's list contract: not_before NULLS
// LAST, then id. The reducer keeps sync (arrival) order in state; the feed
// order is derived here at render time (the redux-kotlin select{}/selector
// layer per ADR 0013). Cards with no not_before sort after timed ones.
//
// Expiry is enforced HERE (the server stores but doesn't filter expired cards,
// and nothing tombstones them — repo.ts orders by not_before only). Offline-first:
// a cached card past expires_at would otherwise linger ("RSVP by Thursday" on
// Friday). Fail open — a null/unparseable expires_at or now keeps the card, never
// hides on bad data. not_before stays ordering-only (documented as a sort key,
// not a visibility gate).
fun feedCards(state: AppState, nowIso: String): List<Card> {
  val now = parseOrNull(nowIso)
  return state.content.cards
    .filter { card -> now == null || card.expiresAt == null || (parseOrNull(card.expiresAt)?.let { it > now } ?: true) }
    .sortedWith(compareBy({ it.notBefore == null }, { it.notBefore }, { it.id }))
}

// CL-6: resolve the card at the top of the detail stack. A null result does not itself
// change navigation; the host keeps the selected detail mounted as an unavailable state
// until the user explicitly goes back.
fun currentDetailCard(state: AppState): Card? =
  state.navigation.detailStack.lastOrNull()?.let { id -> state.content.cards.find { it.id == id } }
