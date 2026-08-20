package com.sloopworks.dayfold.client

private const val FUZZY_SPARSE_THRESHOLD = 5
private const val MAX_RESULT_TITLE_CODE_UNITS = 200
private const val MAX_RESULT_BREADCRUMB_CODE_UNITS = 80
private const val MAX_RESULT_EXCERPT_CODE_UNITS = 120
private const val MAX_RESULT_DISPLAY_CODE_UNITS =
  MAX_RESULT_TITLE_CODE_UNITS + MAX_RESULT_BREADCRUMB_CODE_UNITS + MAX_RESULT_EXCERPT_CODE_UNITS

private enum class TokenMatchKind { EXACT, PREFIX, SUBSTRING, FUZZY }

private data class FieldHit(
  val field: IndexedSearchField,
  val token: SearchToken,
  val kind: TokenMatchKind,
  val score: Int,
)

private data class MatchCandidate(
  val document: SearchDocument,
  val hits: List<FieldHit>,
  val score: Int,
  val closeMatch: Boolean,
)

/** Pure bounded scan. The owning engine fences revisions before publishing this response. */
fun searchCorpus(
  corpus: SearchCorpus,
  query: String,
  requestGeneration: Long = 0,
  bindingGeneration: Long = 0,
  corpusGeneration: Long = corpus.sourceRevision,
  checkpoint: SearchCheckpoint = SearchCheckpoint.NONE,
): SearchResponse {
  val normalizedQuery = normalizeSearchQuery(query, checkpoint)
  if (normalizedQuery == null || corpus.readiness != SearchReadiness.READY) {
    return SearchResponse(
      requestGeneration,
      bindingGeneration,
      corpusGeneration,
      corpus.readiness,
    )
  }

  val top = ArrayList<MatchCandidate>(SearchLimits.MAX_RESULTS)
  corpus.documents.forEachIndexed { index, document ->
    if (index % SearchLimits.DOCUMENT_CANCELLATION_INTERVAL == 0) checkpoint.check()
    matchDocument(document, normalizedQuery, allowFuzzy = false, checkpoint)?.let { top.insertBounded(it) }
  }

  // Fuzzy is a recall fallback, not a co-equal ranker. Once lexical search is useful, stop.
  if (top.size < FUZZY_SPARSE_THRESHOLD) {
    val lexicalDestinations = top.mapTo(HashSet(top.size)) { it.document.destination }
    corpus.documents.forEachIndexed { index, document ->
      if (index % SearchLimits.DOCUMENT_CANCELLATION_INTERVAL == 0) checkpoint.check()
      if (document.destination !in lexicalDestinations) {
        matchDocument(document, normalizedQuery, allowFuzzy = true, checkpoint)
          ?.takeIf { it.closeMatch }
          ?.let { top.insertBounded(it) }
      }
    }
  }

  var remainingDisplay = SearchLimits.MAX_RETURNED_DISPLAY_CODE_UNITS
  val results = ArrayList<SearchResult>(top.size)
  top.forEach { candidate ->
    if (remainingDisplay <= 0) return@forEach
    val result = candidate.toResult(corpusGeneration, minOf(MAX_RESULT_DISPLAY_CODE_UNITS, remainingDisplay))
    val used = result.title.length + result.breadcrumb.orEmpty().length + result.excerpt.orEmpty().length
    if (result.title.isNotEmpty()) {
      results += result
      remainingDisplay -= used
    }
  }
  return SearchResponse(
    requestGeneration = requestGeneration,
    bindingGeneration = bindingGeneration,
    corpusGeneration = corpusGeneration,
    readiness = corpus.readiness,
    results = results,
  )
}

