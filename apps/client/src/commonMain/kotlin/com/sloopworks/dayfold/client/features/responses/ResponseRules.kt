package com.sloopworks.dayfold.client

// ADR 0064 decided-Q4 — the derived lane reads the SAME rule list, enforced on-device.
// The server never sees derived items (they are synthesized here and persisted nowhere), so
// it cannot enforce them; this is the only place that can.
//
// `matches` mirrors the server's matchesRule() exactly: three string equalities, no content
// inspection, no prefix matching. The two must agree — a member who mutes something in the
// feed expects it gone from BOTH lanes, and a divergence shows up as a card that half
// disappears.
object ResponseRules {

  fun matches(
    rule: ContentResponse,
    subjectRef: String,
    reasonKind: String,
    source: String?,
  ): Boolean = when (rule.matchScope) {
    // EXACT equality, never prefix containment — a hub-level key is a prefix of every block
    // under that hub, so a prefix match would silently mute the whole hub. (The ranker
    // rejected prefix containment for dedup for the same reason.)
    MatchScope.SUBJECT -> subjectRef == rule.subjectRef
    MatchScope.KIND -> "kind:$reasonKind" == rule.subjectRef
    MatchScope.SOURCE -> source != null && "source:$source" == rule.subjectRef
  }

  /**
   * Does [rule] apply to the member looking at the feed? A done row resolves the subject for
   * everyone; a family mute is everyone's; a personal mute is its owner's alone.
   */
  fun appliesTo(rule: ContentResponse, viewerUserId: String?): Boolean =
    rule.kind == ResponseKind.DONE ||
      rule.audienceScope == AudienceScope.FAMILY ||
      (viewerUserId != null && rule.userId == viewerUserId)

  /** Drop every item a rule suppresses for this viewer. Pure; order-preserving. */
  fun suppress(
    items: List<NowItem>,
    rules: List<ContentResponse>,
    viewerUserId: String?,
  ): List<NowItem> {
    if (rules.isEmpty() || items.isEmpty()) return items
    val live = rules.filter { appliesTo(it, viewerUserId) }
    if (live.isEmpty()) return items
    return items.filterNot { item ->
      live.any { matches(it, item.subjectKey, item.reasonKind, item.authoredSource) }
    }
  }

  /** True when any applicable rule covers [subjectRef] — used to gate hub-block rendering. */
  fun isSuppressed(
    subjectRef: String,
    reasonKind: String,
    source: String?,
    rules: List<ContentResponse>,
    viewerUserId: String?,
  ): Boolean = rules.any { appliesTo(it, viewerUserId) && matches(it, subjectRef, reasonKind, source) }
}
