package com.sloopworks.dayfold.client

/** Pure feed/sync projection transitions. Cross-feature detail pruning stays in rootReducer. */
fun reduceContent(state: AppState, action: Any): AppState = when (action) {
  is SyncStarted -> state.copy(syncing = true, error = null)
  is SyncSucceeded -> state.copy(syncing = false, error = null)
  is SyncStopped -> state.copy(syncing = false)
  is SyncFailed -> state.copy(syncing = false, error = action.message)
  is CardsLoaded -> state.copy(cards = action.cards)
  else -> state
}
