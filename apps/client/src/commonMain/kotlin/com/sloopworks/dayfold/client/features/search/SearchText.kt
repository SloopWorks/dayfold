package com.sloopworks.dayfold.client

internal data class SearchToken(
  val normalized: String,
  val sourceRange: SearchTextRange,
) {
  val fuzzyEligible: Boolean get() =
    normalized.length in 4..SearchLimits.MAX_FUZZY_TOKEN_CODE_UNITS &&
      normalized.all { it in 'a'..'z' || it in '0'..'9' }
}

internal data class NormalizedSearchQuery(val tokens: List<SearchToken>)

internal fun normalizeSearchQuery(
  query: String,
  checkpoint: SearchCheckpoint = SearchCheckpoint.NONE,
): NormalizedSearchQuery? {
  val bounded = safePrefix(query, SearchLimits.MAX_QUERY_CODE_UNITS)
  if (countNonSpaceCodePoints(bounded, SearchLimits.MIN_QUERY_NON_SPACE_CHARS) <
    SearchLimits.MIN_QUERY_NON_SPACE_CHARS
  ) return null
  val tokens = tokenizeSearchText(bounded, SearchLimits.MAX_QUERY_TOKENS, checkpoint)
  return tokens.takeIf { it.isNotEmpty() }?.let(::NormalizedSearchQuery)
}

/**
 * Removes presentational Markdown punctuation while retaining authored words and link targets.
 * The returned String is the exact display/search source whose UTF-16 ranges are reported.
 */
internal fun cleanSearchText(source: String): String {
  if (source.isEmpty()) return source
  val out = StringBuilder(source.length.coerceAtMost(SearchLimits.MAX_DOCUMENT_CODE_UNITS))
  var i = 0
  var pendingSpace = false
  while (i < source.length) {
    val c = source[i]
    when {
      c == '\\' && i + 1 < source.length -> {
        if (pendingSpace && out.isNotEmpty()) out.append(' ')
        pendingSpace = false
        out.append(source[i + 1])
        i += 2
      }
      c == '\n' || c == '\r' || c == '\t' || c.isWhitespace() -> {
        pendingSpace = out.isNotEmpty()
        i++
      }
      c == '#' || c == '*' || c == '_' || c == '~' || c == '`' ||
        c == '[' || c == ']' || c == '(' || c == ')' || c == '>' -> {
        // Brackets become a boundary ("label](url" must not join); emphasis markers do not.
        if (c == '[' || c == ']' || c == '(' || c == ')' || c == '>') {
          pendingSpace = out.isNotEmpty()
        }
        i++
      }
      else -> {
        if (pendingSpace && out.isNotEmpty()) out.append(' ')
        pendingSpace = false
        out.append(c)
        i++
      }
    }
  }
  return out.toString().trim()
}

internal fun tokenizeSearchText(
  source: String,
  maxTokens: Int,
  checkpoint: SearchCheckpoint = SearchCheckpoint.NONE,
): List<SearchToken> {
  if (source.isEmpty() || maxTokens <= 0) return emptyList()
  val result = ArrayList<SearchToken>(minOf(maxTokens, 32))
  var tokenStart = -1
  var tokenEnd = -1
  var normalized = StringBuilder()
  var i = 0
  var nextCheckpoint = SearchLimits.SOURCE_CANCELLATION_INTERVAL

  fun flush() {
    if (tokenStart >= 0 && normalized.isNotEmpty() && result.size < maxTokens) {
      result += SearchToken(normalized.toString(), SearchTextRange(tokenStart, tokenEnd))
    }
    tokenStart = -1
    tokenEnd = -1
    normalized = StringBuilder()
  }

  while (i < source.length && result.size < maxTokens) {
    if (i >= nextCheckpoint) {
      checkpoint.check()
      nextCheckpoint += SearchLimits.SOURCE_CANCELLATION_INTERVAL
    }
    val width = codePointWidthAt(source, i)
    val cp = codePointAt(source, i, width)
    val combining = isCombiningMark(cp)
    if (combining && tokenStart >= 0) {
      // The folded form drops the mark, while its source range remains attached to the token.
      tokenEnd = i + width
    } else if (isTokenCodePoint(cp)) {
      if (tokenStart < 0) tokenStart = i
      appendFolded(normalized, cp, source, i, width)
      tokenEnd = i + width
    } else {
      flush()
    }
    i += width
  }
  flush()
  return result
}

internal fun safePrefix(source: String, maxCodeUnits: Int): String {
  if (source.length <= maxCodeUnits) return source
  var end = maxCodeUnits.coerceAtLeast(0)
  if (end > 0 && end < source.length && source[end - 1].isHighSurrogate() && source[end].isLowSurrogate()) {
    end--
  }
  // Do not keep a base while discarding the combining marks immediately following it.
  if (end < source.length && isCombiningMark(source[end].code)) {
    while (end > 0 && isCombiningMark(source[end - 1].code)) end--
    if (end > 0) {
      end--
      if (end > 0 && source[end].isLowSurrogate() && source[end - 1].isHighSurrogate()) end--
    }
  }
  return source.substring(0, end)
}

