package com.sloopworks.dayfold.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class SearchNativeTest {
  @Test
  fun exactAndCloseMatch_useSharedMatcherOnNative() {
    val corpus = corpusOf(
      Card(
        id = "target",
        title = "Soccer registration closes Friday",
        bodyMd = "Register for the fall recreational season.",
      ),
    )

    val exact = searchCorpus(corpus, "soccer")
    val exactResult = assertNotNull(exact.results.singleOrNull())
    assertFalse(exactResult.closeMatch)
    assertEquals("Soccer", exactResult.title.highlightedSource(exactResult.titleHighlights.single()))

    val close = searchCorpus(corpus, "socer")
    val closeResult = assertNotNull(close.results.singleOrNull())
    assertTrue(closeResult.closeMatch)
    assertEquals(SearchHighlightKind.CLOSE, closeResult.titleHighlights.single().kind)
    assertEquals("Soccer", closeResult.title.highlightedSource(closeResult.titleHighlights.single()))
  }

  @Test
  fun corpusPublication_isDetachedAndSafeForConcurrentNativeReaders() = runBlocking<Unit> {
    val mutableCards = MutableList(256) { index ->
      Card(
        id = "card-$index",
        title = "Family reminder $index",
        bodyMd = "Bring the permission slip for activity $index.",
      )
    }
    val corpus = buildSearchCorpus(
      SearchContentSnapshot(
        revision = 41,
        readiness = SearchReadiness.READY,
        cards = mutableCards,
      ),
    )

    mutableCards.clear()
    mutableCards += Card(id = "late-mutation", title = "Must not enter the published corpus")

    assertEquals(256, corpus.size)
    assertFalse(corpus.contains(SearchDestination.card("late-mutation")))
    val results = List(24) {
      async(Dispatchers.Default) {
        searchCorpus(corpus, "permission slip").results.map { it.handle.destination }
      }
    }.awaitAll()
    assertTrue(results.all { it == results.first() })
    assertEquals(50, results.first().size)
  }

  @Test
  fun unicodeHighlights_preserveOriginalUtf16Ranges() {
    val decomposed = "Cafe\u0301 plans"
    val corpus = corpusOf(
      Card(id = "precomposed", title = "Café plans"),
      Card(id = "decomposed", title = decomposed),
      Card(id = "emoji", title = "😀 soccer"),
      Card(id = "cjk", title = "家族 予定"),
    )

    val accentResults = searchCorpus(corpus, "cafe").results.associateBy {
      it.handle.destination.cardId
    }
    val precomposed = assertNotNull(accentResults["precomposed"])
    assertEquals("Café", precomposed.title.highlightedSource(precomposed.titleHighlights.single()))
    val decomposedResult = assertNotNull(accentResults["decomposed"])
    assertEquals(
      decomposed.substring(0, 5),
      decomposedResult.title.highlightedSource(decomposedResult.titleHighlights.single()),
    )

    val emoji = assertNotNull(searchCorpus(corpus, "soccer").results.singleOrNull())
    val emojiRange = emoji.titleHighlights.single().range
    assertEquals("soccer", emoji.title.substring(emojiRange.start, emojiRange.endExclusive))
    assertFalse(emoji.title[emojiRange.start].isLowSurrogate())

    val cjk = assertNotNull(searchCorpus(corpus, "家族").results.singleOrNull())
    assertFalse(cjk.closeMatch)
    assertEquals("家族", cjk.title.highlightedSource(cjk.titleHighlights.single()))
  }

  /** Diagnostic only: host speed varies, so correctness—not wall time—controls pass/fail. */
  @Test
  fun boundedPerformanceProbe_reports500_2000_10000Documents() {
    listOf(500, 2_000, 10_000).forEach { documentCount ->
      val cards = List(documentCount) { index ->
        Card(
          id = "probe-$index",
          title = "Family reminder $index",
          bodyMd = "Bring the permission slip to activity $index before Friday.",
        )
      }

      val buildMark = TimeSource.Monotonic.markNow()
      val corpus = corpusOf(*cards.toTypedArray())
      val buildDuration = buildMark.elapsedNow()

      val exactMark = TimeSource.Monotonic.markNow()
      val exact = searchCorpus(corpus, "family reminder")
      val exactDuration = exactMark.elapsedNow()

      val prefixMark = TimeSource.Monotonic.markNow()
      val prefix = searchCorpus(corpus, "fam rem")
      val prefixDuration = prefixMark.elapsedNow()

      val zeroHitMark = TimeSource.Monotonic.markNow()
      val zeroHit = searchCorpus(corpus, "remindr qzzx")
      val zeroHitDuration = zeroHitMark.elapsedNow()

      assertEquals(documentCount, corpus.size)
      assertEquals(SearchLimits.MAX_RESULTS, exact.results.size)
      assertEquals(SearchLimits.MAX_RESULTS, prefix.results.size)
      assertTrue(zeroHit.results.isEmpty())
      println(
        "search-native-perf documents=$documentCount build=$buildDuration " +
          "exact=$exactDuration prefix=$prefixDuration zero_hit=$zeroHitDuration",
      )
    }
  }

  private fun corpusOf(vararg cards: Card): SearchCorpus = buildSearchCorpus(
    SearchContentSnapshot(
      revision = 1,
      readiness = SearchReadiness.READY,
      cards = cards.toList(),
    ),
  )

  private fun String.highlightedSource(highlight: SearchHighlight): String =
    substring(highlight.range.start, highlight.range.endExclusive)
}
