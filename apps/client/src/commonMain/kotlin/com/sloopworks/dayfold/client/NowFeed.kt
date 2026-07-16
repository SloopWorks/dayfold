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
  // ADR 0049 Option A (#299): the FOREGROUND render allows any authored geo trigger to surface;
  // the BACKGROUND notify pass passes false → only place_ref-resolved geo surfaces (a coord-only
  // authored trigger never fires on a background wake — background geofencing stays user-curated).
  authoredCoordGeo: Boolean = true,
): RankedFeed {
  // Authored lane: reuse feedCards' shipped expiry filter + ordering, then gate not_before
  // on-device (today feedCards only ORDERS by not_before — this closes OQ-notbefore-gating for the
  // authored lane; the derived lane is gated by its rule windows).
  val visible = feedCards(state, nowIso).filter { notBeforeReached(it.notBefore, nowIso, zone) }
  val authored = visible.map { cardToNowItem(it, rankConfig, nowIso, zone) }
  // Authored geo lane (#299): a visible card's geo trigger, matched on-device against live location.
  val authoredGeo = authoredGeoItems(visible, state.now.content.places, location, deriveConfig, placeRefOnly = !authoredCoordGeo)

  val derived = deriveNow(
    hubs = state.hubs.hubs,
    sections = state.now.content.sections,
    blocks = state.now.content.blocks,
    places = state.now.content.places,
    nowIso = nowIso,
    location = location,
    zone = zone,
    config = deriveConfig,
  )

  return rank(derived + authored + authoredGeo, nowIso, location, state.now.surfacing, zone, rankConfig)
}

// Foreground authored-geo lane (ADR 0049 Option A, #299): a visible card whose geo trigger the user
// is within radius of surfaces as a NOW item, matched on-device (ADR 0014 — live location injected,
// never persisted). Reuses deriveNow's geo math (haversine, radius precedence, place_ref resolve).
// Distinct id from the time item ("authored:<id>") but the SAME subjectKey → the ranker dedups them.
// placeRefOnly=true (background pass) skips coord-only triggers so they never fire in the background.
internal fun authoredGeoItems(
  cards: List<Card>,
  places: List<Place>,
  location: DeviceLocation?,
  config: DeriveConfig,
  placeRefOnly: Boolean,
): List<NowItem> {
  if (location == null) return emptyList()
  val placeById = places.associateBy { it.id }
  val out = ArrayList<NowItem>()
  cards.forEach { card ->
    card.triggers?.forEachIndexed { idx, t ->
      val geo = t.geo ?: return@forEachIndexed
      val place = geo.placeRef?.let { placeById[it] }
      if (placeRefOnly && place == null) return@forEachIndexed   // ADR 0049 Option A: no coord-only geo in background
      val lat = geo.lat ?: place?.lat ?: return@forEachIndexed
      val lng = geo.lng ?: place?.lng ?: return@forEachIndexed
      val radius = (geo.radiusM ?: place?.radiusM ?: config.geoRadiusDefaultM).toDouble()
      val dist = haversineMeters(location.lat, location.lng, lat, lng)
      if (dist <= radius) {
        val label = geo.label ?: place?.label ?: "this place"
        out += NowItem(
          id = "authored:geo:${card.id}:$idx",
          origin = Origin.AUTHORED, reasonKind = ReasonKind.GEO,
          title = card.title, why = "You're near $label",
          subjectKey = subjectKeyFor(card),
          target = card.targetHubId?.let { DeepLinkTarget(it, card.targetSectionId, card.targetBlockId) },
          triggerAtIso = null, weight = config.geoWeight,
          geoActive = true, distanceM = dist,
          authoredSource = card.provenance?.source,
        )
      }
    }
  }
  return out
}

// ADR 0043 §2b — the subjects ACTUALLY surfaced to the user right now: the prominent bands
// (now/soon/later) heads plus the dedup peers rendered inset under each head. Overflow is excluded
// — it stays collapsed behind "More" until the user expands it, so it has not been "shown" for
// anti-nag purposes. Drives the render-shown effect that starts each subject's decay clock.
fun RankedFeed.visibleSubjectKeys(): Set<String> =
  (now + soon + later).flatMapTo(mutableSetOf()) { ranked ->
    listOf(ranked.item.subjectKey) + ranked.collapsedWith.map { it.subjectKey }
  }

// One active card → a NowItem in the authored lane. subjectKey uses the deep-link target (so an
// authored nudge collapses with the derived item about the same hub/block — the target earns its
// second job as the dedup key); a target-less card keys on its own id (never merges with derived).
// public: consumed cross-module by :ui (NowFeedScreenTest)
// subjectKey for an authored card: the deep-link target (so an authored nudge collapses with the
// derived item about the same hub/block) else the card's own id. Shared by cardToNowItem +
// authoredGeoItems so a card's time item and geo item dedup onto ONE subject (#299).
internal fun subjectKeyFor(card: Card): String =
  card.targetHubId?.let { hub ->
    buildString {
      append("hub:").append(hub)
      card.targetSectionId?.let { append("/sec:").append(it) }
      card.targetBlockId?.let { append("/blk:").append(it) }
    }
  } ?: "card:${card.id}"

fun cardToNowItem(card: Card, config: RankConfig, nowIso: String, zone: TimeZone): NowItem {
  val reasonKind = when {
    card.kind == "weather" -> ReasonKind.WEATHER
    card.provenance?.source == "email" -> ReasonKind.EMAIL
    card.provenance?.source == "claude" -> ReasonKind.CLAUDE
    else -> ReasonKind.EXTERNAL
  }

  // Relevance anchor (#299): soonest FUTURE when-trigger with alert_offset folded in, else
  // not_before (unchanged for trigger-less cards). A past-only trigger set → no future → not_before.
  val now = parseInstantFlexible(nowIso, zone)
  val whenAnchor: String? = card.triggers
    ?.mapNotNull { t -> t.whenTrigger?.let { applyOffset(it.at, it.alertOffset, zone) } }
    ?.filter { now == null || it > now }
    ?.minOrNull()
    ?.toString()

  return NowItem(
    id = "authored:${card.id}",
    origin = Origin.AUTHORED,
    reasonKind = reasonKind,
    title = card.title,
    why = firstNonBlankLine(card.bodyMd) ?: card.title,
    subjectKey = subjectKeyFor(card),
    target = card.targetHubId?.let { DeepLinkTarget(it, card.targetSectionId, card.targetBlockId) },
    // when-trigger anchor (offset-folded) drives banding + exact-schedule wake; else not_before.
    triggerAtIso = whenAnchor ?: card.notBefore,
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
