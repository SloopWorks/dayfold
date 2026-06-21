package com.sloopworks.dayfold.client

// [review F1] Feed render order = the API's list contract: not_before NULLS
// LAST, then id. The reducer keeps sync (arrival) order in state; the feed
// order is derived here at render time (the redux-kotlin select{}/selector
// layer per ADR 0013). Cards with no not_before sort after timed ones.
fun feedCards(state: AppState): List<Card> =
  state.cards.sortedWith(compareBy({ it.notBefore == null }, { it.notBefore }, { it.id }))

// CL-6: the card at the top of the detail stack, or null (→ feed). Null also when
// the open card synced away — the host gracefully falls back to the feed.
fun currentDetailCard(state: AppState): Card? =
  state.detailStack.lastOrNull()?.let { id -> state.cards.find { it.id == id } }
