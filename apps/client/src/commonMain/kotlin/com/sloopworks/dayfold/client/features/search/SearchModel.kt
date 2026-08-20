package com.sloopworks.dayfold.client

/** Hard allocation and work bounds for the first on-device search implementation. */
object SearchLimits {
  const val MIN_QUERY_NON_SPACE_CHARS = 2
  const val MAX_QUERY_CODE_UNITS = 128
  const val MAX_QUERY_TOKENS = 16
  const val MAX_DOCUMENT_CODE_UNITS = 65_536
  const val MAX_DOCUMENT_TOKENS = 8_192
  const val MAX_FUZZY_TOKEN_CODE_UNITS = 64
  const val MAX_RESULTS = 50
  const val MAX_EXCERPT_CODE_UNITS = 240
  const val MAX_RETURNED_DISPLAY_CODE_UNITS = 20_000
  const val DOCUMENT_CANCELLATION_INTERVAL = 64
  const val SOURCE_CANCELLATION_INTERVAL = 4_096
}

/** Bounds private UI state with the same surrogate-safe limit enforced by the matcher. */
fun boundSearchQueryInput(value: String): String =
  safePrefix(value, SearchLimits.MAX_QUERY_CODE_UNITS)

/** Whether the local cache is still awaiting its first sync, is known-empty, or is searchable. */
enum class SearchReadiness { WAITING_FOR_SYNC, EMPTY, READY }

enum class SearchDestinationKind { CARD, HUB, SECTION, BLOCK }

/** IDs only: no family identifier, text, query, or visibility metadata crosses navigation. */
data class SearchDestination(
  val kind: SearchDestinationKind,
  val cardId: String? = null,
  val hubId: String? = null,
  val sectionId: String? = null,
  val blockId: String? = null,
) {
  companion object {
    fun card(id: String) = SearchDestination(SearchDestinationKind.CARD, cardId = id)
    fun hub(id: String) = SearchDestination(SearchDestinationKind.HUB, hubId = id)
    fun section(hubId: String, sectionId: String) = SearchDestination(
      SearchDestinationKind.SECTION,
      hubId = hubId,
      sectionId = sectionId,
    )
    fun block(hubId: String, sectionId: String, blockId: String) = SearchDestination(
      SearchDestinationKind.BLOCK,
      hubId = hubId,
      sectionId = sectionId,
      blockId = blockId,
    )
  }
}

/**
 * One writer-gated, permission-filtered view of cached content. Applicable DONE response keys
 * are resolved by the store before this boundary; search never performs a second ACL decision.
 */
data class SearchContentSnapshot(
  val revision: Long,
  val readiness: SearchReadiness,
  val cards: List<Card> = emptyList(),
  val hubs: List<Hub> = emptyList(),
  val sections: List<HubSection> = emptyList(),
  val blocks: List<HubBlock> = emptyList(),
  val hiddenIds: Set<String> = emptySet(),
  val completedSubjectRefs: Set<String> = emptySet(),
)

/** Query-free lifecycle metadata exposed by the family-owned search engine. */
data class SearchStatus(
  val bindingGeneration: Long = 0,
  val corpusGeneration: Long = 0,
  val readiness: SearchReadiness = SearchReadiness.WAITING_FOR_SYNC,
)

data class SearchResultHandle(
  val destination: SearchDestination,
  val corpusGeneration: Long,
)

/** Half-open UTF-16 range into the exact String shipped beside it. */
data class SearchTextRange(val start: Int, val endExclusive: Int) {
  init {
    require(start >= 0 && endExclusive >= start)
  }
}

enum class SearchHighlightKind { EXACT, CLOSE }

data class SearchHighlight(
  val range: SearchTextRange,
  val kind: SearchHighlightKind,
)

data class SearchResult(
  val handle: SearchResultHandle,
  val title: String,
  val breadcrumb: String? = null,
  val excerpt: String? = null,
  val titleHighlights: List<SearchHighlight> = emptyList(),
  val breadcrumbHighlights: List<SearchHighlight> = emptyList(),
  val excerptHighlights: List<SearchHighlight> = emptyList(),
  val archived: Boolean = false,
  val closeMatch: Boolean = false,
)

/** Fixed, content-free error vocabulary. */
enum class SearchFailure { UNAVAILABLE }

/** Deliberately omits the raw query. This object is session-only despite containing excerpts. */
data class SearchResponse(
  val requestGeneration: Long,
  val bindingGeneration: Long,
  val corpusGeneration: Long,
  val readiness: SearchReadiness,
  val results: List<SearchResult> = emptyList(),
  val failure: SearchFailure? = null,
)

/** Allows an owning coroutine to inject ensureActive without making the pure core suspend. */
fun interface SearchCheckpoint {
  fun check()

  companion object {
    val NONE = SearchCheckpoint { }
  }
}

internal enum class SearchFieldKind { TITLE, BREADCRUMB, BODY, STRUCTURED }

internal data class IndexedSearchField(
  val kind: SearchFieldKind,
  val text: String,
  val tokens: List<SearchToken>,
)

internal data class SearchDocument(
  val destination: SearchDestination,
  val title: IndexedSearchField,
  val breadcrumb: IndexedSearchField?,
  val detail: List<IndexedSearchField>,
  val archived: Boolean,
  val stableOrder: Int,
)

/** Immutable corpus safe to publish between Kotlin/Native workers. */
class SearchCorpus internal constructor(
  val sourceRevision: Long,
  val readiness: SearchReadiness,
  internal val documents: List<SearchDocument>,
) {
  val size: Int get() = documents.size

  fun contains(destination: SearchDestination): Boolean =
    documents.any { it.destination == destination }
}
