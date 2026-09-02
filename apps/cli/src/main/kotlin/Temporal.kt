package com.sloopworks.dayfold.cli

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

private val temporalJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
private val ulidPattern = Regex("^[0-9A-HJKMNP-TV-Z]{26}$")
private val civilPattern = Regex("^\\d{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12]\\d|3[01])$")
private val instantPattern = Regex(
  "^\\d{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12]\\d|3[01])T(?:[01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d" +
    "(?:Z|(?!-00:00)(?:[+-](?:0\\d|1[0-3]):[0-5]\\d|[+-]14:00))$",
)
private val alertPattern = Regex("^([+-])?P(?=\\d|T\\d)(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$")
private val roles = setOf("event", "deadline", "window", "reference")
private val statuses = setOf("confirmed", "tentative", "cancelled")
private val occurrenceKeys = setOf("id", "role", "label", "start", "end", "zone", "status")
private const val maxAlertSeconds = 30L * 24 * 60 * 60

internal data class TemporalProblem(val path: String, val code: String) {
  override fun toString(): String = "$path: $code"
}

private fun JsonObject.string(key: String): String? =
  (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun civil(value: String): LocalDate? = if (!civilPattern.matches(value)) null else
  runCatching { LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE.withResolverStyle(ResolverStyle.STRICT)) }.getOrNull()

private fun instant(value: String): OffsetDateTime? = if (!instantPattern.matches(value)) null else
  runCatching { OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME) }.getOrNull()

private fun zoneMatches(value: String, zoneName: String): Boolean {
  val parsed = instant(value) ?: return false
  val zone = runCatching { ZoneId.of(zoneName) }.getOrNull() ?: return false
  return zone.rules.getValidOffsets(parsed.toLocalDateTime()).contains(parsed.offset)
}

internal fun knownIanaZone(zoneName: String): Boolean =
  zoneName.length <= 128 && runCatching { ZoneId.of(zoneName) }.isSuccess

internal fun authoringInstantMatchesZone(value: String, zoneName: String): Boolean =
  knownIanaZone(zoneName) && zoneMatches(value, zoneName)

internal fun isAuthoringTimedValue(value: String): Boolean = instant(value) != null

internal fun isAuthoringCivilValue(value: String): Boolean = civil(value) != null

internal fun authoringRangeIsValid(start: String, end: String, zoneName: String?): Boolean {
  val startDate = civil(start)
  val endDate = civil(end)
  if (startDate != null || endDate != null)
    return startDate != null && endDate != null && zoneName == null && endDate.isAfter(startDate)
  val startInstant = instant(start) ?: return false
  val endInstant = instant(end) ?: return false
  return zoneName != null && authoringInstantMatchesZone(start, zoneName) &&
    authoringInstantMatchesZone(end, zoneName) && endInstant.toInstant().isAfter(startInstant.toInstant())
}

private fun alertSeconds(value: String): Long? {
  val match = alertPattern.matchEntire(value) ?: return null
  val values = (2..5).map { match.groupValues[it].ifEmpty { "0" }.toLongOrNull() ?: return null }
  val total = runCatching {
    Math.addExact(
      Math.addExact(Math.multiplyExact(values[0], 86400L), Math.multiplyExact(values[1], 3600L)),
      Math.addExact(Math.multiplyExact(values[2], 60L), values[3]),
    )
  }.getOrNull() ?: return null
  return if (match.groupValues[1] == "-") -total else total
}

private data class Fact(val start: String?, val status: String = "confirmed", val role: String = "event")