private fun matchDocument(
  document: SearchDocument,
  query: NormalizedSearchQuery,
  allowFuzzy: Boolean,
  checkpoint: SearchCheckpoint,
): MatchCandidate? {
  val fields = buildList {
    add(document.title)
    document.breadcrumb?.let(::add)
    addAll(document.detail)
  }
  val hits = ArrayList<FieldHit>(query.tokens.size)
  var usedFuzzy = false
  var scanCodeUnits = 0

  for (queryToken in query.tokens) {
    var best: FieldHit? = null
    fields.forEach { field ->
      field.tokens.forEach { target ->
        scanCodeUnits += target.sourceRange.endExclusive - target.sourceRange.start
        if (scanCodeUnits >= SearchLimits.SOURCE_CANCELLATION_INTERVAL) {
          checkpoint.check()
          scanCodeUnits = 0
        }
        val kind = lexicalKind(queryToken.normalized, target.normalized) ?: return@forEach
        val hit = FieldHit(field, target, kind, hitScore(field.kind, kind))
        if (best == null || hit.score > best!!.score) best = hit
      }
    }

    if (best == null && allowFuzzy && !usedFuzzy && queryToken.fuzzyEligible) {
      fields.forEach { field ->
        field.tokens.forEach { target ->
          scanCodeUnits += target.sourceRange.endExclusive - target.sourceRange.start
          if (scanCodeUnits >= SearchLimits.SOURCE_CANCELLATION_INTERVAL) {
            checkpoint.check()
            scanCodeUnits = 0
          }
          if (target.fuzzyEligible && isCloseToken(queryToken.normalized, target.normalized)) {
            val hit = FieldHit(field, target, TokenMatchKind.FUZZY, hitScore(field.kind, TokenMatchKind.FUZZY))
            if (best == null || hit.score > best!!.score) best = hit
          }
        }
      }
      if (best != null) usedFuzzy = true
    }
    hits += best ?: return null
  }

  var score = hits.sumOf(FieldHit::score)
  if (query.tokens.size > 1) {
    if (hasExactPhrase(document.title, query)) score += 1_000
    else if (document.detail.any { hasExactPhrase(it, query) }) score += 200
    else if (document.breadcrumb?.let { hasExactPhrase(it, query) } == true) score += 100
  }
  return MatchCandidate(document, hits, score, usedFuzzy)
}

private fun lexicalKind(query: String, target: String): TokenMatchKind? = when {
  target == query -> TokenMatchKind.EXACT
  target.startsWith(query) -> TokenMatchKind.PREFIX
  target.contains(query) -> TokenMatchKind.SUBSTRING
  else -> null
}

private fun hitScore(field: SearchFieldKind, kind: TokenMatchKind): Int {
  val match = when (kind) {
    TokenMatchKind.EXACT -> 300
    TokenMatchKind.PREFIX -> 220
    TokenMatchKind.SUBSTRING -> 140
    TokenMatchKind.FUZZY -> 30
  }
  val fieldBoost = when (field) {
    SearchFieldKind.TITLE -> 240
    SearchFieldKind.BREADCRUMB -> 40
    SearchFieldKind.BODY -> 60
    SearchFieldKind.STRUCTURED -> 20
  }
  return match + fieldBoost
}

private fun hasExactPhrase(field: IndexedSearchField, query: NormalizedSearchQuery): Boolean {
  if (query.tokens.size > field.tokens.size) return false
  val lastStart = field.tokens.size - query.tokens.size
  for (start in 0..lastStart) {
    var matches = true
    for (offset in query.tokens.indices) {
      if (field.tokens[start + offset].normalized != query.tokens[offset].normalized) {
        matches = false
        break
      }
    }
    if (matches) return true
  }
  return false
}

private fun MutableList<MatchCandidate>.insertBounded(candidate: MatchCandidate) {
  var index = 0
  while (index < size && !candidate.betterThan(this[index])) index++
  if (index >= SearchLimits.MAX_RESULTS) return
  add(index, candidate)
  if (size > SearchLimits.MAX_RESULTS) removeAt(lastIndex)
}

private fun MatchCandidate.betterThan(other: MatchCandidate): Boolean = when {
  closeMatch != other.closeMatch -> !closeMatch
  score != other.score -> score > other.score
  else -> document.stableOrder < other.document.stableOrder
}

