package com.sloopworks.dayfold.client

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private const val SEARCH_DEBOUNCE_MILLIS = 75L

/**
 * The shared Android/iOS local-search route.
 *
 * [session] must be hoisted with [rememberSearchSession] above route composition so destination
 * Back restores the raw query, result set, and list position. Search text never enters an action,
 * saved-state object, log, or analytic event through this API.
 */
@Composable
fun SearchScreen(
  searchStatus: SearchStatus,
  offline: Boolean,
  session: SearchSession,
  search: suspend (String) -> SearchResponse,
  onBack: () -> Unit,
  onResultClick: (SearchResultHandle) -> Unit,
  modifier: Modifier = Modifier,
) {
  val focusRequester = androidx.compose.runtime.remember(searchStatus.bindingGeneration) { FocusRequester() }
  val focusManager = LocalFocusManager.current
  val meaningful = isMeaningfulSearchQuery(session.query)

  LaunchedEffect(searchStatus.bindingGeneration) {
    focusRequester.requestFocus()
  }

  // Corpus generation is a key: a hide/tombstone/completion rebuild reruns the current query and
  // cannot leave a stale result visible. A binding change constructs a completely fresh session.
  LaunchedEffect(
    session.query,
    session.searchTrigger,
    searchStatus.bindingGeneration,
    searchStatus.corpusGeneration,
    searchStatus.readiness,
  ) {
    if (!meaningful || searchStatus.readiness != SearchReadiness.READY) {
      session.discardResponse()
      session.searching = false
      return@LaunchedEffect
    }
    val immediate = session.consumeImmediateSearch(session.searchTrigger)
    // Search is remounted after a canonical destination closes. Keep a still-current response
    // visible immediately instead of flashing a debounce spinner on the way back. An explicit
    // keyboard Search submission still refreshes immediately.
    if (!immediate && session.hasCurrentResponse(session.query, searchStatus)) {
      session.searching = false
      return@LaunchedEffect
    }

    val submittedQuery = session.query
    session.discardResponse()
    session.searching = true
    if (!immediate) delay(SEARCH_DEBOUNCE_MILLIS)

    val response = try {
      search(submittedQuery)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Throwable) {
      SearchResponse(
        requestGeneration = session.searchTrigger,
        bindingGeneration = searchStatus.bindingGeneration,
        corpusGeneration = searchStatus.corpusGeneration,
        readiness = searchStatus.readiness,
        failure = SearchFailure.UNAVAILABLE,
      )
    }
    // The family-owned engine also fences these values. This UI check prevents even a briefly
    // returned obsolete response from entering the visible in-memory session.
    if (
      response.bindingGeneration == searchStatus.bindingGeneration &&
      response.corpusGeneration == searchStatus.corpusGeneration &&
      session.query == submittedQuery
    ) {
      session.acceptResponse(submittedQuery, response)
    }
    session.searching = false
  }

  Surface(
    modifier = modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.surface,
  ) {
    Column(Modifier.fillMaxSize()) {
      SearchField(
        query = session.query,
        onQueryChange = session::updateQuery,
        onClear = { session.updateQuery("") },
        onSubmit = session::submit,
        onBack = {
          focusManager.clearFocus(force = true)
          session.clear()
          onBack()
        },
        focusRequester = focusRequester,
      )
      SearchTrustLine(offline = offline)
      Box(Modifier.weight(1f)) {
        SearchBody(
          searchStatus = searchStatus,
          query = session.query,
          meaningful = meaningful,
          searching = session.searching,
          response = session.response,
          session = session,
          onResultClick = { handle ->
            focusManager.clearFocus(force = true)
            onResultClick(handle)
          },
        )
      }
    }
  }
}

@Composable
private fun SearchField(
  query: String,
  onQueryChange: (String) -> Unit,
  onClear: () -> Unit,
  onSubmit: () -> Unit,
  onBack: () -> Unit,
  focusRequester: FocusRequester,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, end = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
      Icon(DayfoldIcons.ArrowBack, contentDescription = "Back")
    }
    TextField(
      value = query,
      onValueChange = onQueryChange,
      modifier = Modifier
        .weight(1f)
        .focusRequester(focusRequester)
        .semantics { contentDescription = "Search all Dayfold content" },
      placeholder = { Text("Search Dayfold") },
      leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
      trailingIcon = if (query.isNotEmpty()) {
        {
          IconButton(onClick = onClear, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Rounded.Clear, contentDescription = "Clear search")
          }
        }
      } else {
        null
      },
      singleLine = true,
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
      keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
      shape = MaterialTheme.shapes.extraLarge,
      colors = TextFieldDefaults.colors(
        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
      ),
    )
  }
}