internal fun safeRange(source: String, start: Int, endExclusive: Int): SearchTextRange {
  var startSafe = start.coerceIn(0, source.length)
  var endSafe = endExclusive.coerceIn(startSafe, source.length)
  if (startSafe > 0 && startSafe < source.length && source[startSafe].isLowSurrogate() && source[startSafe - 1].isHighSurrogate()) {
    startSafe--
  }
  while (startSafe > 0 && startSafe < source.length && isCombiningMark(source[startSafe].code)) startSafe--
  if (
    startSafe > 0 && startSafe < source.length &&
    source[startSafe].isLowSurrogate() && source[startSafe - 1].isHighSurrogate()
  ) startSafe--
  if (endSafe > 0 && endSafe < source.length && source[endSafe - 1].isHighSurrogate() && source[endSafe].isLowSurrogate()) {
    endSafe++
  }
  while (endSafe < source.length && isCombiningMark(source[endSafe].code)) endSafe++
  return SearchTextRange(startSafe, endSafe)
}

private fun countNonSpaceCodePoints(source: String, stopAt: Int): Int {
  var count = 0
  var i = 0
  while (i < source.length && count < stopAt) {
    val width = codePointWidthAt(source, i)
    val cp = codePointAt(source, i, width)
    val space = if (cp <= 0xffff) cp.toChar().isWhitespace() else false
    if (!space) count++
    i += width
  }
  return count
}

private fun codePointWidthAt(source: String, index: Int): Int =
  if (source[index].isHighSurrogate() && index + 1 < source.length && source[index + 1].isLowSurrogate()) 2 else 1

private fun codePointAt(source: String, index: Int, width: Int): Int {
  if (width == 1) return source[index].code
  val high = source[index].code - 0xd800
  val low = source[index + 1].code - 0xdc00
  return 0x10000 + (high shl 10) + low
}

private fun isTokenCodePoint(cp: Int): Boolean {
  if (isCombiningMark(cp)) return false
  if (cp > 0xffff) return true // Emoji/supplementary scripts remain searchable exact-only.
  val c = cp.toChar()
  if (c.isWhitespace() || c.isISOControl()) return false
  return c !in SEARCH_SEPARATORS
}

private val SEARCH_SEPARATORS = setOf(
  '.', ',', ':', ';', '!', '?', '/', '\\', '|', '@', '&', '+', '=',
  '-', '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2212',
  '\'', '\u2018', '\u2019', '\u02bc', '"', '\u201c', '\u201d',
  '(', ')', '[', ']', '{', '}', '<', '>', '#', '*', '_', '~', '`', '^', '%', '$',
)

internal fun isCombiningMark(cp: Int): Boolean =
  cp in 0x0300..0x036f || cp in 0x1ab0..0x1aff || cp in 0x1dc0..0x1dff ||
    cp in 0x20d0..0x20ff || cp in 0xfe20..0xfe2f

private fun appendFolded(out: StringBuilder, cp: Int, source: String, index: Int, width: Int) {
  when (cp) {
    in 'A'.code..'Z'.code -> out.append((cp + ('a'.code - 'A'.code)).toChar())
    0x00c0, 0x00c1, 0x00c2, 0x00c3, 0x00c4, 0x00c5,
    0x00e0, 0x00e1, 0x00e2, 0x00e3, 0x00e4, 0x00e5,
    0x0100, 0x0101, 0x0102, 0x0103, 0x0104, 0x0105 -> out.append('a')
    0x00c7, 0x00e7, 0x0106, 0x0107, 0x0108, 0x0109, 0x010a, 0x010b, 0x010c, 0x010d -> out.append('c')
    0x00d0, 0x00f0, 0x010e, 0x010f, 0x0110, 0x0111 -> out.append('d')
    0x00c8, 0x00c9, 0x00ca, 0x00cb, 0x00e8, 0x00e9, 0x00ea, 0x00eb,
    0x0112, 0x0113, 0x0116, 0x0117, 0x0118, 0x0119, 0x011a, 0x011b -> out.append('e')
    0x011e, 0x011f, 0x0122, 0x0123 -> out.append('g')
    0x0128, 0x0129, 0x012a, 0x012b, 0x012e, 0x012f, 0x0130, 0x0131,
    0x00cc, 0x00cd, 0x00ce, 0x00cf, 0x00ec, 0x00ed, 0x00ee, 0x00ef -> out.append('i')
    0x0139, 0x013a, 0x013d, 0x013e, 0x0141, 0x0142 -> out.append('l')
    0x00d1, 0x00f1, 0x0143, 0x0144, 0x0147, 0x0148 -> out.append('n')
    0x00d2, 0x00d3, 0x00d4, 0x00d5, 0x00d6, 0x00d8,
    0x00f2, 0x00f3, 0x00f4, 0x00f5, 0x00f6, 0x00f8,
    0x014c, 0x014d, 0x0150, 0x0151 -> out.append('o')
    0x0154, 0x0155, 0x0158, 0x0159 -> out.append('r')
    0x015a, 0x015b, 0x015e, 0x015f, 0x0160, 0x0161 -> out.append('s')
    0x0162, 0x0163, 0x0164, 0x0165 -> out.append('t')
    0x00d9, 0x00da, 0x00db, 0x00dc, 0x00f9, 0x00fa, 0x00fb, 0x00fc,
    0x016a, 0x016b, 0x016e, 0x016f, 0x0170, 0x0171, 0x0172, 0x0173 -> out.append('u')
    0x00dd, 0x00fd, 0x00ff, 0x0178 -> out.append('y')
    0x0179, 0x017a, 0x017b, 0x017c, 0x017d, 0x017e -> out.append('z')
    0x00c6, 0x00e6 -> out.append("ae")
    0x0152, 0x0153 -> out.append("oe")
    0x00df -> out.append("ss")
    else -> out.append(source, index, index + width)
  }
}