/** One deterministic local validator used by push, audit, apply, and tests. */
internal fun temporalProblems(resource: JsonObject): List<TemporalProblem> {
  val problems = mutableListOf<TemporalProblem>()
  val facet = resource["temporal"]
  val occurrences = when (facet) {
    null, JsonNull -> emptyList()
    is JsonObject -> (facet["occurrences"] as? JsonArray)?.mapIndexedNotNull { index, value ->
      (value as? JsonObject) ?: run {
        problems += TemporalProblem("/temporal/occurrences/$index", "temporal.occurrence-object-required")
        null
      }
    } ?: run {
      problems += TemporalProblem("/temporal/occurrences", "temporal.occurrences-required")
      emptyList()
    }
    else -> {
      problems += TemporalProblem("/temporal", "temporal.object-or-null-required")
      emptyList()
    }
  }
  if (occurrences.size !in 0..64 || (facet is JsonObject && occurrences.isEmpty()))
    problems += TemporalProblem("/temporal/occurrences", "temporal.occurrence-count")

  val facts = mutableMapOf<String, Fact>()
  occurrences.forEachIndexed { index, occurrence ->
    val path = "/temporal/occurrences/$index"
    occurrence.keys.filterNot(occurrenceKeys::contains).forEach {
      problems += TemporalProblem("$path/$it", "temporal.unknown-field")
    }
    val id = occurrence.string("id")
    if (id == null || !ulidPattern.matches(id)) problems += TemporalProblem("$path/id", "temporal.invalid-id")
    else if (facts.containsKey("temporal:$id")) problems += TemporalProblem("$path/id", "temporal.duplicate-id")
    val role = occurrence.string("role")
    if (role !in roles) problems += TemporalProblem("$path/role", "temporal.invalid-role")
    val status = occurrence.string("status")
    if (status !in statuses) problems += TemporalProblem("$path/status", "temporal.invalid-status")
    val label = occurrence.string("label")
    if (label == null || label.isEmpty() || label.length > 256)
      problems += TemporalProblem("$path/label", "temporal.invalid-label")
    val start = occurrence.string("start")
    val startDate = start?.let(::civil)
    val startInstant = start?.let(::instant)
    if (start == null || (startDate == null && startInstant == null))
      problems += TemporalProblem("$path/start", "temporal.invalid-start")
    val zone = occurrence.string("zone")
    if (startDate != null && occurrence["zone"] != null)
      problems += TemporalProblem("$path/zone", "temporal.all-day-zone-forbidden")
    if (startInstant != null) {
      if (zone == null) problems += TemporalProblem("$path/zone", "temporal.timed-zone-required")
      else if (zone.length > 128 || !zoneMatches(start, zone))
        problems += TemporalProblem("$path/zone", "temporal.zone-offset-mismatch")
    }
    val end = occurrence.string("end")
    if (end != null) {
      val endDate = civil(end)
      val endInstant = instant(end)
      when {
        startDate != null && (endDate == null || !endDate.isAfter(startDate)) ->
          problems += TemporalProblem("$path/end", "temporal.invalid-civil-end")
        startInstant != null && (endInstant == null || !endInstant.toInstant().isAfter(startInstant.toInstant())) ->
          problems += TemporalProblem("$path/end", "temporal.invalid-timed-end")
      }
      if (startInstant != null && zone != null && endInstant != null && !zoneMatches(end, zone))
        problems += TemporalProblem("$path/end", "temporal.end-zone-offset-mismatch")
    }
    if (role == "window" && end == null) problems += TemporalProblem("$path/end", "temporal.window-end-required")
    if (role == "deadline" && end != null) problems += TemporalProblem("$path/end", "temporal.deadline-end-forbidden")
    if (id != null) facts["temporal:$id"] = Fact(start, status ?: "", role ?: "")
  }

  val type = resource.string("type")
  val payload = resource["payload"] as? JsonObject
  if (type == "milestone") facts["payload:milestone"] = Fact(payload?.string("date"))
  if (type == "checklist") (payload?.get("items") as? JsonArray)?.forEach { value ->
    (value as? JsonObject)?.let { item -> item.string("id")?.let { facts["checklist:$it:due"] = Fact(item.string("due"), role = "deadline") } }
  }
  val typed = payload?.get(type ?: "") as? JsonObject
  if (type == "invite") {
    facts["payload:invite:start"] = Fact(typed?.string("startAt"))
    facts["payload:invite:rsvp"] = Fact(typed?.string("rsvpBy"), role = "deadline")
  }
  if (type == "link") facts["payload:link:closes"] = Fact(typed?.string("closesAt"), role = "deadline")
  if (type == "geo") facts["payload:geo:leave"] = Fact(typed?.string("leaveBy"), role = "deadline")

  val factTriggers = mutableListOf<Pair<Int, JsonObject>>()
  (resource["triggers"] as? JsonArray)?.forEachIndexed { index, value ->
    val whenObject = (value as? JsonObject)?.get("when") as? JsonObject ?: return@forEachIndexed
    if (whenObject.string("fact_ref") != null) factTriggers += index to whenObject
  }
  if (factTriggers.size > 1) problems += TemporalProblem("/triggers", "temporal.multiple-fact-triggers")
  factTriggers.forEach { (index, whenObject) ->
    val path = "/triggers/$index/when"
    if (whenObject.keys.any { it !in setOf("fact_ref", "alert_offset") })
      problems += TemporalProblem(path, "temporal.fact-trigger-mixed-fields")
    whenObject.string("alert_offset")?.let {
      val seconds = alertSeconds(it)
      if (seconds == null || kotlin.math.abs(seconds) > maxAlertSeconds)
        problems += TemporalProblem("$path/alert_offset", "temporal.invalid-alert-offset")
    }
    val ref = whenObject.string("fact_ref")!!
    val fact = facts[ref]
    if (fact == null) problems += TemporalProblem("$path/fact_ref", "temporal.dangling-fact-ref")
    else if (fact.status != "confirmed" || fact.role == "reference" || fact.start?.let(::instant) == null)
      problems += TemporalProblem("$path/fact_ref", "temporal.ineligible-fact-ref")
  }
  return problems.distinct()
}