private fun MatchCandidate.toResult(corpusGeneration: Long, budget: Int): SearchResult {
  var remaining = budget
  val titleHits = hits.filter { it.field === document.title }
  val titleSegment = segment(
    document.title,
    titleHits,
    minOf(MAX_RESULT_TITLE_CODE_UNITS, remaining),
    centerOnHit = false,
  )
  remaining -= titleSegment.text.length

  val breadcrumbHits = document.breadcrumb?.let { field -> hits.filter { it.field === field } }.orEmpty()
  val breadcrumbSegment = document.breadcrumb?.takeIf { remaining > 0 }?.let { field ->
    segment(field, breadcrumbHits, minOf(MAX_RESULT_BREADCRUMB_CODE_UNITS, remaining), centerOnHit = false)
  }
  remaining -= breadcrumbSegment?.text.orEmpty().length

  val detailHit = hits.filter { it.field.kind == SearchFieldKind.BODY || it.field.kind == SearchFieldKind.STRUCTURED }
    .maxByOrNull { it.score }
  val excerptField = detailHit?.field ?: document.detail.firstOrNull()
  val excerptHits = excerptField?.let { field -> hits.filter { it.field === field } }.orEmpty()
  val excerptSegment = excerptField?.takeIf { remaining > 0 }?.let { field ->
    segment(
      field,
      excerptHits,
      minOf(SearchLimits.MAX_EXCERPT_CODE_UNITS, MAX_RESULT_EXCERPT_CODE_UNITS, remaining),
      centerOnHit = true,
    )
  }

  return SearchResult(
    handle = SearchResultHandle(document.destination, corpusGeneration),
    title = titleSegment.text,
    breadcrumb = breadcrumbSegment?.text?.takeIf(String::isNotEmpty),
    excerpt = excerptSegment?.text?.takeIf(String::isNotEmpty),
    titleHighlights = titleSegment.highlights,
    breadcrumbHighlights = breadcrumbSegment?.highlights.orEmpty(),
    excerptHighlights = excerptSegment?.highlights.orEmpty(),
    archived = document.archived,
    closeMatch = closeMatch,
  )
}

private data class DisplaySegment(val text: String, val highlights: List<SearchHighlight>)

private fun segment(
  field: IndexedSearchField,
  hits: List<FieldHit>,
  maxLength: Int,
  centerOnHit: Boolean,
): DisplaySegment {
  if (maxLength <= 0 || field.text.isEmpty()) return DisplaySegment("", emptyList())
  val anchor = hits.minOfOrNull { it.token.sourceRange.start } ?: 0
  var start = if (centerOnHit && field.text.length > maxLength) (anchor - maxLength / 3).coerceAtLeast(0) else 0
  if (start > 0) {
    while (start < anchor && !field.text[start].isWhitespace()) start++
    while (start < anchor && field.text[start].isWhitespace()) start++
  }
  val safeStart = safeRange(field.text, start, start).start
  val tail = field.text.substring(safeStart)
  val text = safePrefix(tail, maxLength)
  val end = safeStart + text.length
  val highlights = hits.mapNotNull { hit ->
    val range = safeRange(field.text, hit.token.sourceRange.start, hit.token.sourceRange.endExclusive)
    if (range.start < safeStart || range.endExclusive > end) null else SearchHighlight(
      SearchTextRange(range.start - safeStart, range.endExclusive - safeStart),
      if (hit.kind == TokenMatchKind.FUZZY) SearchHighlightKind.CLOSE else SearchHighlightKind.EXACT,
    )
  }.distinct()
  return DisplaySegment(text, highlights)
}

private fun isCloseToken(query: String, target: String): Boolean {
  if (query == target || query.length !in 4..SearchLimits.MAX_FUZZY_TOKEN_CODE_UNITS) return false
  val allowed = if (query.length <= 7) 1 else 2
  if (kotlin.math.abs(query.length - target.length) > allowed) return false
  return osaDistanceAtMost(query, target, allowed)
}

/** Bounded optimal-string-alignment distance with transposition and row early exit. */
private fun osaDistanceAtMost(a: String, b: String, maximum: Int): Boolean {
  var previousPrevious = IntArray(b.length + 1) { it }
  var previous = IntArray(b.length + 1) { it }
  var current = IntArray(b.length + 1)
  for (i in 1..a.length) {
    current[0] = i
    var rowMin = current[0]
    for (j in 1..b.length) {
      val substitutionCost = if (a[i - 1] == b[j - 1]) 0 else 1
      var value = minOf(
        previous[j] + 1,
        current[j - 1] + 1,
        previous[j - 1] + substitutionCost,
      )
      if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
        value = minOf(value, previousPrevious[j - 2] + 1)
      }
      current[j] = value
      rowMin = minOf(rowMin, value)
    }
    if (rowMin > maximum) return false
    val swap = previousPrevious
    previousPrevious = previous
    previous = current
    current = swap
  }
  return previous[b.length] <= maximum
}
