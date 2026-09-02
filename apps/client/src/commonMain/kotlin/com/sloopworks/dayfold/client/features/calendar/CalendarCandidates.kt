package com.sloopworks.dayfold.client

import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus

// ADR 0063 §2 — the calendar-candidate projection. deriveEventCandidates synthesizes
// DayfoldEventCandidates ON-DEVICE from already-synced TYPED content fields only: hub
// start_at/end_at, block/card `when.at` triggers, and explicitly dated milestone blocks.
// countdown_to is relevance metadata (drives Now surfacing, ADR 0043) — it never becomes a
// second candidate for a hub subject already covered by start_at. Mirrors deriveNow's shape
// (pure, zone injected, no wall-clock) but is a DIFFERENT projection: consumed by the device
// calendar reconciler, never persisted, never synced (ADR 0063 §3).
//
// Strict typed-field-only rule: this file never reads bodyMd/prose for a date, duration, or
// location — a candidate's title/location come only from typed fields (Hub.title, Card.title,
// block payload label/date/address/lat/lng). That is also why sourceVersion — a fingerprint
// over exactly those typed inputs — stays stable across an unrelated body-text edit and
// changes only when a typed input changes.

data class CandidateLocation(
  val label: String? = null,
  val address: String? = null,
  val lat: Double? = null,
  val lng: Double? = null,
)

/** The typed Dayfold row that produced a candidate. [subjectKey] may intentionally point at a
 * card's deep-link target for cross-lane dedup, so write-back must not infer source ownership from
 * that key. */
sealed interface CalendarCandidateSource {
  data class Hub(val id: String, val type: String?) : CalendarCandidateSource
  data class Block(val id: String) : CalendarCandidateSource
  data class Card(val id: String) : CalendarCandidateSource
}

data class DayfoldEventCandidate(
  // Compatibility name for the UI/action address. ADR 0067 values this with localFactKey.
  val subjectKey: String,
  val title: String,
  val startAt: String,
  val endAt: String?,
  val allDay: Boolean,
  val timezone: String,
  val location: CandidateLocation?,
  val sourceVersion: String,
  val deepLink: DeepLinkTarget?,
  val source: CalendarCandidateSource? = null,
  val entityRef: EntityRef = EntityRef(subjectKey),
  val factRef: FactRef = FactRef("legacy:when"),
  val subjectRef: String = subjectKey,
) {
  val localFactKey: String get() = localFactKey(entityRef, factRef)
}

/** Progressive "add only" handoff for a dated Hub. It does not require Calendar Check read access. */
fun Hub.calendarEventPrefill(zone: TimeZone = TimeZone.currentSystemDefault()): EventPrefill? {
  if (status == "archived") return null
  val fact = temporalFacts(this, zone).calendarEligible().singleOrNull { it.factRef.value == "hub:start" } ?: return null
  val extent = fact.extent as? TemporalExtent.Timed ?: return null
  return EventPrefill(
    title = title,
    startAt = extent.start.toString(),
    endAt = extent.endExclusive?.toString(),
    allDay = false,
    timezone = extent.zone.id,
    deepLink = DeepLinkTarget(id),
  )
}

