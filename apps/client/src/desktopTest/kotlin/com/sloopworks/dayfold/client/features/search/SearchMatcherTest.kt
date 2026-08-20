package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SearchMatcherTest {
  @Test fun `bounded fold treats composed and decomposed Latin text consistently`() {
    val cases = listOf(
      "Cafe" to "Café plans",
      "cafe" to "Cafe\u0301 plans",
      "izin" to "İzin form",
      "oeuvre" to "Œuvre notes",
    )
    cases.forEachIndexed { index, (query, title) ->
      val response = search(cards = listOf(Card("c$index", title = title)), query = query)
      assertEquals("c$index", response.results.single().handle.destination.cardId, "$query → $title")
      assertTrue(response.results.single().titleHighlights.isNotEmpty())
    }
  }

  @Test fun `apostrophes hyphens emoji and CJK keep truthful source ranges`() {
    val punctuation = listOf(
      "Jake's Rentals" to "Jake’s Rentals",
      "permission slip" to "permission-slip",
      "family plan" to "family—plan",
    )
    punctuation.forEachIndexed { index, (query, title) ->
      assertEquals("c$index", search(listOf(Card("c$index", title = title)), query).results.single().handle.destination.cardId)
    }

    val emoji = search(listOf(Card("emoji", title = "Go 🎉 party")), "🎉 party").results.single()
    assertTrue(emoji.titleHighlights.any { highlight ->
      val value = emoji.title.substring(highlight.range.start, highlight.range.endExclusive)
      value == "🎉"
    })
    emoji.titleHighlights.forEach { assertNoBrokenUtf16(emoji.title, it.range) }

    val cjk = search(listOf(Card("cjk", title = "家庭旅行")), "家庭").results.single()
    assertFalse(cjk.closeMatch)
    assertEquals("家庭旅行", cjk.title)
  }

  @Test fun `lexical ranking is stable and exact always precedes close matches`() {
    val cards = listOf(
      Card("body", title = "Saturday plans", bodyMd = "Return the permission slip"),
      Card("prefix", title = "Permission slips"),
      Card("exact", title = "Permission slip"),
      Card("fuzzy", title = "Permission slop"),
    )
    val phrase = search(cards, "permission slip")
    assertEquals(listOf("exact", "prefix", "body"), phrase.results.take(3).map { it.handle.destination.cardId })

    val close = search(
      listOf(Card("same-1", title = "Socer"), Card("same-2", title = "Socer"), Card("close", title = "Soccer")),
      "socer",
    )
    assertEquals(listOf("same-1", "same-2", "close"), close.results.map { it.handle.destination.cardId })
    assertFalse(close.results[0].closeMatch)
    assertTrue(close.results.last().closeMatch)
    val tealTarget = close.results.last().titleHighlights.single()
    assertEquals(SearchHighlightKind.CLOSE, tealTarget.kind)
    assertEquals("Soccer", close.results.last().title.substring(tealTarget.range.start, tealTarget.range.endExclusive))
  }

  @Test fun `every query token must match and fuzzy is limited to one eligible token`() {
    val card = Card("soccer", title = "Soccer practice", bodyMd = "Saturday at the park")
    assertEquals("soccer", search(listOf(card), "socer practice").results.single().handle.destination.cardId)
    assertTrue(search(listOf(card), "socer practce").results.isEmpty(), "two fuzzy tokens are rejected")
    assertTrue(search(listOf(card), "socr").results.isEmpty(), "a misspelled four-char token still needs distance <=1")
    assertTrue(search(listOf(card), "sox").results.isEmpty(), "under-four fuzzy is disabled")
    assertTrue(search(listOf(card), "soccer Tuesday").results.isEmpty(), "all tokens are required")

    val cyrillic = Card("ru", title = "привет семье")
    assertTrue(search(listOf(cyrillic), "превет").results.isEmpty(), "non-Latin fuzzy is disabled")
    assertEquals("ru", search(listOf(cyrillic), "привет").results.single().handle.destination.cardId)
  }

  @Test fun `minimum input and query token bounds are deterministic`() {
    val card = Card("bounded", title = (1..16).joinToString(" ") { "word$it" })
    assertTrue(search(listOf(card), " ").results.isEmpty())
    assertTrue(search(listOf(card), "a").results.isEmpty())

    val seventeenTokens = (1..17).joinToString(" ") { "word$it" }
    assertEquals("bounded", search(listOf(card), seventeenTokens).results.single().handle.destination.cardId)

    val query = "needle" + "x".repeat(SearchLimits.MAX_QUERY_CODE_UNITS * 2)
    val boundedQuery = normalizeSearchQuery(query)
    assertNotNull(boundedQuery)
    assertTrue(boundedQuery.tokens.single().sourceRange.endExclusive <= SearchLimits.MAX_QUERY_CODE_UNITS)
  }

  @Test fun `top K and returned display text are bounded without disturbing stable ties`() {
    val cards = (0 until 80).map { index ->
      Card("c$index", title = "Needle result $index " + "x".repeat(500), bodyMd = "needle " + "y".repeat(500))
    }
    val response = search(cards, "needle")
    assertEquals(SearchLimits.MAX_RESULTS, response.results.size)
    assertEquals((0 until 50).map { "c$it" }, response.results.map { it.handle.destination.cardId })
    val returned = response.results.sumOf { it.title.length + it.breadcrumb.orEmpty().length + it.excerpt.orEmpty().length }
    assertTrue(returned <= SearchLimits.MAX_RETURNED_DISPLAY_CODE_UNITS)
    assertTrue(response.results.all { it.excerpt.orEmpty().length <= SearchLimits.MAX_EXCERPT_CODE_UNITS })
  }

  @Test fun `late punctuation-delimited match stays in highlighted excerpt`() {
    val prefix = "x".repeat(1_000)
    val result = search(listOf(Card("late", title = "Late note", bodyMd = "$prefix.needle")), "needle").results.single()
    val excerpt = assertNotNull(result.excerpt)
    val highlight = result.excerptHighlights.single()
    assertEquals("needle", excerpt.substring(highlight.range.start, highlight.range.endExclusive))
  }

  @Test fun `document character and token budgets omit deterministic tails`() {
    val longTitle = "start " + "x".repeat(SearchLimits.MAX_DOCUMENT_CODE_UNITS) + " forbiddenneedle"
    assertTrue(search(listOf(Card("chars", title = longTitle)), "forbiddenneedle").results.isEmpty())

    val tooManyTokens = (0..SearchLimits.MAX_DOCUMENT_TOKENS).joinToString(" ") { index ->
      if (index == SearchLimits.MAX_DOCUMENT_TOKENS) "forbiddentail" else "t$index"
    }
    assertTrue(search(listOf(Card("tokens", title = tooManyTokens)), "forbiddentail").results.isEmpty())
  }

  @Test fun `checkpoint is visited during corpus construction and scans`() {
    var checks = 0
    val checkpoint = SearchCheckpoint { checks++ }
    val cards = (0 until 130).map { Card("c$it", title = "Needle $it", bodyMd = "x".repeat(5_000)) }
    val corpus = buildSearchCorpus(
      SearchContentSnapshot(1, SearchReadiness.READY, cards = cards),
      checkpoint,
    )
    val afterBuild = checks
    assertTrue(afterBuild > 3)
    searchCorpus(corpus, "needle", checkpoint = checkpoint)
    assertTrue(checks > afterBuild)
  }

  @Test fun `response and result handles carry request and corpus generations`() {
    val response = searchCorpus(
      corpus = buildSearchCorpus(SearchContentSnapshot(9, SearchReadiness.READY, cards = listOf(Card("c", title = "Secret needle")))),
      query = "secret needle",
      requestGeneration = 4,
      bindingGeneration = 7,
      corpusGeneration = 11,
    )
    assertEquals(4, response.requestGeneration)
    assertEquals(7, response.bindingGeneration)
    assertEquals(11, response.corpusGeneration)
    assertEquals(11, response.results.single().handle.corpusGeneration)
  }

  private fun search(cards: List<Card>, query: String): SearchResponse = searchCorpus(
    buildSearchCorpus(SearchContentSnapshot(1, SearchReadiness.READY, cards = cards)),
    query,
    corpusGeneration = 3,
  )

  private fun assertNoBrokenUtf16(text: String, range: SearchTextRange) {
    assertFalse(range.start > 0 && text[range.start].isLowSurrogate() && text[range.start - 1].isHighSurrogate())
    assertFalse(
      range.endExclusive in 1 until text.length &&
        text[range.endExclusive - 1].isHighSurrogate() && text[range.endExclusive].isLowSurrogate(),
    )
  }
}
