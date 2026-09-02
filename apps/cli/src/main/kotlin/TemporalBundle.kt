package com.sloopworks.dayfold.cli

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

internal const val TEMPORAL_BUNDLE_SCHEMA = "dayfold.temporal-bundle.v1"
internal const val MAX_TEMPORAL_BUNDLE_BYTES = 4 * 1024 * 1024
private const val MAX_BUNDLE_RESOURCES = 256
private const val MAX_LEDGER_ROWS = 1024
private val bundleJson = Json { ignoreUnknownKeys = false; explicitNulls = true }

@Serializable
internal data class BundleAcl(
  val visibility: String,
  val audience: List<String> = emptyList(),
)

@Serializable
internal data class TemporalClaim(
  @SerialName("claim_id") val claimId: String,
  @SerialName("source_ref") val sourceRef: String,
  val classification: String,
  val normalized: JsonElement? = null,
  val zone: String? = null,
  val certainty: String,
  @SerialName("carrier_path") val carrierPath: String? = null,
  val behavior: Boolean = false,
  val disposition: String? = null,
  val relative: Boolean = false,
  @SerialName("source_base_instant") val sourceBaseInstant: String? = null,
  @SerialName("source_base_zone") val sourceBaseZone: String? = null,
)

@Serializable
internal data class TemporalBundleResource(
  val kind: String,
  val id: String,
  @SerialName("hub_id") val hubId: String? = null,
  @SerialName("base_version") val baseVersion: Long? = null,
  val acl: BundleAcl,
  val content: JsonObject,
  val ledger: List<TemporalClaim>,
)

@Serializable
internal data class TemporalBundle(
  val schema: String,
  val resources: List<TemporalBundleResource>,
)

internal data class ContentIssue(val resource: String, val path: String, val code: String) {
  fun render(): String = "$resource $path $code"
}

internal data class ContentCommandResult(
  val exitCode: Int,
  val stdout: String = "",
  val stderr: String = "",
)

internal fun interface ContentGet { fun call(path: String): Pair<Int, String> }
internal fun interface ContentPut { fun call(path: String, body: String, headers: Map<String, String>): Pair<Int, String> }
internal data class ContentNetwork(val get: ContentGet, val put: ContentPut)

private val classifications = setOf("event", "deadline", "window", "reference", "source_metadata", "lifecycle")
private val certainties = setOf("confirmed", "tentative", "cancelled")
private val dispositions = setOf("source_metadata", "lifecycle", "non_material_incidental", "false_positive")

