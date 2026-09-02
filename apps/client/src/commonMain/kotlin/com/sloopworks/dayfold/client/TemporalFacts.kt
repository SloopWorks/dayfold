package com.sloopworks.dayfold.client

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** ADR 0067's lossless distinction: a civil interval is never converted to a midnight instant. */
sealed interface TemporalExtent {
  data class AllDay(val start: LocalDate, val endExclusive: LocalDate) : TemporalExtent
  data class Timed(val start: Instant, val endExclusive: Instant?, val zone: TimeZone) : TemporalExtent
}

enum class TemporalRole { EVENT, DEADLINE, WINDOW, REFERENCE, UNKNOWN }
enum class TemporalStatus { CONFIRMED, TENTATIVE, CANCELLED, UNKNOWN }
enum class TemporalSource { FACET, HUB_START, HUB_COUNTDOWN, MILESTONE, CHECKLIST_DUE, INVITE_START, INVITE_RSVP, LINK_CLOSE, GEO_LEAVE, LEGACY_WHEN }

data class TemporalCapabilities(
  val calendar: Boolean = false,
  val timeline: Boolean = false,
  val trigger: Boolean = false,
)

data class FactRef(val value: String)
data class EntityRef(val value: String)

data class NormalizedFact(
  val entityRef: EntityRef,
  val factRef: FactRef,
  val label: String,
  val role: TemporalRole,
  val status: TemporalStatus,
  val extent: TemporalExtent,
  val source: TemporalSource,
  val capabilities: TemporalCapabilities,
)

fun localFactKey(entityRef: EntityRef, factRef: FactRef): String =
  "${entityRef.value.length}:${entityRef.value}${factRef.value}"

fun parseLocalFactKey(value: String): Pair<EntityRef, FactRef>? {
  val colon = value.indexOf(':')
  val size = value.substring(0, colon.takeIf { it > 0 } ?: return null).toIntOrNull() ?: return null
  val start = colon + 1
  if (size < 1 || start + size >= value.length) return null
  return EntityRef(value.substring(start, start + size)) to FactRef(value.substring(start + size))
}