internal fun temporalValidationErrors(json: String): List<String> {
  val resource = runCatching { temporalJson.parseToJsonElement(json).jsonObject }.getOrElse {
    return listOf("/: temporal.invalid-json")
  }
  return temporalProblems(resource).map(TemporalProblem::toString)
}

// Conservative local review net. Years alone, phone numbers, version strings, and
// bare numeric fragments deliberately do not match.
private val dateMentionPatterns = listOf(
  Regex("\\b(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun)(?:day)?\\b", RegexOption.IGNORE_CASE),
  Regex("\\b(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)[a-z]*\\s+\\d{1,2}(?:st|nd|rd|th)?\\b", RegexOption.IGNORE_CASE),
  Regex("\\b\\d{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12]\\d|3[01])\\b"),
  Regex("\\b(?:[01]?\\d|2[0-3]):[0-5]\\d(?:\\s*(?:am|pm))?\\b", RegexOption.IGNORE_CASE),
  Regex("\\b(?:1[0-2]|0?[1-9])(?:am|pm)\\b", RegexOption.IGNORE_CASE),
  Regex("\\b(?:today|tomorrow|tonight|yesterday)\\b", RegexOption.IGNORE_CASE),
  Regex("\\b(?:next|this|last)\\s+(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun)(?:day)?\\b", RegexOption.IGNORE_CASE),
  Regex("\\b(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)[a-z]*\\s+\\d{4}\\b", RegexOption.IGNORE_CASE),
)

internal fun containsTemporalMention(text: String): Boolean = dateMentionPatterns.any { it.containsMatchIn(text) }

/** Conservative coverage units: one review unit per prose line containing a date/time-like token. */
internal fun temporalMentionCount(text: String): Int =
  text.lineSequence().count(::containsTemporalMention)

internal fun jsonPointer(root: JsonElement, pointer: String): JsonElement? {
  if (pointer.isEmpty()) return root
  if (!pointer.startsWith('/')) return null
  return pointer.drop(1).split('/').fold(root as JsonElement?) { current, raw ->
    val token = raw.replace("~1", "/").replace("~0", "~")
    when (current) {
      is JsonObject -> current[token]
      is JsonArray -> token.toIntOrNull()?.let(current::getOrNull)
      else -> null
    }
  }
}
