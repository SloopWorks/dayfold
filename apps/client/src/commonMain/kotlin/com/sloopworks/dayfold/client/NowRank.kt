package com.sloopworks.dayfold.client

import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

// ADR 0043 §2b — the on-device Priority & Ordering Engine. ONE pure function ranks the merged
// candidate set (derive(...) ∪ activeAuthored) and decides what surfaces, in what order, and how
// many. On-device by necessity (geo + live clock + local quiet/decay state can't go server-side);
// the server NEVER ranks Now. Pipeline: score → dedup/collapse → calm budget → stable order +
// hysteresis. Pure: clock + location + surfacing-state injected; deterministic, snapshot-testable.

enum class Band { NOW, SOON, LATER }

// LOCAL-ONLY, NEVER-synced surfacing state (ADR 0043 §2b.3) — last-shown drives anti-nag decay;
// dismissed removes a subject. Syncing this would be a who-saw-what behavioral leak (cf. ADR 0039
// hide-state). Keyed by the canonical subjectKey. Quiet-hours config is deferred to Phase B (it
// gates notifications, not in-feed ordering).
data class SurfacingRecord(
  val subjectKey: String,
  val lastShownAtIso: String? = null,
  val dismissedAtIso: String? = null,
)

data class RankedItem(
  val item: NowItem,
  val band: Band,
  val score: Double,
  val softened: Boolean = false,            // shown-but-not-acted → de-emphasized ("easing off")
  val emphasized: Boolean = false,          // geo-active nearest-N → ring + pulse
  val collapsedWith: List<NowItem> = emptyList(),  // dedup peers merged under this head
)

// now/soon/later horizon + an overflow tail (collapsed, NEVER silently dropped). caughtUp = nothing
// needs attention right now (NOW empty) — the calm headline; the horizon still shows below it.
data class RankedFeed(
  val now: List<RankedItem> = emptyList(),
  val soon: List<RankedItem> = emptyList(),
  val later: List<RankedItem> = emptyList(),
  val overflow: List<RankedItem> = emptyList(),
  val caughtUp: Boolean = true,
)

data class RankConfig(
  val nowBandMinutes: Long = 180,           // triggerAt within 3h (or geo-active) → NOW
  val soonBandHours: Long = 48,             // within 48h → SOON; else LATER
  val visibleBudget: Int = 6,               // soonest/nearest-N shown prominently; rest → overflow
  val nearestGeoBudget: Int = 3,            // geo nearest-N get the emphasized treatment
  val decayHalfLifeHours: Double = 24.0,    // anti-nag: a shown-not-acted item's score halves per N h
  val softenAfterHours: Double = 12.0,      // when an un-acted item visibly softens
  val hysteresisEpsilon: Double = 0.05,     // score-snap grid → no per-tick reorder jitter
  val importanceCap: Double = 0.80,         // authored importance clamp (no author-pinned spam)
  val wUrgency: Double = 0.45,
  val wProximity: Double = 0.30,
  val wImportance: Double = 0.25,
  val geoProximityRangeM: Double = 1000.0,  // proximity falls from 1.0 (at the point) to 0 at this range
)