private val DATE_ONLY = Regex("""^\d{4}-\d{2}-\d{2}$""")
private val TIMED = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:Z|(?!-00:00)(?:[+-](?:0\d|1[0-3]):[0-5]\d|[+-]14:00))$""")
private val FACT_REF = Regex("""^(?:temporal:[0-9A-HJKMNP-TV-Z]{26}|payload:milestone|checklist:[0-9A-HJKMNP-TV-Z]{26}:due|payload:(?:invite:(?:start|rsvp)|link:closes|geo:leave))$""")
private val ELAPSED_OFFSET = Regex("""^[+-]?P(?=\d|T\d)(?:\d+D)?(?:T(?:\d+H)?(?:\d+M)?(?:\d+S)?)?$""")

fun isFactRef(value: String): Boolean = FACT_REF.matches(value)

private fun role(value: String?): TemporalRole = when (value) {
  "event" -> TemporalRole.EVENT
  "deadline" -> TemporalRole.DEADLINE
  "window" -> TemporalRole.WINDOW
  "reference" -> TemporalRole.REFERENCE
  else -> TemporalRole.UNKNOWN
}

private fun status(value: String?): TemporalStatus = when (value) {
  "confirmed" -> TemporalStatus.CONFIRMED
  "tentative" -> TemporalStatus.TENTATIVE
  "cancelled" -> TemporalStatus.CANCELLED
  else -> TemporalStatus.UNKNOWN
}

private fun extent(start: String, end: String?, zone: String?): TemporalExtent? {
  if (DATE_ONLY.matches(start)) {
    if (zone != null || (end != null && !DATE_ONLY.matches(end))) return null
    val s = runCatching { LocalDate.parse(start) }.getOrNull() ?: return null
    val e = end?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: s.plus(1, DateTimeUnit.DAY)
    return TemporalExtent.AllDay(s, e).takeIf { e > s }
  }
  if (!TIMED.matches(start) || zone == null || (end != null && !TIMED.matches(end))) return null
  val s = runCatching { Instant.parse(start) }.getOrNull() ?: return null
  val e = end?.let { runCatching { Instant.parse(it) }.getOrNull() ?: return null }
  if (e != null && e <= s) return null
  val tz = runCatching { TimeZone.of(zone) }.getOrNull() ?: return null
  return TemporalExtent.Timed(s, e, tz)
}

private fun typedExtent(start: String?, end: String?, zone: TimeZone): TemporalExtent? {
  start ?: return null
  if (DATE_ONLY.matches(start)) {
    val s = runCatching { LocalDate.parse(start) }.getOrNull() ?: return null
    val e = end?.takeIf(DATE_ONLY::matches)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
      ?: s.plus(1, DateTimeUnit.DAY)
    return TemporalExtent.AllDay(s, e).takeIf { e > s }
  }
  if (!TIMED.matches(start) || (end != null && !TIMED.matches(end))) return null
  val s = runCatching { Instant.parse(start) }.getOrNull() ?: return null
  val e = end?.let { runCatching { Instant.parse(it) }.getOrNull() ?: return null }
  return TemporalExtent.Timed(s, e, zone).takeIf { e == null || e > s }
}

private fun facetFacts(entity: EntityRef, temporal: JsonObject?): List<NormalizedFact> {
  val occurrences = temporal?.get("occurrences")?.let { runCatching { it.jsonArray }.getOrNull() }.orEmpty()
  val ids = occurrences.mapNotNull { raw ->
    runCatching { raw.jsonObject.string("id") }.getOrNull()
  }.groupingBy { it }.eachCount()
  val bounded = occurrences.size in 1..64
  return occurrences.mapNotNull { raw ->
    val o = runCatching { raw.jsonObject }.getOrNull() ?: return@mapNotNull null
    val id = o.string("id") ?: return@mapNotNull null
    val label = o.string("label") ?: return@mapNotNull null
    val r = role(o.string("role"))
    val s = status(o.string("status"))
    val ex = extent(o.string("start") ?: return@mapNotNull null, o.string("end"), o.string("zone")) ?: return@mapNotNull null
    val structurallyValid = bounded && FACT_REF.matches("temporal:$id") && ids[id] == 1 &&
      label.isNotEmpty() && label.length <= 256 &&
      !(r == TemporalRole.WINDOW && o.string("end") == null) &&
      !(r == TemporalRole.DEADLINE && o.string("end") != null)
    val confirmedMaterial = structurallyValid && s == TemporalStatus.CONFIRMED &&
      r in setOf(TemporalRole.EVENT, TemporalRole.DEADLINE, TemporalRole.WINDOW)
    NormalizedFact(
      entity, FactRef("temporal:$id"), label, r, s, ex, TemporalSource.FACET,
      TemporalCapabilities(
        calendar = confirmedMaterial,
        timeline = confirmedMaterial,
        trigger = confirmedMaterial && ex is TemporalExtent.Timed,
      ),
    )
  }
}

private fun JsonObject.string(key: String): String? =
  get(key)?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

fun temporalFacts(resource: Hub, zone: TimeZone = TimeZone.currentSystemDefault()): List<NormalizedFact> {
  val entity = EntityRef("hub:${resource.id}")
  val out = mutableListOf<NormalizedFact>()
  // Hub columns are timestamptz/date-time-only in V1. Do not manufacture an all-day Hub fact.
  typedExtent(resource.startAt, resource.endAt, zone)?.takeIf { it is TemporalExtent.Timed }?.let {
    out += NormalizedFact(entity, FactRef("hub:start"), resource.title, TemporalRole.EVENT,
      TemporalStatus.CONFIRMED, it, TemporalSource.HUB_START, TemporalCapabilities(calendar = true, timeline = true))
  }
  typedExtent(resource.endAt, null, zone)?.takeIf { it is TemporalExtent.Timed }?.let {
    out += NormalizedFact(entity, FactRef("hub:end"), "Ends", TemporalRole.DEADLINE,
      TemporalStatus.CONFIRMED, it, TemporalSource.HUB_START, TemporalCapabilities(timeline = true))
  }
  typedExtent(resource.countdownTo, null, zone)?.takeIf { it is TemporalExtent.Timed }?.let {
    out += NormalizedFact(entity, FactRef("hub:countdown"), resource.title, TemporalRole.DEADLINE,
      TemporalStatus.CONFIRMED, it, TemporalSource.HUB_COUNTDOWN, TemporalCapabilities(timeline = true))
  }
  return out
}

fun temporalFacts(resource: HubBlock, zone: TimeZone = TimeZone.currentSystemDefault()): List<NormalizedFact> {
  val entity = EntityRef("block:${resource.id}")
  val out = facetFacts(entity, resource.temporal).toMutableList()
  val payload = resource.payload
  if (resource.type == "milestone") {
    val milestoneZone = payload?.tz?.let { runCatching { TimeZone.of(it) }.getOrNull() } ?: zone
    typedExtent(payload?.date, payload?.end, milestoneZone)?.let {
      out += NormalizedFact(entity, FactRef("payload:milestone"), payload?.label ?: "Milestone",
        TemporalRole.EVENT, TemporalStatus.CONFIRMED, it, TemporalSource.MILESTONE,
        TemporalCapabilities(calendar = true, timeline = true, trigger = it is TemporalExtent.Timed))
    }
  }
  if (resource.type == "checklist") payload?.items.orEmpty().forEach { item ->
    val id = item.id ?: return@forEach
    val validRef = FACT_REF.matches("checklist:$id:due")
    typedExtent(item.due, null, zone)?.let {
      out += NormalizedFact(entity, FactRef(if (validRef) "checklist:$id:due" else "legacy:checklist:$id:due"), item.text ?: "To-do",
        TemporalRole.DEADLINE, TemporalStatus.CONFIRMED, it, TemporalSource.CHECKLIST_DUE,
        TemporalCapabilities(timeline = true, trigger = validRef && it is TemporalExtent.Timed))
    }
  }
  return out
}

fun temporalFacts(resource: Card, zone: TimeZone = TimeZone.currentSystemDefault()): List<NormalizedFact> {
  val entity = EntityRef("card:${resource.id}")
  val out = facetFacts(entity, resource.temporal).toMutableList()
  fun add(ref: String, at: String?, label: String, source: TemporalSource) {
    typedExtent(at, null, zone)?.let {
      out += NormalizedFact(entity, FactRef(ref), label, TemporalRole.EVENT, TemporalStatus.CONFIRMED,
        it, source, TemporalCapabilities(trigger = it is TemporalExtent.Timed))
    }
  }
  resource.payload?.invite?.let {
    add("payload:invite:start", it.startAt, it.eventName ?: resource.title, TemporalSource.INVITE_START)
    add("payload:invite:rsvp", it.rsvpBy, "RSVP: ${it.eventName ?: resource.title}", TemporalSource.INVITE_RSVP)
  }
  resource.payload?.link?.let { add("payload:link:closes", it.closesAt, it.title ?: resource.title, TemporalSource.LINK_CLOSE) }
  resource.payload?.geo?.let { add("payload:geo:leave", it.leaveBy, it.label ?: resource.title, TemporalSource.GEO_LEAVE) }
  return out
}

fun List<NormalizedFact>.calendarEligible(): List<NormalizedFact> = filter { it.capabilities.calendar }
fun List<NormalizedFact>.timelineEligible(): List<NormalizedFact> = filter { it.capabilities.timeline }

fun List<NormalizedFact>.forTrigger(ref: String?): NormalizedFact? {
  if (ref == null || !isFactRef(ref)) return null
  return singleOrNull { it.factRef.value == ref && it.capabilities.trigger && it.status == TemporalStatus.CONFIRMED }
}

/** Strict elapsed offset. Malformed or >30 days is ineligible; it is never treated as zero. */
fun factAlertOffset(value: String?): Duration? {
  if (value == null) return Duration.ZERO
  if (!ELAPSED_OFFSET.matches(value)) return null
  val parsed = runCatching { Duration.parseIsoString(value) }.getOrNull() ?: return null
  return parsed.takeIf { it >= (-30).days && it <= 30.days }
}

internal fun resolveWhenTrigger(
  facts: List<NormalizedFact>,
  trigger: TriggerWhen,
  fallbackZone: TimeZone,
): ResolvedWhenTrigger? {
  val ref = trigger.factRef
  if (ref != null) {
    if (trigger.raw?.keys?.any { it !in setOf("fact_ref", "alert_offset") } == true) return null
    if (trigger.at != null || trigger.relative != null || trigger.recurring != null) return null
    val fact = facts.forTrigger(ref) ?: return null
    val timed = fact.extent as? TemporalExtent.Timed ?: return null
    val offset = factAlertOffset(trigger.alertOffset) ?: return null
    return ResolvedWhenTrigger(timed.start, timed.start + offset, ref)
  }
  val at = parseInstantFlexible(trigger.at, fallbackZone) ?: return null
  val offset = factAlertOffset(trigger.alertOffset) ?: return null
  return ResolvedWhenTrigger(at, at + offset)
}
