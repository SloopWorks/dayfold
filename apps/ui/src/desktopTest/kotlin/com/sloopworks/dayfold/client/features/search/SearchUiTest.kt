package com.sloopworks.dayfold.client

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import com.sloopworks.dayfold.client.theme.DayfoldTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SearchUiTest {
  private val ready = SearchStatus(bindingGeneration = 4, corpusGeneration = 9, readiness = SearchReadiness.READY)

  @Test
  fun `one non-space character never starts a search and clear removes the query`() = runComposeUiTest {
    val session = SearchSession(ready.bindingGeneration)
    var calls = 0
    setContent {
      DayfoldTheme {
        SearchScreen(
          searchStatus = ready,
          offline = false,
          session = session,
          search = {
            calls++
            response()
          },
          onBack = {},
          onResultClick = {},
        )
      }
    }

    onNodeWithContentDescription("Search all Dayfold content").performTextInput("s")
    mainClock.advanceTimeBy(200)
    waitForIdle()
    assertEquals(0, calls)
    onNodeWithText("Find anything in Dayfold").assertIsDisplayed()

    onNodeWithContentDescription("Search all Dayfold content").performTextInput("occer")
    onNodeWithContentDescription("Clear search").performClick()
    runOnIdle { assertEquals("", session.query) }
    onNodeWithText("Find anything in Dayfold").assertIsDisplayed()
  }

  @Test
  fun `offline and empty-cache state use honest fixed copy`() = runComposeUiTest {
    val empty = ready.copy(readiness = SearchReadiness.EMPTY)
    setContent {
      DayfoldTheme {
        SearchScreen(
          searchStatus = empty,
          offline = true,
          session = SearchSession(empty.bindingGeneration),
          search = { error("empty cache must not search") },
          onBack = {},
          onResultClick = {},
        )
      }
    }

    onNodeWithText("Offline · searching saved content").assertIsDisplayed()
    onNodeWithText("Nothing saved on this device yet").assertIsDisplayed()
    onNodeWithText("Once Dayfold content is available here, you can search it offline too.").assertIsDisplayed()
  }

  @Test
  fun `whole result row opens its opaque handle and exposes state labels`() = runComposeUiTest {
    val session = SearchSession(ready.bindingGeneration)
    session.updateQuery("socer")
    val handle = SearchResultHandle(
      destination = SearchDestination.block("h1", "s1", "b1"),
      corpusGeneration = ready.corpusGeneration,
    )
    var tapped: SearchResultHandle? = null
    setContent {
      DayfoldTheme {
        SearchScreen(
          searchStatus = ready,
          offline = false,
          session = session,
          search = {
            response(
              results = listOf(
                SearchResult(
                  handle = handle,
                  title = "Soccer permission slip",
                  breadcrumb = "Fall soccer › Forms",
                  excerpt = "Return the permission slip by Friday.",
                  titleHighlights = listOf(
                    SearchHighlight(SearchTextRange(0, 6), SearchHighlightKind.CLOSE),
                  ),
                  archived = true,
                  closeMatch = true,
                ),
              ),
            )
          },
          onBack = {},
          onResultClick = { tapped = it },
        )
      }
    }

    waitUntil(timeoutMillis = 2_000) {
      onAllNodesWithText("Close match").fetchSemanticsNodes().isNotEmpty()
    }
    onNodeWithText("Close matches for “socer”").assertIsDisplayed()
    onNodeWithText("Close match").assertIsDisplayed()
    onNodeWithText("Archived").assertIsDisplayed()
    onNodeWithContentDescription(
      "Hub item. Soccer permission slip. Fall soccer › Forms. Return the permission slip by Friday. Close match. Archived",
    ).performClick()
    assertEquals(handle, tapped)
  }

  @Test
  fun `binding generation replacement creates an empty non-saveable session`() = runComposeUiTest {
    var status by mutableStateOf(ready)
    lateinit var current: SearchSession
    setContent {
      current = rememberSearchSession(status)
    }

    lateinit var first: SearchSession
    runOnIdle {
      first = current
      first.updateQuery("permission slip")
      assertEquals("permission slip", first.query)
      status = status.copy(bindingGeneration = status.bindingGeneration + 1)
    }
    runOnIdle {
      assertNotSame(first, current)
      assertEquals("", current.query)
      assertEquals(null, current.response)
      assertEquals("", first.query, "disposed family session must be synchronously cleared")
    }
  }

  @Test
  fun `pasted query is bounded in private UI state without splitting a surrogate pair`() {
    val session = SearchSession(ready.bindingGeneration)
    session.updateQuery("x".repeat(SearchLimits.MAX_QUERY_CODE_UNITS - 1) + "😀" + "tail")

    assertTrue(session.query.length <= SearchLimits.MAX_QUERY_CODE_UNITS)
    assertTrue(session.query.last().isHighSurrogate().not())
    assertEquals("x".repeat(SearchLimits.MAX_QUERY_CODE_UNITS - 1), session.query)
  }

  @Test
  fun `source ranges receive distinct exact coral and close teal styles`() {
    val exact = SpanStyle(
      color = Color(0xFF4E1000),
      background = Color(0xFFFFDAD2),
      textDecoration = TextDecoration.Underline,
    )
    val close = SpanStyle(
      color = Color(0xFF00201C),
      background = Color(0xFF9DF2E4),
      textDecoration = TextDecoration.Underline,
    )
    val marked = buildHighlightedSearchText(
      text = "soccer Friday",
      highlights = listOf(
        SearchHighlight(SearchTextRange(0, 6), SearchHighlightKind.CLOSE),
        SearchHighlight(SearchTextRange(7, 13), SearchHighlightKind.EXACT),
        SearchHighlight(SearchTextRange(99, 100), SearchHighlightKind.EXACT),
      ),
      exactStyle = exact,
      closeStyle = close,
    )

    assertEquals("soccer Friday", marked.text)
    assertTrue(marked.spanStyles.any { it.start == 0 && it.end == 6 && it.item == close })
    assertTrue(marked.spanStyles.any { it.start == 7 && it.end == 13 && it.item == exact })
    assertEquals(2, marked.spanStyles.size, "invalid source ranges must not create fabricated marks")
  }

  private fun response(results: List<SearchResult> = emptyList()) = SearchResponse(
    requestGeneration = 1,
    bindingGeneration = ready.bindingGeneration,
    corpusGeneration = ready.corpusGeneration,
    readiness = SearchReadiness.READY,
    results = results,
  )
}