fun rank(
  candidates: List<NowItem>,
  nowIso: String,
  location: DeviceLocation?,
  surfacing: Map<String, SurfacingRecord>,
  zone: TimeZone = TimeZone.currentSystemDefault(),
  config: RankConfig = RankConfig(),
): RankedFeed {
  val nowInstant = parseInstantFlexible(nowIso, zone)

  // 0. dismissed subjects are omitted entirely (a dismissal is a per-subject local signal).
  val live = candidates.filter { surfacing[it.subjectKey]?.dismissedAtIso == null }
  if (live.isEmpty()) return RankedFeed()

  // geo nearest-N: only the N nearest geo-active items earn the emphasized treatment.
  val nearestGeoIds = live.filter { it.geoActive }
    .sortedBy { it.distanceM ?: Double.MAX_VALUE }
    .take(config.nearestGeoBudget).map { it.id }.toSet()

  fun minutesUntil(iso: String?): Double? {
    val t = parseInstantFlexible(iso, zone) ?: return null
    val n = nowInstant ?: return null
    return (t - n).inWholeSeconds / 60.0
  }

  fun scoreOf(it: NowItem): Double {
    val mins = minutesUntil(it.triggerAtIso)
    val urgency = when {
      mins == null -> 0.0                                   // pure-geo: urgency comes from proximity
      mins >= 0 -> 1.0 / (1.0 + (mins / 60.0) / 12.0)       // 0h→1, 12h→0.5, 48h→0.2
      else -> (1.0 + (mins / 60.0) / 12.0).coerceAtLeast(0.0)  // decays over 12h after the moment
    }
    val proximity = if (it.geoActive)
      (1.0 - ((it.distanceM ?: 0.0) / config.geoProximityRangeM)).coerceIn(0.0, 1.0) else 0.0
    val importance = it.weight.coerceIn(0.0, config.importanceCap)   // clamp: no author top-pin
    val base = config.wUrgency * urgency + config.wProximity * proximity + config.wImportance * importance
    return base * (1.0 - 0.5 * decayFraction(it.subjectKey, surfacing, nowInstant, zone, config))
  }

  // 1. score every candidate.
  val scored = live.associateWith { scoreOf(it) }

  // 2. dedup/collapse — group by EXACT subjectKey. A derived event and its authored nudge collapse
  //    because both resolve to the same canonical key (the authored item's deep-link target →
  //    "hub:H", the derived countdown → "hub:H"). Distinct items keep distinct keys and stay
  //    separate — prefix-containment was rejected: a hub-level countdown is a prefix of EVERY block
  //    under the hub, so it transitively collapsed all of them into one mega-row.
  val clusters = live.groupBy { it.subjectKey }.values

  val rankedItems = clusters.map { cluster ->
    val head = cluster.maxWith(compareBy({ scored.getValue(it) }, { it.id }))
    val peers = cluster.filter { it.id != head.id }.sortedWith(compareByDescending<NowItem> { scored.getValue(it) }.thenBy { it.id })
    val emphasized = cluster.any { it.geoActive && it.id in nearestGeoIds }   // nearest-N → the ring
    // Banding uses FUTURE proximity only — a past trigger (e.g. an authored item whose not_before
    // fired hours ago) is calm horizon (LATER), never pinned to NOW. Geo-active = physically
    // on-location now → always NOW. Calendar "today" (date-only triggers resolve to midnight, which
    // is "past" by afternoon) still counts as NOW via the day check.
    val geoNow = cluster.any { it.geoActive }
    val futureMins = cluster.mapNotNull { minutesUntil(it.triggerAtIso) }.filter { it >= 0 }.minOrNull()
    val futureDays = cluster.mapNotNull { relativeDays(it.triggerAtIso, nowIso, zone) }.filter { it >= 0 }.minOrNull()
    val band = when {
      geoNow -> Band.NOW
      futureMins != null && futureMins <= config.nowBandMinutes -> Band.NOW
      futureDays == 0 -> Band.NOW
      futureDays != null && futureDays * 24 <= config.soonBandHours -> Band.SOON
      else -> Band.LATER
    }
    val softened = cluster.any { isSoftened(it.subjectKey, surfacing, nowInstant, zone, config) }
    RankedItem(head, band, scored.getValue(head), softened, emphasized, peers)
  }

  // 3. stable order + hysteresis. Snap score to the epsilon grid so sub-epsilon ticks can't reorder;
  //    total tiebreak by (band, −snappedScore, soonest-trigger, id) → deterministic, jitter-free.
  fun snap(s: Double): Int = (s / config.hysteresisEpsilon).roundToInt()
  val ordered = rankedItems.sortedWith(
    compareBy<RankedItem> { it.band.ordinal }
      .thenByDescending { snap(it.score) }
      .thenBy { minutesUntil(it.item.triggerAtIso) ?: Double.MAX_VALUE }
      .thenBy { it.item.id }
  )

  // 4. calm budget — the soonest/nearest N surface prominently; the tail collapses into overflow
  //    (NEVER dropped). caughtUp when nothing is in NOW.
  val visible = ordered.take(config.visibleBudget)
  val overflow = ordered.drop(config.visibleBudget)
  return RankedFeed(
    now = visible.filter { it.band == Band.NOW },
    soon = visible.filter { it.band == Band.SOON },
    later = visible.filter { it.band == Band.LATER },
    overflow = overflow,
    caughtUp = visible.none { it.band == Band.NOW },
  )
}

// Union items into clusters where any pair has one subjectKey a prefix of the other (transitive).
// n is small (a calm feed); the O(n²) pairing is fine and keeps the rule obvious.
private fun clusterByPrefix(items: List<NowItem>): List<List<NowItem>> {
  val parent = IntArray(items.size) { it }
  fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; return r }
  fun union(a: Int, b: Int) { parent[find(a)] = find(b) }
  for (i in items.indices) for (j in i + 1 until items.size) {
    if (isPrefixSubject(items[i].subjectKey, items[j].subjectKey)) union(i, j)
  }
  return items.indices.groupBy { find(it) }.values.map { idxs -> idxs.map { items[it] } }
}

// a contains b (hierarchically) if a == b or b starts with "a/". The "/" guard prevents
// "hub:h1" from matching "hub:h10".
private fun isPrefixSubject(a: String, b: String): Boolean =
  a == b || b.startsWith("$a/") || a.startsWith("$b/")

private fun isSoftened(subjectKey: String, surfacing: Map<String, SurfacingRecord>, now: Instant?, zone: TimeZone, config: RankConfig): Boolean {
  val rec = surfacing[subjectKey] ?: return false
  if (rec.dismissedAtIso != null) return false
  val shown = rec.lastShownAtIso?.let { parseInstantFlexible(it, zone) } ?: return false
  val n = now ?: return false
  return (n - shown).inWholeMinutes / 60.0 > config.softenAfterHours
}

private fun decayFraction(subjectKey: String, surfacing: Map<String, SurfacingRecord>, now: Instant?, zone: TimeZone, config: RankConfig): Double {
  val rec = surfacing[subjectKey] ?: return 0.0
  if (rec.dismissedAtIso != null) return 0.0
  val shown = rec.lastShownAtIso?.let { parseInstantFlexible(it, zone) } ?: return 0.0
  val n = now ?: return 0.0
  val hours = (n - shown).inWholeMinutes / 60.0
  if (hours <= 0) return 0.0
  return 1.0 - 0.5.pow(hours / config.decayHalfLifeHours)   // grows 0 → 1 with time un-acted
}
