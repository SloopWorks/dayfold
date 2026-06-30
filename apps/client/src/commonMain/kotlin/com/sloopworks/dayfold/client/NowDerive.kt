package com.sloopworks.dayfold.client

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime

// ADR 0043 Phase A — the DERIVED lane. `deriveNow` synthesizes ephemeral Now items ON-DEVICE
// from already-synced family content (hubs, blocks, places + block triggers) evaluated against
// the live clock + location. PURE: clock + location are injected (mirrors feedCards(state, nowIso)),
// no wall-clock, no randomness — snapshot/property-testable, fits the redux render-time selector.
// Output is a render projection: never persisted, never synced, no server involvement.

enum class Origin { DERIVED, AUTHORED }

// The five derived reason_kinds + authored provenances — the spec's lowercase token vocabulary
// (specs/now-content-model-design.md §2). Drives the "why" chip family.
object ReasonKind {
  const val COUNTDOWN = "countdown"
  const val MILESTONE = "milestone"
  const val CHECKLIST = "checklist"
  const val GEO = "geo"
  const val WHEN = "when"
  // authored provenances (Slice 6 maps Card.provenance.source onto these)
  const val WEATHER = "weather"
  const val EMAIL = "email"
  const val CLAUDE = "claude"
  const val EXTERNAL = "external"
}

// Live device position (foreground-only at Phase A; null = unknown / no permission). Injected into
// the pure deriver — it never leaves the device (ADR 0014). No expect/actual, no platform leak.
data class DeviceLocation(val lat: Double, val lng: Double)

// Where a Now item deep-links into the hub graph (ADR 0006). Derived items always start from a node.
data class DeepLinkTarget(val hubId: String, val sectionId: String? = null, val blockId: String? = null)

// One surfaced Now item — the single projection both lanes render through (origin only drives the
// "why" chip). Lean: scoring inputs are pre-resolved (`weight`, `geoActive`, `distanceM`) so the
// ranker never recomputes geometry or re-clamps importance.
data class NowItem(
  val id: String,                       // stable: "derived:<kind>:<node>" | "authored:<cardId>"
  val origin: Origin,
  val reasonKind: String,
  val title: String,
  val why: String,                      // computed human reason ("Party in 2 days", "You're near Safeway")
  val subjectKey: String,               // canonical hierarchical key for cross-lane dedup (prefix-merge)
  val target: DeepLinkTarget?,
  val triggerAtIso: String? = null,     // the instant this item is "for" (null for pure-geo)
  val weight: Double = 0.5,             // pre-resolved rule weight or clamped authored importance
  val geoActive: Boolean = false,       // computed once: within the geo radius right now
  val distanceM: Double? = null,        // computed once (meters)
  val authoredSource: String? = null,   // provenance for the authored chip (Slice 6)
)

data class DeriveConfig(
  val countdownWindowDays: Int = 14,    // surface a hub countdown within N days
  val milestoneWindowDays: Int = 7,     // surface a dated milestone block within N days
  val checklistWindowDays: Int = 7,     // surface "N left" only when the hub event is within N days
  val whenWindowMinutes: Long = 120,    // surface a time-window trigger within N minutes
  val geoRadiusDefaultM: Long = 200,    // fallback radius when neither trigger nor place sets one
  val countdownWeight: Double = 0.60,
  val milestoneWeight: Double = 0.55,
  val checklistWeight: Double = 0.50,
  val geoWeight: Double = 0.70,
  val whenWeight: Double = 0.65,
)