fun deriveEventCandidates(
  hubs: List<Hub>,
  sections: List<HubSection>,
  blocks: List<HubBlock>,
  cards: List<Card>,
  zone: TimeZone = TimeZone.currentSystemDefault(),
): List<DayfoldEventCandidate> {
  val out = ArrayList<DayfoldEventCandidate>()
  val hubIdForSection = sections.associate { it.id to it.hubId }

  fun append(
    fact: NormalizedFact,
    subjectKey: String,
    location: CandidateLocation?,
    deepLink: DeepLinkTarget?,
    source: CalendarCandidateSource,
  ) {
    val (start, end, allDay, timezone) = when (val extent = fact.extent) {
      is TemporalExtent.AllDay -> listOf(extent.start.toString(), extent.endExclusive.toString(), "true", zone.id)
      is TemporalExtent.Timed -> listOf(extent.start.toString(), extent.endExclusive?.toString(), "false", extent.zone.id)
    }
    out += DayfoldEventCandidate(
      subjectKey = localFactKey(fact.entityRef, fact.factRef), entityRef = fact.entityRef, factRef = fact.factRef,
      subjectRef = subjectKey,
      title = fact.label, startAt = start!!, endAt = end, allDay = allDay == "true",
      timezone = timezone!!, location = location,
      sourceVersion = fingerprintFor(fact.label, start, end, location, fact.factRef.value, timezone),
      deepLink = deepLink, source = source,
    )
  }

  // ── 1. Hub start_at/end_at. countdown_to remains timeline/relevance-only. ──
  for (hub in hubs) {
    temporalFacts(hub, zone).calendarEligible().forEach { fact ->
      append(fact, SubjectRef.node(hub.id), null, DeepLinkTarget(hub.id), CalendarCandidateSource.Hub(hub.id, hub.type))
    }
  }

  for (block in blocks) {
    // A block whose hub can't be resolved (orphaned/no section) has nothing to bind a
    // subjectKey or deep-link to — mirrors deriveNow's hubIdForBlock(...) ?: continue.
    val hubId = block.sectionId?.let { hubIdForSection[it] } ?: continue
    val subjectKey = SubjectRef.node(hubId, block.sectionId, block.id)
    val target = DeepLinkTarget(hubId, block.sectionId, block.id)
    val payload = block.payload
    val location = locationFromPayload(payload)

    val canonical = temporalFacts(block, zone).calendarEligible()
    if (canonical.isNotEmpty()) {
      canonical.forEach { append(it, subjectKey, location, target, CalendarCandidateSource.Block(block.id)) }
    } else {
      // Compatibility only: legacy when.at is not a second fact beside canonical content.
      block.triggers.orEmpty().mapNotNull { it.whenTrigger?.at }.forEachIndexed { index, whenAt ->
        val ex = legacyExtent(whenAt, zone) ?: return@forEachIndexed
        append(
          NormalizedFact(EntityRef("block:${block.id}"), FactRef("legacy:when:$index"), payload?.label ?: "Reminder",
            TemporalRole.EVENT, TemporalStatus.CONFIRMED, ex, TemporalSource.LEGACY_WHEN,
            TemporalCapabilities(calendar = true)),
          subjectKey, location, target, CalendarCandidateSource.Block(block.id),
        )
      }
    }
  }

  // ── 4. A card's `when.at` time trigger. A card without a hub target has no node to
  // deep-link into (it keys its own subject via SubjectRef.card, mirroring subjectKeyFor). ──
  for (card in cards) {
    val location = locationFromPayload(card.payload?.geo)
    val target = card.targetHubId?.let { DeepLinkTarget(it, card.targetSectionId, card.targetBlockId) }
    val canonical = temporalFacts(card, zone).calendarEligible()
    if (canonical.isNotEmpty()) canonical.forEach {
      append(it, subjectKeyFor(card), location, target, CalendarCandidateSource.Card(card.id))
    } else card.triggers.orEmpty().mapNotNull { it.whenTrigger?.at }.forEachIndexed { index, whenAt ->
      val ex = legacyExtent(whenAt, zone) ?: return@forEachIndexed
      append(
        NormalizedFact(EntityRef("card:${card.id}"), FactRef("legacy:when:$index"), card.title,
          TemporalRole.EVENT, TemporalStatus.CONFIRMED, ex, TemporalSource.LEGACY_WHEN,
          TemporalCapabilities(calendar = true)),
        subjectKeyFor(card), location, target, CalendarCandidateSource.Card(card.id),
      )
    }
  }

  // Stable order: tests + downstream reconciler diffing need deterministic output
  // regardless of input list order.
  return out.sortedWith(compareBy({ it.entityRef.value }, { it.startAt }, { it.factRef.value }))
}

private val DATE_ONLY = Regex("""^\d{4}-\d{2}-\d{2}$""")

private fun isDateOnly(iso: String): Boolean = DATE_ONLY.matches(iso.trim())

private fun legacyExtent(value: String, zone: TimeZone): TemporalExtent? =
  if (isDateOnly(value)) runCatching {
    val start = kotlinx.datetime.LocalDate.parse(value)
    TemporalExtent.AllDay(start, start.plus(1, kotlinx.datetime.DateTimeUnit.DAY))
  }.getOrNull()
  else parseInstantFlexible(value, zone)?.let { TemporalExtent.Timed(it, null, zone) }

private fun locationFromPayload(p: BlockPayload?): CandidateLocation? {
  if (p == null || (p.address == null && p.lat == null && p.lng == null)) return null
  return CandidateLocation(label = p.label, address = p.address, lat = p.lat, lng = p.lng)
}

private fun locationFromPayload(geo: GeoPayload?): CandidateLocation? {
  if (geo == null || (geo.address == null && geo.lat == null && geo.lng == null)) return null
  return CandidateLocation(label = geo.label, address = geo.address, lat = geo.lat, lng = geo.lng)
}

// The typed inputs that feed a candidate's rendered fields (title/start/end/location) — exactly
// what sourceVersion must track. Deliberately excludes subjectKey/timezone/deepLink: those are
// structural/render context, not authored content whose drift the reconciler needs to detect.
private fun fingerprintFor(
  title: String, startAt: String, endAt: String?, location: CandidateLocation?,
  factRef: String? = null, timezone: String? = null,
): String = stableFingerprint(
  title, startAt, endAt, location?.label, location?.address, location?.lat?.toString(), location?.lng?.toString(),
  factRef, timezone,
)

// A small, deterministic, non-cryptographic 64-bit digest (FNV-1a) — stable across Kotlin/JVM
// versions, unlike hashCode(). No existing stable-digest util was found in :client to reuse.
private const val FNV_OFFSET_BASIS = -0x340d631b7bdddcdbL // 0xcbf29ce484222325 as a signed Long
private const val FNV_PRIME = 0x100000001b3L

internal fun stableFingerprint(vararg parts: String?): String {
  var hash = FNV_OFFSET_BASIS
  for (part in parts) {
    val s = part ?: "\u0000"
    for (ch in s) {
      hash = (hash xor ch.code.toLong()) * FNV_PRIME
    }
    hash = (hash xor 0x1FL) * FNV_PRIME // part separator, so ("ab","c") != ("a","bc")
  }
  return hash.toString(16)
}
