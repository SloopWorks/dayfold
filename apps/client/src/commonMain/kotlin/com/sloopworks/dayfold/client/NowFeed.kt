package com.sloopworks.dayfold.client

import kotlinx.datetime.TimeZone

// ADR 0043 — the MERGE. nowFeed runs both lanes through the ONE on-device Priority & Ordering
// Engine and returns the ranked, banded, budgeted feed. It is a pure render-time selector (clock +
// location injected, mirroring feedCards(state, nowIso)) — the reducer never sees a wall-clock, and
// the server never ranks Now. Derived items come from deriveNow over the synced cache; authored
// items are the active cards (expiry-filtered + not_before-gated) mapped onto the same NowItem.

fun nowFeed(
  state: AppState,
  nowIso: String,
  location: DeviceLocation?,
  zone: TimeZone = TimeZone.currentSystemDefault(),
  deriveConfig: DeriveConfig = DeriveConfig(),
  rankConfig: RankConfig = RankConfig(),
): RankedFeed {
  // Authored lane: reuse feedCards' shipped expiry filter + ordering, then gate not_before
  // on-device (today feedCards only ORDERS by not_before — this closes OQ-notbefore-gating for the
  // authored lane; the derived lane is gated by its rule windows).
  val authored = feedCards(state, nowIso)
    .filter { notBeforeReached(it.notBefore, nowIso, zone) }
    .map { cardToNowItem(it, rankConfig) }

  val derived = deriveNow(
    hubs = state.hubs,
    sections = state.nowContent.sections,
    blocks = state.nowContent.blocks,
    places = state.nowContent.places,
    nowIso = nowIso,
    location = location,
    zone = zone,
    config = deriveConfig,
  )

  return rank(derived + authored, nowIso, location, state.surfacing, zone, rankConfig)
}

// One active card → a NowItem in the authored lane. subjectKey uses the deep-link target (so an
// authored nudge collapses with the derived item about the same hub/block — the target earns its
// second job as the dedup key); a target-less card keys on its own id (never merges with derived).
internal fun cardToNowItem(card: Card, config: RankConfig): NowItem {
  val subjectKey = card.targetHubId?.let { hub ->
    buildString {
      append("hub:").append(hub)
      card.targetSectionId?.let { append("/sec:").append(it) }
      card.targetBlockId?.let { append("/blk:").append(it) }
    }
  } ?: "card:${card.id}"

  val reasonKind = when {
    card.kind == "weather" -> ReasonKind.WEATHER
    card.provenance?.source == "email" -> ReasonKind.EMAIL
    card.provenance?.source == "claude" -> ReasonKind.CLAUDE
    else -> ReasonKind.EXTERNAL
  }

  return NowItem(
    id = "authored:${card.id}",
    origin = Origin.AUTHORED,
    reasonKind = reasonKind,
    title = card.title,
    why = firstNonBlankLine(card.bodyMd) ?: card.title,
    subjectKey = subjectKey,
    target = card.targetHubId?.let { DeepLinkTarget(it, card.targetSectionId, card.targetBlockId) },
    // not_before is the authored item's "for" instant → drives banding (now/soon/later).
    triggerAtIso = card.notBefore,
    // importance is clamped to the engine cap here too (defense-in-depth; rank also clamps).
    weight = card.importance?.coerceIn(0.0, config.importanceCap) ?: DEFAULT_AUTHORED_WEIGHT,
    authoredSource = card.provenance?.source,
  )
}

private const val DEFAULT_AUTHORED_WEIGHT = 0.5

// An authored card is live once its not_before has passed (or it has none). Fail open — an
// unparseable not_before never hides the card (mirrors feedCards' fail-open expiry).
internal fun notBeforeReached(notBefore: String?, nowIso: String, zone: TimeZone): Boolean {
  if (notBefore == null) return true
  val nb = parseInstantFlexible(notBefore, zone) ?: return true
  val now = parseInstantFlexible(nowIso, zone) ?: return true
  return nb <= now
}

private fun firstNonBlankLine(s: String?): String? =
  s?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.removePrefix("#")?.trim()?.ifBlank { null }
