package com.sloopworks.dayfold.client

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Ephemeral local-search UI state.
 *
 * This deliberately has no Saver and must never be moved to rememberSaveable, Redux, a Bundle, or
 * any persistence layer: queries, excerpts, and result text are family content. Hoist the session at
 * FeedApp level so a canonical destination can temporarily replace Search without losing the query
 * or [resultsListState].
 */
@Stable
class SearchSession internal constructor(
  val bindingGeneration: Long,
) {
  var query: String by mutableStateOf("")
    private set

  var response: SearchResponse? by mutableStateOf(null)
    private set

  /** Raw association stays only beside the raw query in this non-saveable session. */
  private var responseQuery: String? = null

  var searching: Boolean by mutableStateOf(false)
    internal set

  /** Plain, non-saveable list state that restores the result position after destination Back. */
  val resultsListState: LazyListState = LazyListState()

  internal var searchTrigger: Long by mutableLongStateOf(0L)
    private set
  private var immediateTrigger: Long = Long.MIN_VALUE

  fun updateQuery(value: String) {
    val bounded = boundSearchQueryInput(value)
    if (query == bounded) return
    query = bounded
    response = null
    responseQuery = null
    searching = false
  }

  /** Requests the current meaningful query immediately, bypassing only the UI debounce. */
  fun submit() {
    searchTrigger += 1L
    immediateTrigger = searchTrigger
  }

  internal fun consumeImmediateSearch(trigger: Long): Boolean {
    if (immediateTrigger != trigger) return false
    immediateTrigger = Long.MIN_VALUE
    return true
  }

  internal fun hasCurrentResponse(query: String, status: SearchStatus): Boolean {
    val current = response ?: return false
    return responseQuery == query &&
      current.bindingGeneration == status.bindingGeneration &&
      current.corpusGeneration == status.corpusGeneration
  }

  internal fun acceptResponse(query: String, value: SearchResponse) {
    responseQuery = query
    response = value
  }

  internal fun discardResponse() {
    responseQuery = null
    response = null
  }

  /** Synchronously removes every query/result-bearing value from this session. */
  fun clear() {
    query = ""
    response = null
    responseQuery = null
    searching = false
    immediateTrigger = Long.MIN_VALUE
    searchTrigger = 0L
    resultsListState.requestScrollToItem(0)
  }
}

/**
 * Creates one plain in-memory session per opaque family binding.
 *
 * A binding change disposes and clears the old session even when the replacement family has the
 * same ID. Calling this from the always-mounted FeedApp owner also clears it when that host exits.
 */
@Composable
fun rememberSearchSession(searchStatus: SearchStatus): SearchSession {
  val session = remember(searchStatus.bindingGeneration) {
    SearchSession(bindingGeneration = searchStatus.bindingGeneration)
  }
  DisposableEffect(session) {
    onDispose { session.clear() }
  }
  return session
}

internal fun isMeaningfulSearchQuery(query: String): Boolean =
  query.count { !it.isWhitespace() } >= SearchLimits.MIN_QUERY_NON_SPACE_CHARS