private fun pointerToken(value: String): String = value.replace("~", "~0").replace("/", "~1")
private fun JsonObject.bundleString(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

private fun mappedClaimValues(content: JsonObject, claim: TemporalClaim): List<Pair<String, JsonElement>>? {
  val carrier = claim.carrierPath ?: return null
  val normalized = claim.normalized ?: return null
  val actual = jsonPointer(content, carrier) ?: return null
  if (normalized is JsonObject && actual is JsonObject) {
    if (normalized.isEmpty() || normalized.any { (key, value) -> actual[key] != value }) return null
    return normalized.map { (key, value) -> "$carrier/${pointerToken(key)}" to value }
  }
  return listOf(carrier to normalized).takeIf { actual == normalized }
}

private fun operationalCarrierPaths(content: JsonObject): Set<String> = buildSet {
  val occurrences = ((content["temporal"] as? JsonObject)?.get("occurrences") as? JsonArray).orEmpty()
  occurrences.forEachIndexed { index, value ->
    val occurrence = value as? JsonObject ?: return@forEachIndexed
    if (occurrence["start"] != null) add("/temporal/occurrences/$index/start")
    if (occurrence["end"] != null) add("/temporal/occurrences/$index/end")
  }
  val type = (content["type"] as? JsonPrimitive)?.contentOrNull
  val payload = content["payload"] as? JsonObject
  if (type == "milestone") {
    if (payload?.get("date") != null) add("/payload/date")
    if (payload?.get("end") != null) add("/payload/end")
  }
  if (type == "checklist") (payload?.get("items") as? JsonArray).orEmpty().forEachIndexed { index, value ->
    if ((value as? JsonObject)?.get("due") != null) add("/payload/items/$index/due")
  }
  val typed = payload?.get(type ?: "") as? JsonObject
  when (type) {
    "invite" -> {
      if (typed?.get("startAt") != null) add("/payload/invite/startAt")
      if (typed?.get("rsvpBy") != null) add("/payload/invite/rsvpBy")
    }
    "link" -> if (typed?.get("closesAt") != null) add("/payload/link/closesAt")
    "geo" -> if (typed?.get("leaveBy") != null) add("/payload/geo/leaveBy")
  }
  for (field in listOf("start_at", "end_at", "countdown_to")) if (content[field] != null) add("/$field")
}

private fun operationalRangePaths(content: JsonObject): List<Set<String>> = buildList {
  val occurrences = ((content["temporal"] as? JsonObject)?.get("occurrences") as? JsonArray).orEmpty()
  occurrences.forEachIndexed { index, value ->
    val occurrence = value as? JsonObject ?: return@forEachIndexed
    if (occurrence["start"] != null && occurrence["end"] != null)
      add(setOf("/temporal/occurrences/$index/start", "/temporal/occurrences/$index/end"))
  }
  val type = (content["type"] as? JsonPrimitive)?.contentOrNull
  val payload = content["payload"] as? JsonObject
  if (type == "milestone" && payload?.get("date") != null && payload["end"] != null)
    add(setOf("/payload/date", "/payload/end"))
  if (content["start_at"] != null && content["end_at"] != null)
    add(setOf("/start_at", "/end_at"))
}

private val occurrenceCarrier = Regex("^/temporal/occurrences/(\\d+)(?:/.*)?$")

private fun occurrenceAtCarrier(content: JsonObject, carrier: String): JsonObject? {
  val index = occurrenceCarrier.matchEntire(carrier)?.groupValues?.get(1)?.toIntOrNull() ?: return null
  return ((((content["temporal"] as? JsonObject)?.get("occurrences") as? JsonArray)?.getOrNull(index)) as? JsonObject)
}

private fun factRefCarrierPath(content: JsonObject, ref: String): String? = when {
  ref.startsWith("temporal:") -> {
    val id = ref.removePrefix("temporal:")
    val occurrences = ((content["temporal"] as? JsonObject)?.get("occurrences") as? JsonArray).orEmpty()
    occurrences.indexOfFirst { (it as? JsonObject)?.bundleString("id") == id }
      .takeIf { it >= 0 }?.let { "/temporal/occurrences/$it/start" }
  }
  ref == "payload:milestone" -> "/payload/date"
  ref.startsWith("checklist:") -> {
    val id = ref.removePrefix("checklist:").removeSuffix(":due")
    val items = ((content["payload"] as? JsonObject)?.get("items") as? JsonArray).orEmpty()
    items.indexOfFirst { (it as? JsonObject)?.bundleString("id") == id }
      .takeIf { it >= 0 }?.let { "/payload/items/$it/due" }
  }
  ref == "payload:invite:start" -> "/payload/invite/startAt"
  ref == "payload:invite:rsvp" -> "/payload/invite/rsvpBy"
  ref == "payload:link:closes" -> "/payload/link/closesAt"
  ref == "payload:geo:leave" -> "/payload/geo/leaveBy"
  else -> null
}

internal fun decodeTemporalBundle(raw: String): Pair<TemporalBundle?, List<ContentIssue>> {
  if (raw.toByteArray().size > MAX_TEMPORAL_BUNDLE_BYTES)
    return null to listOf(ContentIssue("bundle", "/", "bundle.too-large"))
  return try {
    bundleJson.decodeFromString<TemporalBundle>(raw) to emptyList()
  } catch (_: SerializationException) {
    null to listOf(ContentIssue("bundle", "/", "bundle.invalid-json"))
  } catch (_: IllegalArgumentException) {
    null to listOf(ContentIssue("bundle", "/", "bundle.invalid-json"))
  }
}

private fun stringFields(element: JsonElement, path: String = ""): Sequence<Pair<String, String>> = sequence {
  when (element) {
    is JsonObject -> element.forEach { (key, value) -> yieldAll(stringFields(value, "$path/$key")) }
    is JsonArray -> element.forEachIndexed { index, value -> yieldAll(stringFields(value, "$path/$index")) }
    is JsonPrimitive -> if (element.isString) yield(path to element.content)
  }
}

private fun reviewableText(path: String): Boolean =
  !path.startsWith("/temporal/") && !path.startsWith("/triggers/") &&
    !path.startsWith("/provenance/") && path !in setOf(
      "/created_at", "/updated_at", "/start_at", "/end_at", "/countdown_to",
      "/payload/email/date", "/payload/email/bodyExcerpt", "/payload/file/modified",
    ) && !path.endsWith("/due") && !path.endsWith("/date") && !path.endsWith("/end") &&
    !path.endsWith("/startAt") && !path.endsWith("/rsvpBy") && !path.endsWith("/closesAt") &&
    !path.endsWith("/leaveBy")

private fun proseTemporalMentionCount(content: JsonObject): Int = stringFields(content)
  .filter { (path, _) -> reviewableText(path) }
  .sumOf { (_, text) -> temporalMentionCount(text) }

private fun structuredTemporalValueCount(content: JsonObject): Int {
  var count = 0
  val occurrences = ((content["temporal"] as? JsonObject)?.get("occurrences") as? JsonArray).orEmpty()
  occurrences.forEach { value ->
    val occurrence = value as? JsonObject ?: return@forEach
    if (occurrence["start"] != null) count++
    if (occurrence["end"] != null) count++
  }
  for (field in listOf("start_at", "end_at", "countdown_to")) if (content[field] != null) count++
  val type = (content["type"] as? JsonPrimitive)?.contentOrNull
  val payload = content["payload"] as? JsonObject
  if (type == "milestone") {
    if (payload?.get("date") != null) count++
    if (payload?.get("end") != null) count++
  }
  if (type == "checklist") (payload?.get("items") as? JsonArray).orEmpty().forEach {
    if ((it as? JsonObject)?.get("due") != null) count++
  }
  val typed = payload?.get(type ?: "") as? JsonObject
  when (type) {
    "invite" -> listOf("startAt", "rsvpBy").forEach { if (typed?.get(it) != null) count++ }
    "link" -> if (typed?.get("closesAt") != null) count++
    "geo" -> if (typed?.get("leaveBy") != null) count++
  }
  if (count == 0) (content["triggers"] as? JsonArray).orEmpty().forEach { trigger ->
    if ((((trigger as? JsonObject)?.get("when") as? JsonObject)?.get("at") as? JsonPrimitive)?.isString == true) count++
  }
  return count
}

private fun ledgerCoverageUnits(claim: TemporalClaim): Int {
  if (claim.classification !in setOf("event", "deadline", "window", "reference") &&
    claim.disposition !in setOf("non_material_incidental", "false_positive")) return 0
  val normalized = claim.normalized as? JsonObject
  return normalized?.keys?.count { it == "start" || it == "end" }?.takeIf { it > 0 } ?: 1
}

private fun normalizedStrings(value: JsonElement?): List<String> = when (value) {
  is JsonPrimitive -> value.contentOrNull?.let(::listOf).orEmpty()
  is JsonObject -> listOfNotNull(
    (value["start"] as? JsonPrimitive)?.contentOrNull,
    (value["end"] as? JsonPrimitive)?.contentOrNull,
  )
  else -> emptyList()
}

internal fun validateTemporalBundle(bundle: TemporalBundle): List<ContentIssue> {
  val issues = mutableListOf<ContentIssue>()
  if (bundle.schema != TEMPORAL_BUNDLE_SCHEMA) issues += ContentIssue("bundle", "/schema", "bundle.unsupported-version")
  if (bundle.resources.isEmpty() || bundle.resources.size > MAX_BUNDLE_RESOURCES)
    issues += ContentIssue("bundle", "/resources", "bundle.resource-count")
  val keys = mutableSetOf<String>()
  bundle.resources.forEach { resource ->
    val key = "${resource.kind}:${resource.id}"
    if (!keys.add(key)) issues += ContentIssue(key, "/id", "bundle.duplicate-resource")
    if (resource.kind !in setOf("card", "block", "section", "hub")) issues += ContentIssue(key, "/kind", "bundle.invalid-kind")
    if (resource.kind in setOf("block", "section") && resource.hubId.isNullOrBlank()) issues += ContentIssue(key, "/hub_id", "bundle.hub-id-required")
    if (resource.acl.visibility !in setOf("family", "restricted")) issues += ContentIssue(key, "/acl/visibility", "bundle.invalid-visibility")
    if (resource.acl.visibility == "family" && resource.acl.audience.isNotEmpty())
      issues += ContentIssue(key, "/acl/audience", "bundle.family-audience-forbidden")
    if (resource.acl.audience.size != resource.acl.audience.distinct().size)
      issues += ContentIssue(key, "/acl/audience", "bundle.duplicate-audience")
    if (resource.ledger.size > MAX_LEDGER_ROWS) issues += ContentIssue(key, "/ledger", "bundle.ledger-count")
    if ((resource.kind == "card" || resource.kind == "block") && !resource.content.containsKey("temporal"))
      issues += ContentIssue(key, "/content/temporal", "bundle.temporal-explicit-required")
    if (resource.content["temporal"] != null && resource.content["temporal"] !is JsonObject && resource.content["temporal"] !is JsonNull)
      issues += ContentIssue(key, "/content/temporal", "temporal.object-or-null-required")
    temporalProblems(resource.content).forEach { issues += ContentIssue(key, "/content${it.path}", it.code) }

    if (resource.id.isBlank() || resource.id.length > 256) issues += ContentIssue(key, "/id", "bundle.invalid-id")
    val mapped = mutableSetOf<String>()
    val claimMappings = mutableMapOf<Int, Set<String>>()
    val claimIds = mutableSetOf<String>()
    resource.ledger.forEachIndexed { claimIndex, claim ->
      val path = "/ledger/$claimIndex"
      if (claim.claimId.isBlank() || claim.claimId.length > 128 || !claimIds.add(claim.claimId))
        issues += ContentIssue(key, "$path/claim_id", "ledger.invalid-or-duplicate-claim-id")
      if (claim.sourceRef.isBlank() || claim.sourceRef.length > 512)
        issues += ContentIssue(key, "$path/source_ref", "ledger.invalid-source-ref")
      if (claim.zone != null && !knownIanaZone(claim.zone))
        issues += ContentIssue(key, "$path/zone", "ledger.invalid-zone")
      if (claim.classification !in classifications) issues += ContentIssue(key, "$path/classification", "ledger.invalid-classification")
      if (claim.certainty !in certainties) issues += ContentIssue(key, "$path/certainty", "ledger.invalid-certainty")
      if (claim.disposition != null && claim.disposition !in dispositions) issues += ContentIssue(key, "$path/disposition", "ledger.invalid-disposition")
      if (claim.behavior && claim.classification == "reference") issues += ContentIssue(key, "$path/behavior", "ledger.reference-behavior-forbidden")
      val normalizedValues = normalizedStrings(claim.normalized)
      val timedValues = normalizedValues.filter(::isAuthoringTimedValue)
      val civilValues = normalizedValues.filter(::isAuthoringCivilValue)
      if (claim.disposition == null && claim.normalized != null &&
        (normalizedValues.isEmpty() || timedValues.size + civilValues.size != normalizedValues.size))
        issues += ContentIssue(key, "$path/normalized", "ledger.invalid-normalized-temporal-value")
      val range = claim.normalized as? JsonObject
      if (range != null) {
        val rangeStart = (range["start"] as? JsonPrimitive)?.contentOrNull
        val rangeEnd = (range["end"] as? JsonPrimitive)?.contentOrNull
        if (range.keys != setOf("start", "end") || rangeStart == null || rangeEnd == null ||
          !authoringRangeIsValid(rangeStart, rangeEnd, claim.zone))
          issues += ContentIssue(key, "$path/normalized", "ledger.invalid-normalized-range")
      }
      if (claim.classification == "window" && range == null && claim.disposition == null)
        issues += ContentIssue(key, "$path/normalized", "ledger.window-range-required")
      if (claim.disposition == null && timedValues.isNotEmpty() && claim.zone == null)
        issues += ContentIssue(key, "$path/zone", "ledger.timed-zone-required")
      if (claim.zone != null && timedValues.any { !authoringInstantMatchesZone(it, claim.zone) })
        issues += ContentIssue(key, "$path/zone", "ledger.zone-offset-mismatch")
      if (claim.zone != null && civilValues.isNotEmpty())
        issues += ContentIssue(key, "$path/zone", "ledger.all-day-zone-forbidden")
      val hasBaseInstant = claim.sourceBaseInstant != null
      val hasBaseZone = claim.sourceBaseZone != null
      if (claim.relative && (!hasBaseInstant || !hasBaseZone))
        issues += ContentIssue(key, "$path", "ledger.relative-source-base-required")
      if (hasBaseInstant != hasBaseZone)
        issues += ContentIssue(key, "$path", "ledger.incomplete-source-base")
      if (hasBaseInstant && hasBaseZone && !authoringInstantMatchesZone(claim.sourceBaseInstant, claim.sourceBaseZone))
        issues += ContentIssue(key, "$path", "ledger.invalid-source-base")
      if (claim.disposition == null) {
        val values = mappedClaimValues(resource.content, claim)
        if (values == null) issues += ContentIssue(key, path, "ledger.mapping-or-value-mismatch")
        else {
          val paths = values.mapTo(linkedSetOf()) { it.first }
          if (paths.any(mapped::contains)) issues += ContentIssue(key, "$path/carrier_path", "ledger.duplicate-mapping")
          mapped.addAll(paths)
          claimMappings[claimIndex] = paths
          val occurrence = occurrenceAtCarrier(resource.content, claim.carrierPath ?: "")
          if (occurrence != null) {
            if (occurrence.bundleString("role") != claim.classification)
              issues += ContentIssue(key, "$path/classification", "ledger.occurrence-role-mismatch")
            if (occurrence.bundleString("status") != claim.certainty)
              issues += ContentIssue(key, "$path/certainty", "ledger.occurrence-status-mismatch")
            if (occurrence.bundleString("zone") != claim.zone)
              issues += ContentIssue(key, "$path/zone", "ledger.occurrence-zone-mismatch")
          }
        }
      } else if (claim.carrierPath != null || claim.normalized != null) {
        issues += ContentIssue(key, path, "ledger.disposition-cannot-map")
      }
    }

    operationalCarrierPaths(resource.content).filterNot(mapped::contains).forEach { carrier ->
      issues += ContentIssue(key, "/content$carrier", "ledger.unmapped-temporal-carrier")
    }
    operationalRangePaths(resource.content).forEach { rangePaths ->
      if (claimMappings.values.none { it.containsAll(rangePaths) })
        issues += ContentIssue(key, "/ledger", "ledger.range-must-be-one-claim")
    }
    val triggerRefs = (resource.content["triggers"] as? JsonArray).orEmpty().mapNotNull { value ->
      (((value as? JsonObject)?.get("when") as? JsonObject)?.get("fact_ref") as? JsonPrimitive)?.contentOrNull
    }
    val behaviorClaims = resource.ledger.withIndex().filter { it.value.behavior }
    if (triggerRefs.isEmpty() && behaviorClaims.isNotEmpty()) {
      issues += ContentIssue(key, "/ledger", "ledger.behavior-trigger-missing")
    } else if (triggerRefs.isNotEmpty()) {
      if (behaviorClaims.size != 1) issues += ContentIssue(key, "/ledger", "ledger.behavior-approval-required")
      else {
        val expected = triggerRefs.singleOrNull()?.let { factRefCarrierPath(resource.content, it) }
        if (expected == null || expected !in claimMappings[behaviorClaims.single().index].orEmpty())
          issues += ContentIssue(key, "/ledger/${behaviorClaims.single().index}/behavior", "ledger.behavior-fact-mismatch")
      }
    }
    if (proseTemporalMentionCount(resource.content) > resource.ledger.sumOf(::ledgerCoverageUnits))
      issues += ContentIssue(key, "/content", "coverage.unreviewed-temporal-mention")
  }
  return issues.distinct()
}

private fun parseObject(body: String): JsonObject? = runCatching { bundleJson.parseToJsonElement(body).jsonObject }.getOrNull()
private fun parseArray(body: String): JsonArray? = runCatching { bundleJson.parseToJsonElement(body).jsonArray }.getOrNull()

private fun currentResource(resource: TemporalBundleResource, network: ContentNetwork, family: String): Pair<JsonObject?, ContentIssue?> {
  val key = "${resource.kind}:${resource.id}"
  val path = when (resource.kind) {
    "card" -> "/families/$family/cards"
    "block", "section" -> "/families/$family/hubs/${resource.hubId}/tree"
    else -> "/families/$family/hubs/${resource.id}"
  }
  val (code, body) = network.get.call(path)
  if (code == 404 && resource.kind != "card") return null to null
  if (code != 200) return null to ContentIssue(key, "/", "apply.read-failed")
  val current = when (resource.kind) {
    "card" -> parseArray(body)?.mapNotNull { it as? JsonObject }?.firstOrNull { it["id"] == JsonPrimitive(resource.id) }
    "block", "section" -> (parseObject(body)?.get(if (resource.kind == "block") "blocks" else "sections") as? JsonArray)
      ?.mapNotNull { it as? JsonObject }?.firstOrNull { it["id"] == JsonPrimitive(resource.id) }
    else -> parseObject(body)
  }
  return current to null
}

private fun hubAcl(hubId: String, network: ContentNetwork, family: String): Pair<BundleAcl?, ContentIssue?> {
  val (code, body) = network.get.call("/families/$family/hubs/$hubId/audience")
  if (code != 200) return null to ContentIssue("hub:$hubId", "/acl", "apply.acl-read-failed")
  val obj = parseObject(body) ?: return null to ContentIssue("hub:$hubId", "/acl", "apply.acl-read-failed")
  val visibility = (obj["visibility"] as? JsonPrimitive)?.contentOrNull ?: return null to ContentIssue("hub:$hubId", "/acl", "apply.acl-read-failed")
  val members = (obj["members"] as? JsonArray)?.mapNotNull { row ->
    val member = row as? JsonObject
    val permitted = (member?.get("permitted") as? JsonPrimitive)?.contentOrNull == "true"
    if (permitted) (member["uid"] as? JsonPrimitive)?.contentOrNull else null
  }.orEmpty().sorted()
  return BundleAcl(visibility, if (visibility == "restricted") members else emptyList()) to null
}

private fun rowAcl(resource: TemporalBundleResource, row: JsonObject?, network: ContentNetwork, family: String): Pair<BundleAcl?, ContentIssue?> {
  if (resource.kind != "card") return hubAcl(resource.hubId ?: resource.id, network, family)
  val visibility = (row?.get("visibility") as? JsonPrimitive)?.contentOrNull ?: if (row == null) resource.acl.visibility else return null to
    ContentIssue("card:${resource.id}", "/acl", "apply.acl-read-failed")
  val audience = (row?.get("audience") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty().sorted()
  return BundleAcl(visibility, if (visibility == "restricted") audience else emptyList()) to null
}

private fun authoredValue(kind: String, row: JsonObject, key: String): JsonElement? = when {
  kind == "card" && key == "target" -> JsonObject(mapOf(
    "hubId" to (row["target_hub_id"] ?: JsonNull),
    "sectionId" to (row["target_section_id"] ?: JsonNull),
    "blockId" to (row["target_block_id"] ?: JsonNull),
  ).filterValues { it !is JsonNull })
  key == "hubRef" -> row["hub_ref"]
  key == "relatedKicker" -> row["related_kicker"]
  key == "sectionId" -> row["section_id"]
  key == "hubId" -> row["hub_id"]
  else -> row[key]
}

private fun temporalValueMatches(expected: JsonElement, actual: JsonElement?): Boolean {
  if (expected !is JsonObject || actual !is JsonObject) return expected == actual
  val expectedOccurrences = expected["occurrences"] as? JsonArray ?: return expected == actual
  val actualOccurrences = actual["occurrences"] as? JsonArray ?: return false
  if (JsonObject(expected - "occurrences") != JsonObject(actual - "occurrences")) return false
  fun byId(values: JsonArray): Map<String, JsonObject>? {
    val result = linkedMapOf<String, JsonObject>()
    for (value in values) {
      val occurrence = value as? JsonObject ?: return null
      val id = occurrence.bundleString("id") ?: return null
      if (result.put(id, occurrence) != null) return null
    }
    return result
  }
  return byId(expectedOccurrences) == byId(actualOccurrences)
}

private fun selectedValueMatches(field: String, expected: JsonElement, actual: JsonElement?): Boolean = when {
  field == "provenance" && expected is JsonObject && actual is JsonObject ->
    listOf("source", "at").all { expected[it] == actual[it] }
  field == "temporal" -> temporalValueMatches(expected, actual)
  else -> actual == expected
}

private fun roundTripIssues(
  resource: TemporalBundleResource,
  row: JsonObject,
  prefix: String,
): List<ContentIssue> {
  val key = "${resource.kind}:${resource.id}"
  val issues = mutableListOf<ContentIssue>()
  resource.content.forEach { (field, expected) ->
    if (field == "version" || field == "visibility" || field == "audience") return@forEach
    val actual = authoredValue(resource.kind, row, field)
    if (!selectedValueMatches(field, expected, actual))
      issues += ContentIssue(key, "/content/$field", "$prefix-field-mismatch")
  }
  if (row["id"] != JsonPrimitive(resource.id)) issues += ContentIssue(key, "/id", "$prefix-id-mismatch")
  return issues
}

private fun rowVersion(row: JsonObject): Long? =
  (row["version"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

internal fun applyTemporalBundle(
  bundle: TemporalBundle,
  family: String,
  network: ContentNetwork,
  dryRun: Boolean,
): ContentCommandResult {
  val validation = validateTemporalBundle(bundle)
  if (validation.isNotEmpty()) return ContentCommandResult(1, stderr = validation.joinToString("\n", transform = ContentIssue::render))

  val alreadyDesired = mutableSetOf<String>()
  val bundledHubAcls = bundle.resources.filter { it.kind == "hub" }.associate { it.id to it.acl }
  for (resource in bundle.resources) {
    val key = "${resource.kind}:${resource.id}"
    val (row, readIssue) = currentResource(resource, network, family)
    if (readIssue != null) return ContentCommandResult(2, stderr = readIssue.render())
    val liveVersion = row?.let(::rowVersion)
    val bundledParentAcl = resource.hubId?.let(bundledHubAcls::get)
    var liveAcl: BundleAcl? = null
    if (row == null && resource.kind in setOf("block", "section") && bundledParentAcl != null) {
      if (resource.acl.copy(audience = resource.acl.audience.sorted()) != bundledParentAcl.copy(audience = bundledParentAcl.audience.sorted()))
        return ContentCommandResult(1, stderr = ContentIssue(key, "/acl", "apply.parent-acl-mismatch").render())
    } else if (row != null || resource.kind in setOf("block", "section")) {
      val (acl, aclIssue) = rowAcl(resource, row, network, family)
      if (aclIssue != null) return ContentCommandResult(2, stderr = aclIssue.render())
      liveAcl = acl
      if (acl != resource.acl.copy(audience = resource.acl.audience.sorted()))
        return ContentCommandResult(1, stderr = ContentIssue(key, "/acl", "apply.acl-mismatch").render())
    }
    if (row != null && liveVersion != null && liveAcl == resource.acl.copy(audience = resource.acl.audience.sorted()) &&
      roundTripIssues(resource, row, "apply.current").isEmpty()) {
      alreadyDesired += key
    } else if (row != null && (resource.baseVersion == null || resource.baseVersion != liveVersion)) {
      return ContentCommandResult(1, stderr = ContentIssue(key, "/base_version", "apply.stale-base").render())
    }
  }
  val occurrenceCount = bundle.resources.sumOf {
    (((it.content["temporal"] as? JsonObject)?.get("occurrences") as? JsonArray)?.size ?: 0)
  }
  val factRefCount = bundle.resources.sumOf { resource ->
    (resource.content["triggers"] as? JsonArray).orEmpty().count { value ->
      ((((value as? JsonObject)?.get("when") as? JsonObject)?.get("fact_ref") as? JsonPrimitive)?.isString == true)
    }
  }
  fun receipt(prefix: String, writes: Int): String =
    "$prefix resources=${bundle.resources.size} occurrences=$occurrenceCount fact_refs=$factRefCount " +
      "review_issues=0 writes=$writes skipped=${alreadyDesired.size} round_trip=verified"
  if (dryRun) return ContentCommandResult(0, stdout = receipt("dry-run verified", 0))

  var writes = 0
  val writeOrder = mapOf("hub" to 0, "section" to 1, "block" to 2, "card" to 3)
  for (resource in bundle.resources.sortedWith(compareBy({ writeOrder[it.kind] ?: 4 }, { it.id }))) {
    val key = "${resource.kind}:${resource.id}"
    if (key in alreadyDesired) continue
    val pathKind = when (resource.kind) { "card" -> "cards"; "block" -> "blocks"; "section" -> "sections"; else -> "hubs" }
    val body = JsonObject(resource.content + when (resource.kind) {
      "card", "hub" -> mapOf("visibility" to JsonPrimitive(resource.acl.visibility), "audience" to JsonArray(resource.acl.audience.map(::JsonPrimitive)))
      "block" -> mapOf("sectionId" to (resource.content["sectionId"] ?: JsonNull))
      "section" -> mapOf("hubId" to JsonPrimitive(resource.hubId!!))
      else -> emptyMap()
    }).toString()
    val headers = buildMap {
      put("x-dayfold-content-capability", "temporal-v1")
      resource.baseVersion?.let { put("if-match", it.toString()) }
    }
    val (code, responseBody) = network.put.call("/families/$family/$pathKind/${resource.id}", body, headers)
    if (code != 200) return ContentCommandResult(2, stderr = ContentIssue(key, "/", "apply.write-failed").render())
    val response = parseObject(responseBody) ?: return ContentCommandResult(2, stderr = ContentIssue(key, "/", "apply.invalid-response").render())
    val responseIssues = roundTripIssues(resource, response, "apply.response")
    if (responseIssues.isNotEmpty()) return ContentCommandResult(1, stderr = responseIssues.joinToString("\n", transform = ContentIssue::render))
    val responseVersion = rowVersion(response)
    if (responseVersion == null || (resource.baseVersion != null && responseVersion <= resource.baseVersion))
      return ContentCommandResult(1, stderr = ContentIssue(key, "/version", "apply.response-version-mismatch").render())
    val (responseAcl, responseAclIssue) = rowAcl(resource, response, network, family)
    if (responseAclIssue != null) return ContentCommandResult(2, stderr = responseAclIssue.render())
    if (responseAcl != resource.acl.copy(audience = resource.acl.audience.sorted()))
      return ContentCommandResult(1, stderr = ContentIssue(key, "/acl", "apply.response-acl-mismatch").render())
    val (pulled, pullIssue) = currentResource(resource, network, family)
    if (pullIssue != null || pulled == null) return ContentCommandResult(2, stderr = (pullIssue ?: ContentIssue(key, "/", "apply.pull-failed")).render())
    val pullIssues = roundTripIssues(resource, pulled, "apply.pull")
    if (pullIssues.isNotEmpty()) return ContentCommandResult(1, stderr = pullIssues.joinToString("\n", transform = ContentIssue::render))
    if (rowVersion(pulled) != responseVersion)
      return ContentCommandResult(1, stderr = ContentIssue(key, "/version", "apply.pull-version-mismatch").render())
    val (pulledAcl, pulledAclIssue) = rowAcl(resource, pulled, network, family)
    if (pulledAclIssue != null) return ContentCommandResult(2, stderr = pulledAclIssue.render())
    if (pulledAcl != resource.acl.copy(audience = resource.acl.audience.sorted()))
      return ContentCommandResult(1, stderr = ContentIssue(key, "/acl", "apply.pull-acl-mismatch").render())
    writes++
  }
  return ContentCommandResult(0, stdout = receipt("verified", writes))
}

internal fun auditContent(raw: String, includeResourceIds: Boolean = false): ContentCommandResult {
  if (raw.toByteArray().size > MAX_TEMPORAL_BUNDLE_BYTES)
    return ContentCommandResult(2, stderr = "audit / audit.too-large")
  val root = runCatching { bundleJson.parseToJsonElement(raw) }.getOrElse {
    return ContentCommandResult(2, stderr = "audit / audit.invalid-json")
  }
  val resources = mutableListOf<Pair<String, JsonObject>>()
  fun add(kind: String, element: JsonElement?) {
    when (element) {
      is JsonArray -> element.mapNotNull { it as? JsonObject }.forEach { row -> resources += kind to row }
      is JsonObject -> resources += kind to element
      else -> Unit
    }
  }
  if (root is JsonObject) {
    add("card", root["cards"])
    add("hub", root["hubs"])
    add("block", root["blocks"])
    (root["hub"] as? JsonObject)?.let { add("hub", it) }
  } else if (root is JsonArray) add("card", root)
  val issues = mutableListOf<ContentIssue>()
  resources.forEachIndexed { index, (kind, resource) ->
    val key = if (includeResourceIds) "$kind:${(resource["id"] as? JsonPrimitive)?.contentOrNull ?: "unknown"}" else "$kind[$index]"
    temporalProblems(resource).forEach { issues += ContentIssue(key, it.path, it.code) }
    if (proseTemporalMentionCount(resource) > structuredTemporalValueCount(resource))
      issues += ContentIssue(key, "/", "coverage.possible-unstructured-temporal-claim")
  }
  return if (issues.isEmpty()) ContentCommandResult(0, stdout = "audit clean resources=${resources.size} issues=0")
  else ContentCommandResult(1, stdout = "audit resources=${resources.size} issues=${issues.size}", stderr = issues.joinToString("\n", transform = ContentIssue::render))
}