@Composable
private fun SearchTrustLine(offline: Boolean) {
  val text = if (offline) "Offline · searching saved content" else "On this device"
  val icon = if (offline) DayfoldIcons.WifiOff else DayfoldIcons.Verified
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 64.dp, vertical = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      modifier = Modifier.size(16.dp),
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = text,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
  HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SearchBody(
  searchStatus: SearchStatus,
  query: String,
  meaningful: Boolean,
  searching: Boolean,
  response: SearchResponse?,
  session: SearchSession,
  onResultClick: (SearchResultHandle) -> Unit,
) {
  when {
    searchStatus.readiness == SearchReadiness.WAITING_FOR_SYNC -> SearchMessage(
      title = "Getting saved content ready",
      supporting = "Search will be available after Dayfold finishes its first sync.",
      progress = true,
    )

    searchStatus.readiness == SearchReadiness.EMPTY -> SearchMessage(
      title = "Nothing saved on this device yet",
      supporting = "Once Dayfold content is available here, you can search it offline too.",
    )

    !meaningful -> SearchMessage(
      title = "Find anything in Dayfold",
      supporting = "Try a Hub, card, person, file, or a phrase from a checklist. Type at least two characters.",
      icon = Icons.Rounded.Search,
    )

    searching -> SearchMessage(
      title = "Searching saved content",
      supporting = null,
      progress = true,
    )

    response?.failure != null -> SearchMessage(
      title = "Search is unavailable",
      supporting = "Go back and try again in a moment.",
    )

    response == null -> SearchMessage(
      title = "Searching saved content",
      supporting = null,
      progress = true,
    )

    response.readiness == SearchReadiness.EMPTY -> SearchMessage(
      title = "Nothing saved on this device yet",
      supporting = "Once Dayfold content is available here, you can search it offline too.",
    )

    response.results.isEmpty() -> SearchMessage(
      title = "No results",
      supporting = "Try a shorter word or a different phrase.",
    )

    else -> SearchResults(
      query = query,
      results = response.results,
      session = session,
      onResultClick = onResultClick,
    )
  }
}

@Composable
private fun SearchResults(
  query: String,
  results: List<SearchResult>,
  session: SearchSession,
  onResultClick: (SearchResultHandle) -> Unit,
) {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    state = session.resultsListState,
  ) {
    val firstCloseMatch = results.indexOfFirst(SearchResult::closeMatch)
    results.forEachIndexed { index, result ->
      if (index == firstCloseMatch) {
        item(key = "close-match-heading") {
          Text(
            text = "Close matches for “$query”",
            modifier = Modifier.padding(start = 64.dp, top = 14.dp, end = 16.dp, bottom = 6.dp)
              .semantics { heading() },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
          )
        }
      }
      item(key = result.handle.destination.stableSearchKey()) {
        SearchResultRow(result = result, onClick = onResultClick)
        HorizontalDivider(
          modifier = Modifier.padding(start = 64.dp),
          color = MaterialTheme.colorScheme.outlineVariant,
        )
      }
    }
  }
}

@Composable
private fun SearchMessage(
  title: String,
  supporting: String?,
  icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
  progress: Boolean = false,
) {
  Box(Modifier.fillMaxSize().padding(horizontal = 28.dp), contentAlignment = Alignment.Center) {
    Column(
      modifier = Modifier.fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      when {
        progress -> CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
        icon != null -> Icon(
          imageVector = icon,
          contentDescription = null,
          modifier = Modifier.size(36.dp),
          tint = MaterialTheme.colorScheme.primary,
        )
      }
      Text(
        text = title,
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
      )
      supporting?.let {
        Text(
          text = it,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

private fun SearchDestination.stableSearchKey(): String = when (kind) {
  SearchDestinationKind.CARD -> "card:$cardId"
  SearchDestinationKind.HUB -> "hub:$hubId"
  SearchDestinationKind.SECTION -> "section:$hubId:$sectionId"
  SearchDestinationKind.BLOCK -> "block:$hubId:$sectionId:$blockId"
}