fun deriveNow(
  hubs: List<Hub>,
  sections: List<HubSection>,
  blocks: List<HubBlock>,
  places: List<Place>,
  nowIso: String,
  location: DeviceLocation?,
  zone: TimeZone = TimeZone.currentSystemDefault(),
  config: DeriveConfig = DeriveConfig(),
): List<NowItem> {
  val out = ArrayList<NowItem>()
  val hubById = hubs.associateBy { it.id }
  val hubIdForSection = sections.associate { it.id to it.hubId }
  val placeById = places.associateBy { it.id }

  fun hubIdForBlock(b: HubBlock): String? = b.sectionId?.let { hubIdForSection[it] }

  // ── 1. COUNTDOWN — a hub's event approaching (countdown_to ?: start_at) ──
  for (hub in hubs) {
    val targetIso = hub.countdownTo ?: hub.startAt ?: continue
    val days = relativeDays(targetIso, nowIso, zone) ?: continue
    if (days < 0 || days > config.countdownWindowDays) continue
    out += NowItem(
      id = "derived:countdown:${hub.id}",
      origin = Origin.DERIVED, reasonKind = ReasonKind.COUNTDOWN,
      title = hub.title, why = whyRelative(hub.title, days),
      subjectKey = "hub:${hub.id}",
      target = DeepLinkTarget(hub.id),
      triggerAtIso = targetIso, weight = config.countdownWeight,
    )
  }

  for (block in blocks) {
    val hubId = hubIdForBlock(block) ?: continue
    val subjectKey = "hub:$hubId/sec:${block.sectionId}/blk:${block.id}"
    val target = DeepLinkTarget(hubId, block.sectionId, block.id)

    // ── 2. MILESTONE — a dated milestone block approaching ──
    if (block.type == "milestone" && block.payload?.date != null) {
      val days = relativeDays(block.payload.date, nowIso, zone)
      if (days != null && days in 0..config.milestoneWindowDays) {
        val label = firstLine(block.bodyMd) ?: block.payload.label ?: "Milestone"
        out += NowItem(
          id = "derived:milestone:${block.id}",
          origin = Origin.DERIVED, reasonKind = ReasonKind.MILESTONE,
          title = label, why = whyRelative(label, days),
          subjectKey = subjectKey, target = target,
          triggerAtIso = block.payload.date, weight = config.milestoneWeight,
        )
      }
    }

    // ── 3. CHECKLIST-DUE — items left, only when the owning hub's event is near ──
    if (block.type == "checklist") {
      val remaining = block.payload?.items?.count { !it.done } ?: 0
      val hub = hubById[hubId]
      val eventIso = hub?.countdownTo ?: hub?.startAt
      val eventDays = eventIso?.let { relativeDays(it, nowIso, zone) }
      if (remaining > 0 && hub != null && eventDays != null && eventDays in 0..config.checklistWindowDays) {
        out += NowItem(
          id = "derived:checklist:${block.id}",
          origin = Origin.DERIVED, reasonKind = ReasonKind.CHECKLIST,
          title = hub.title, why = "$remaining left before ${hub.title}",
          subjectKey = subjectKey, target = target,
          triggerAtIso = eventIso, weight = config.checklistWeight,
        )
      }
    }

    // ── 4. GEO-PROXIMITY — a block trigger's geo within radius right now ──
    if (location != null) {
      val geo = block.triggers?.firstNotNullOfOrNull { it.geo }
      if (geo != null) {
        val place = geo.placeRef?.let { placeById[it] }
        val lat = geo.lat ?: place?.lat
        val lng = geo.lng ?: place?.lng
        if (lat != null && lng != null) {
          val radius = (geo.radiusM ?: place?.radiusM ?: config.geoRadiusDefaultM).toDouble()
          val dist = haversineMeters(location.lat, location.lng, lat, lng)
          if (dist <= radius) {
            val label = geo.label ?: place?.label ?: "this place"
            out += NowItem(
              id = "derived:geo:${block.id}",
              origin = Origin.DERIVED, reasonKind = ReasonKind.GEO,
              title = label, why = "You're near $label",
              subjectKey = subjectKey, target = target,
              triggerAtIso = null, weight = config.geoWeight,
              geoActive = true, distanceM = dist,
            )
          }
        }
      }
    }

    // ── 5. TIME-WINDOW (when) — a block trigger's `when.at` within the minute window ──
    val whenAt = block.triggers?.firstNotNullOfOrNull { it.whenTrigger?.at }
    if (whenAt != null) {
      val at = parseInstantFlexible(whenAt, zone)
      val nowInstant = parseInstantFlexible(nowIso, zone)
      if (at != null && nowInstant != null && at >= nowInstant && at <= nowInstant + config.whenWindowMinutes.minutes) {
        val label = firstLine(block.bodyMd) ?: "Reminder"
        out += NowItem(
          id = "derived:when:${block.id}",
          origin = Origin.DERIVED, reasonKind = ReasonKind.WHEN,
          title = label, why = "$label at ${clockTime(at, zone)}",
          subjectKey = subjectKey, target = target,
          triggerAtIso = whenAt, weight = config.whenWeight,
        )
      }
    }
  }

  // Stable order (the ranker re-orders; this keeps the pre-rank list deterministic for tests).
  return out.sortedBy { it.id }
}

// "$label today" | "$label tomorrow" | "$label in N days". Caller gates days in [0, window].
private fun whyRelative(label: String, days: Int): String = when (days) {
  0 -> "$label today"
  1 -> "$label tomorrow"
  else -> "$label in $days days"
}

// Calendar days from now to target (local dates in `zone`), or null if either is unparseable.
// Calendar-day math (not elapsed hours) so "tomorrow 6am" reads "tomorrow" even at 11pm tonight.
internal fun relativeDays(targetIso: String?, nowIso: String, zone: TimeZone): Int? {
  val target = localDate(targetIso, zone) ?: return null
  val now = localDate(nowIso, zone) ?: return null
  return now.daysUntil(target)
}

private fun localDate(iso: String?, zone: TimeZone): LocalDate? =
  parseInstantFlexible(iso, zone)?.toLocalDateTime(zone)?.date

// Flexible parse: a full RFC-3339 instant OR a date-only "YYYY-MM-DD" (start-of-day in `zone`).
// The common case (hub.countdown_to, milestone date, checklist due) is authored date-only and the
// plain Instant.parse path returns null for it — so the deriver would silently no-op without this.
internal fun parseInstantFlexible(s: String?, zone: TimeZone): Instant? {
  val normalized = normalizeTs(s) ?: return null
  runCatching { Instant.parse(normalized) }.getOrNull()?.let { return it }
  return runCatching { LocalDate.parse(s!!.trim()).atStartOfDayIn(zone) }.getOrNull()
}

// 12-hour clock time with no am/pm, e.g. 13:30 → "1:30", 15:00 → "3:00" (matches the mockup).
private fun clockTime(instant: Instant, zone: TimeZone): String {
  val t = instant.toLocalDateTime(zone).time
  val h12 = (t.hour % 12).let { if (it == 0) 12 else it }
  return "$h12:${t.minute.toString().padStart(2, '0')}"
}

private fun firstLine(s: String?): String? =
  s?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.removePrefix("#")?.trim()?.ifBlank { null }

// Great-circle distance in meters (pure kotlin.math; no platform dependency).
internal fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
  val r = 6_371_000.0
  val dLat = (lat2 - lat1).toRadians()
  val dLng = (lng2 - lng1).toRadians()
  val a = sin(dLat / 2) * sin(dLat / 2) +
    cos(lat1.toRadians()) * cos(lat2.toRadians()) * sin(dLng / 2) * sin(dLng / 2)
  return r * 2 * atan2(sqrt(a), sqrt(1 - a))
}

private fun Double.toRadians(): Double = this * 0.017453292519943295  // PI / 180
