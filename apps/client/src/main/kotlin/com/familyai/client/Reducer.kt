package com.familyai.client

import org.reduxkotlin.Store
import org.reduxkotlin.devtools.DevToolsConfig
import org.reduxkotlin.devtools.devTools
import org.reduxkotlin.threadsafe.createThreadSafeStore

// Hand-written root reducer (locked decision: no combineReducers). Applies a
// /sync delta: upsert changed cards by id (preserving order, newest wins),
// remove tombstoned ids, advance the cursor.
fun rootReducer(state: AppState, action: Any): AppState = when (action) {
  is SyncStarted -> state.copy(syncing = true, error = null)
  is SyncFailed -> state.copy(syncing = false, error = action.message)
  is SyncSucceeded -> {
    val byId = LinkedHashMap<String, Card>()
    state.cards.forEach { byId[it.id] = it }
    action.resp.changes.cards.forEach { byId[it.id] = it }            // upsert
    action.resp.tombstones.filter { it.type == "card" }
      .forEach { byId.remove(it.id) }                                  // delete
    state.copy(
      cards = byId.values.toList(),
      cursor = action.resp.nextCursor ?: state.cursor,
      syncing = false,
      error = null,
    )
  }
  else -> state
}

// [F5] thread-safe store: the SyncClient effect dispatches from Dispatchers.IO
// while the Compose UI reads on main — needs synchronized dispatch.
// `debug=true` attaches the redux-kotlin-devtools `devTools()` enhancer, which
// records actions + state diffs to DevToolsHub (observed by the in-app drawer
// on Android; ADR 0019). Release builds pass debug=false (no recording).
fun createAppStore(initial: AppState = AppState(), debug: Boolean = true): Store<AppState> =
  if (debug) createThreadSafeStore(::rootReducer, initial, devTools(DevToolsConfig(instanceId = "family-ai", name = "Family AI")))
  else createThreadSafeStore(::rootReducer, initial)
