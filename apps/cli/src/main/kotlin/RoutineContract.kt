package com.sloopworks.dayfold.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

private val ROUTINE_JSON = Json { ignoreUnknownKeys = false }
private val RESOURCE_ID = Regex("^[A-Za-z0-9_-]{1,128}$")
private val OP_ID = Regex("^[0-9A-HJKMNP-TV-Z]{26}$")

private val SOURCES = setOf("dayfold", "gmail", "google_calendar", "google_drive")
private val CARD_KINDS = setOf("action", "info", "weather", "countdown")
private val SOURCE_OUTCOMES = setOf("zero_results", "observed", "partial", "reauth_required", "admin_blocked", "unavailable")
private val EMPTY_SOURCE_OUTCOMES = setOf("zero_results", "reauth_required", "admin_blocked", "unavailable")
private val OBSERVED_SOURCE_OUTCOMES = setOf("observed", "partial")
private val HEALTHY_SOURCE_OUTCOMES = setOf("observed", "zero_results")
private val RUN_RESULTS = setOf("success", "no_changes", "partial", "rejected", "failed")
private val RECOVERY_PHASES = setOf(
  "enrollment", "provider_handoff", "authorization", "source_probe", "provider_observation",
  "context_read", "analysis", "validation", "stage", "apply", "finish", "revoke",
)
private val RECOVERY_REASONS = setOf(
  "enrollment_expired", "routine_not_confirmed", "app_return_lost", "preparation_failed",
  "provider_unavailable", "authorization_denied", "authorization_context_mismatch", "late_callback",
  "provider_quota", "source_reauth", "source_syncing", "source_not_observed", "admin_blocked",
  "unexpected_source_set", "partial_source_set", "run_reported_failed", "gateway_unreachable",
  "invalid_output", "policy_rejected", "write_cap", "conflict", "rate_limited", "confirm_pending",
  "revoke_failed", "provider_task_remains", "retries_exhausted", "repeated_conflicts",
  "volume_anomaly", "timeout", "offline", "internal",
)
private val RETRYABILITY = setOf("automatic", "user_action", "final")
private val RECOMMENDED_ACTIONS = setOf(
  "retry", "resume_provider", "manage_source", "restart_enrollment", "continue_review_only",
  "refresh_draft", "review_policy", "contact_support",
)
private val FORBIDDEN_OPERATIONS = listOf("update_card", "delete", "change_acl", "change_role", "invite", "message")

/** A stable, content-free contract diagnostic. Never attach a JSON value or parser message. */
data class RoutineIssue(val path: String, val code: String) {
  override fun toString(): String = "$path $code"
}

data class RoutineManifest(
  val routineId: String,
  val familyId: String,
  val selectedTargetHubId: String,
  val requestedSources: List<String>,
  val maxOperations: Int,
)

data class RoutineCard(
  val id: String,
  val kind: String,
  val title: String,
  val bodyMd: String?,
)

data class RoutineOperation(
  val opId: String,
  val targetHubId: String,
  val card: RoutineCard,
  val sourceRefs: List<String>,
)

data class RoutineChangeset(
  val routineId: String,
  val familyId: String,
  val operations: List<RoutineOperation>,
)

data class RoutineContracts(val manifest: RoutineManifest, val changeset: RoutineChangeset)

data class RoutineValidation<T>(val value: T?, val issues: List<RoutineIssue>) {
  val isValid: Boolean get() = value != null && issues.isEmpty()
}

/** Producer validation for the two CLI inputs, including cross-document policy. */
fun validateRoutineContracts(manifestRaw: String, changesetRaw: String): RoutineValidation<RoutineContracts> {
  val issues = mutableListOf<RoutineIssue>()
  val manifestElement = parseObject(manifestRaw, "/manifest", issues)
  val changesetElement = parseObject(changesetRaw, "/changeset", issues)
  val manifest = manifestElement?.let { parseManifest(it, "/manifest", issues) }
  val changeset = changesetElement?.let { parseChangeset(it, "/changeset", issues) }
  if (manifest != null && changeset != null) validatePolicy(manifest, changeset, issues)
  return RoutineValidation(
    value = if (issues.isEmpty() && manifest != null && changeset != null) RoutineContracts(manifest, changeset) else null,
    issues = issues,
  )
}

/** Validate a shared conformance scenario from specs/domain-model/examples/routines. */
fun validateRoutineScenario(raw: String): RoutineValidation<RoutineContracts> {
  val issues = mutableListOf<RoutineIssue>()
  val root = parseObject(raw, "", issues)
    ?: return RoutineValidation(null, issues)
  rejectUnknown(root, setOf("manifest", "changeset", "finish", "current", "expectedDiff"), "", issues)
  val manifestObject = requiredObject(root, "manifest", "", issues)
  val changesetObject = requiredObject(root, "changeset", "", issues)
  val manifest = manifestObject?.let { parseManifest(it, "/manifest", issues) }
  val changeset = changesetObject?.let { parseChangeset(it, "/changeset", issues) }
  if (manifest != null && changeset != null) {
    validatePolicy(manifest, changeset, issues)
    root["finish"]?.let { finishElement ->
      val finish = finishElement as? JsonObject
      if (finish == null) issues += RoutineIssue("/finish", "schema.type")
      else parseFinish(finish, manifest, changeset, "/finish", issues)
    }
  }
  return RoutineValidation(
    value = if (issues.isEmpty() && manifest != null && changeset != null) RoutineContracts(manifest, changeset) else null,
    issues = issues,
  )
}

private fun parseObject(raw: String, path: String, issues: MutableList<RoutineIssue>): JsonObject? {
  val element = try {
    ROUTINE_JSON.parseToJsonElement(raw)
  } catch (_: Exception) {
    issues += RoutineIssue(path.ifEmpty { "/" }, "json.syntax")
    return null
  }
  return (element as? JsonObject) ?: run {
    issues += RoutineIssue(path.ifEmpty { "/" }, "schema.type")
    null
  }
}

private fun parseManifest(root: JsonObject, path: String, issues: MutableList<RoutineIssue>): RoutineManifest? {
  val before = issues.size
  rejectUnknown(
    root,
    setOf(
      "schemaVersion", "routineId", "familyId", "executionMode", "requestedPublicationMode",
      "selectedTargetHubIds", "requestedSources", "sourceWindow", "maxOperations", "authority",
    ),
    path,
    issues,
  )
  exactInt(root, "schemaVersion", path, 1, issues)
  val routineId = resourceId(root, "routineId", path, issues)
  val familyId = resourceId(root, "familyId", path, issues)
  exactString(root, "executionMode", path, "shadow", issues)
  exactString(root, "requestedPublicationMode", path, "review", issues)
  val hubs = stringArray(root, "selectedTargetHubIds", path, 1, 1, null, RESOURCE_ID, issues)
  val sources = stringArray(root, "requestedSources", path, 1, 4, SOURCES, null, issues)
  intInRange(root, "maxOperations", path, 0, 25, issues)

  requiredObject(root, "sourceWindow", path, issues)?.let { window ->
    val windowPath = "$path/sourceWindow"
    rejectUnknown(window, setOf("lookbackDays", "maxRecordsPerSource"), windowPath, issues)
    intInRange(window, "lookbackDays", windowPath, 1, 30, issues)
    intInRange(window, "maxRecordsPerSource", windowPath, 1, 200, issues)
  }
  requiredObject(root, "authority", path, issues)?.let { authority ->
    val authorityPath = "$path/authority"
    rejectUnknown(authority, setOf("allowedOperations", "forbiddenOperations"), authorityPath, issues)
    val allowed = stringArray(authority, "allowedOperations", authorityPath, 1, 1, null, null, issues)
    if (allowed != null && allowed != listOf("create_card")) {
      issues += RoutineIssue("$authorityPath/allowedOperations", "schema.const")
    }
    val forbidden = stringArray(authority, "forbiddenOperations", authorityPath, 6, 6, null, null, issues)
    if (forbidden != null && forbidden != FORBIDDEN_OPERATIONS) {
      issues += RoutineIssue("$authorityPath/forbiddenOperations", "schema.const")
    }
  }
  val maxOperations = integer(root["maxOperations"])
  if (issues.size != before || routineId == null || familyId == null || hubs == null || sources == null || maxOperations == null) return null
  return RoutineManifest(routineId, familyId, hubs.single(), sources, maxOperations)
}

private fun parseChangeset(root: JsonObject, path: String, issues: MutableList<RoutineIssue>): RoutineChangeset? {
  val before = issues.size
  rejectUnknown(root, setOf("schemaVersion", "routineId", "familyId", "operations"), path, issues)
  exactInt(root, "schemaVersion", path, 1, issues)
  val routineId = resourceId(root, "routineId", path, issues)
  val familyId = resourceId(root, "familyId", path, issues)
  val operationElements = requiredArray(root, "operations", path, issues)
  if (operationElements != null && operationElements.size > 25) {
    issues += RoutineIssue("$path/operations", "schema.maxItems")
  }
  val operations = operationElements?.mapIndexedNotNull { index, element ->
    val operation = element as? JsonObject
    if (operation == null) {
      issues += RoutineIssue("$path/operations/$index", "schema.type")
      null
    } else parseOperation(operation, "$path/operations/$index", issues)
  }
  if (issues.size != before || routineId == null || familyId == null || operations == null) return null
  return RoutineChangeset(routineId, familyId, operations)
}

private fun parseOperation(root: JsonObject, path: String, issues: MutableList<RoutineIssue>): RoutineOperation? {
  val before = issues.size
  rejectUnknown(root, setOf("opId", "kind", "targetHubId", "card", "sourceRefs"), path, issues)
  val opId = requiredString(root, "opId", path, issues)?.also {
    if (!OP_ID.matches(it)) issues += RoutineIssue("$path/opId", "schema.pattern")
  }
  exactString(root, "kind", path, "create_card", issues)
  val targetHubId = resourceId(root, "targetHubId", path, issues)
  val card = requiredObject(root, "card", path, issues)?.let { parseCard(it, "$path/card", issues) }
  val sourceRefs = stringArray(root, "sourceRefs", path, 0, 20, null, RESOURCE_ID, issues)
  if (issues.size != before || opId == null || targetHubId == null || card == null || sourceRefs == null) return null
  return RoutineOperation(opId, targetHubId, card, sourceRefs)
}

private fun parseCard(root: JsonObject, path: String, issues: MutableList<RoutineIssue>): RoutineCard? {
  val before = issues.size
  rejectUnknown(root, setOf("id", "kind", "title", "body_md"), path, issues)
  val id = resourceId(root, "id", path, issues)
  val kind = enumString(root, "kind", path, CARD_KINDS, issues)
  val title = boundedString(root, "title", path, 1, 4096, required = true, issues = issues)
  val body = boundedString(root, "body_md", path, 0, 65536, required = false, issues = issues)
  if (issues.size != before || id == null || kind == null || title == null) return null
  return RoutineCard(id, kind, title, body)
}

private fun parseFinish(
  root: JsonObject,
  manifest: RoutineManifest,
  changeset: RoutineChangeset,
  path: String,
  issues: MutableList<RoutineIssue>,
) {
  rejectUnknown(root, setOf("schemaVersion", "routineId", "familyId", "runId", "result", "sourceOutcomes", "counts", "recovery"), path, issues)
  exactInt(root, "schemaVersion", path, 1, issues)
  val routineId = resourceId(root, "routineId", path, issues)
  val familyId = resourceId(root, "familyId", path, issues)
  resourceId(root, "runId", path, issues)
  val result = enumString(root, "result", path, RUN_RESULTS, issues)
  val outcomes = requiredArray(root, "sourceOutcomes", path, issues)
  if (outcomes != null && (outcomes.size !in 1..4)) issues += RoutineIssue("$path/sourceOutcomes", "schema.itemCount")
  val seenSources = mutableSetOf<String>()
  outcomes?.forEachIndexed { index, element ->
    val outcomePath = "$path/sourceOutcomes/$index"
    val outcome = element as? JsonObject
    if (outcome == null) {
      issues += RoutineIssue(outcomePath, "schema.type")
      return@forEachIndexed
    }
    rejectUnknown(outcome, setOf("source", "outcome", "recordsObserved"), outcomePath, issues)
    val source = enumString(outcome, "source", outcomePath, SOURCES, issues)
    val outcomeValue = enumString(outcome, "outcome", outcomePath, SOURCE_OUTCOMES, issues)
    val records = intInRange(outcome, "recordsObserved", outcomePath, 0, 200, issues)
    if (source != null && !seenSources.add(source)) issues += RoutineIssue("$outcomePath/source", "policy.duplicate_source")
    if (source != null && source !in manifest.requestedSources) issues += RoutineIssue("$outcomePath/source", "policy.unexpected_source")
    if (outcomeValue in EMPTY_SOURCE_OUTCOMES && records != null && records != 0) {
      issues += RoutineIssue("$outcomePath/recordsObserved", "policy.source_outcome_count")
    }
    if (outcomeValue in OBSERVED_SOURCE_OUTCOMES && records != null && records < 1) {
      issues += RoutineIssue("$outcomePath/recordsObserved", "policy.source_outcome_count")
    }
  }
  manifest.requestedSources.forEach { source ->
    if (source !in seenSources) issues += RoutineIssue("$path/sourceOutcomes", "policy.missing_source")
  }

  var proposed: Int? = null
  var creates: Int? = null
  var noChanges: Int? = null
  var conflicts: Int? = null
  var rejected: Int? = null
  requiredObject(root, "counts", path, issues)?.let { counts ->
    val countsPath = "$path/counts"
    rejectUnknown(counts, setOf("operationsProposed", "creates", "noChanges", "conflicts", "rejected"), countsPath, issues)
    proposed = intInRange(counts, "operationsProposed", countsPath, 0, 25, issues)
    creates = intInRange(counts, "creates", countsPath, 0, 25, issues)
    noChanges = intInRange(counts, "noChanges", countsPath, 0, 25, issues)
    conflicts = intInRange(counts, "conflicts", countsPath, 0, 25, issues)
    rejected = intInRange(counts, "rejected", countsPath, 0, 25, issues)
  }

  val recovery = root["recovery"]
  if (recovery != null) {
    val recoveryObject = recovery as? JsonObject
    if (recoveryObject == null) issues += RoutineIssue("$path/recovery", "schema.type")
    else parseRecovery(recoveryObject, "$path/recovery", issues)
  }
  if (result in setOf("partial", "failed", "rejected") && recovery == null) issues += RoutineIssue("$path/recovery", "schema.required")
  if (result in setOf("success", "no_changes") && recovery != null) issues += RoutineIssue("$path/recovery", "schema.forbidden")
  if (familyId != null && familyId != manifest.familyId) issues += RoutineIssue("$path/familyId", "policy.family_mismatch")
  if (routineId != null && routineId != manifest.routineId) issues += RoutineIssue("$path/routineId", "policy.routine_mismatch")
  if (proposed != null && proposed != changeset.operations.size) issues += RoutineIssue("$path/counts/operationsProposed", "policy.proposed_count")
  if (listOf(creates, noChanges, conflicts, rejected).all { it != null } && proposed != null &&
    creates!! + noChanges!! + conflicts!! + rejected!! != proposed
  ) issues += RoutineIssue("$path/counts", "policy.count_sum")
  if (result != null && listOf(proposed, creates, noChanges, conflicts, rejected).all { it != null } && outcomes != null) {
    val parsedOutcomes = outcomes.mapNotNull { element ->
      (element as? JsonObject)?.get("outcome")?.let { it as? JsonPrimitive }
        ?.takeIf { it.isString }?.contentOrNull
    }
    val healthySources = parsedOutcomes.size == outcomes.size && parsedOutcomes.all { it in HEALTHY_SOURCE_OUTCOMES }
    val degradedSources = parsedOutcomes.any { it !in HEALTHY_SOURCE_OUTCOMES }
    val resultCoherent = when (result) {
      "success" -> healthySources && recovery == null && creates!! > 0 && conflicts == 0 && rejected == 0
      "no_changes" -> healthySources && recovery == null && creates == 0 && conflicts == 0 && rejected == 0
      "partial" -> recovery != null && (degradedSources || conflicts!! > 0 || rejected!! > 0)
      "rejected" -> recovery != null && proposed!! > 0 && creates == 0 && noChanges == 0 && conflicts == 0 && rejected == proposed
      "failed" -> recovery != null && proposed == 0 && creates == 0 && noChanges == 0 && conflicts == 0 && rejected == 0
      else -> false
    }
    if (!resultCoherent) issues += RoutineIssue("$path/result", "policy.result_coherence")
  }
}

private fun parseRecovery(root: JsonObject, path: String, issues: MutableList<RoutineIssue>) {
  rejectUnknown(root, setOf("schemaVersion", "phase", "reason", "retryability", "recommendedAction"), path, issues)
  exactInt(root, "schemaVersion", path, 1, issues)
  enumString(root, "phase", path, RECOVERY_PHASES, issues)
  enumString(root, "reason", path, RECOVERY_REASONS, issues)
  enumString(root, "retryability", path, RETRYABILITY, issues)
  enumString(root, "recommendedAction", path, RECOMMENDED_ACTIONS, issues)
}

private fun validatePolicy(manifest: RoutineManifest, changeset: RoutineChangeset, issues: MutableList<RoutineIssue>) {
  if (manifest.familyId != changeset.familyId) issues += RoutineIssue("/changeset/familyId", "policy.family_mismatch")
  if (manifest.routineId != changeset.routineId) issues += RoutineIssue("/changeset/routineId", "policy.routine_mismatch")
  if (changeset.operations.size > manifest.maxOperations) issues += RoutineIssue("/changeset/operations", "policy.operation_cap")
  val opIds = mutableSetOf<String>()
  val resourceIds = mutableSetOf<String>()
  changeset.operations.forEachIndexed { index, operation ->
    val path = "/changeset/operations/$index"
    if (operation.targetHubId != manifest.selectedTargetHubId) issues += RoutineIssue("$path/targetHubId", "policy.target_hub")
    if (!opIds.add(operation.opId)) issues += RoutineIssue("$path/opId", "policy.duplicate_op_id")
    if (!resourceIds.add("briefing_card:${operation.card.id}")) issues += RoutineIssue("$path/card/id", "policy.duplicate_resource")
  }
}

private fun rejectUnknown(root: JsonObject, allowed: Set<String>, path: String, issues: MutableList<RoutineIssue>) {
  if (root.keys.any { it !in allowed }) {
    // Do not echo an attacker-controlled property name: JSON keys can contain
    // source content just as values can. The parent object path is sufficient.
    issues += RoutineIssue(path.ifEmpty { "/" }, "schema.additionalProperties")
  }
}

private fun requiredObject(root: JsonObject, key: String, path: String, issues: MutableList<RoutineIssue>): JsonObject? {
  val value = root[key]
  if (value == null) {
    issues += RoutineIssue("$path/$key", "schema.required")
    return null
  }
  if (value !is JsonObject) {
    issues += RoutineIssue("$path/$key", "schema.type")
    return null
  }
  return value
}

private fun requiredArray(root: JsonObject, key: String, path: String, issues: MutableList<RoutineIssue>): JsonArray? {
  val value = root[key]
  if (value == null) {
    issues += RoutineIssue("$path/$key", "schema.required")
    return null
  }
  if (value !is JsonArray) {
    issues += RoutineIssue("$path/$key", "schema.type")
    return null
  }
  return value
}

private fun requiredString(root: JsonObject, key: String, path: String, issues: MutableList<RoutineIssue>): String? {
  val value = root[key]
  if (value == null) {
    issues += RoutineIssue("$path/$key", "schema.required")
    return null
  }
  val primitive = value as? JsonPrimitive
  val string = primitive?.takeIf { it.isString }?.contentOrNull
  if (string == null) issues += RoutineIssue("$path/$key", "schema.type")
  return string
}

private fun resourceId(root: JsonObject, key: String, path: String, issues: MutableList<RoutineIssue>): String? =
  requiredString(root, key, path, issues)?.also {
    if (!RESOURCE_ID.matches(it)) issues += RoutineIssue("$path/$key", "schema.pattern")
  }

private fun exactString(root: JsonObject, key: String, path: String, expected: String, issues: MutableList<RoutineIssue>) {
  requiredString(root, key, path, issues)?.let {
    if (it != expected) issues += RoutineIssue("$path/$key", "schema.const")
  }
}

private fun enumString(root: JsonObject, key: String, path: String, allowed: Set<String>, issues: MutableList<RoutineIssue>): String? =
  requiredString(root, key, path, issues)?.also {
    if (it !in allowed) issues += RoutineIssue("$path/$key", "schema.enum")
  }

private fun boundedString(
  root: JsonObject,
  key: String,
  path: String,
  min: Int,
  max: Int,
  required: Boolean,
  issues: MutableList<RoutineIssue>,
): String? {
  if (!required && root[key] == null) return null
  val value = requiredString(root, key, path, issues) ?: return null
  val codePointLength = value.codePointCount(0, value.length)
  if (codePointLength < min) issues += RoutineIssue("$path/$key", "schema.minLength")
  if (codePointLength > max) issues += RoutineIssue("$path/$key", "schema.maxLength")
  return value
}

private fun integer(value: JsonElement?): Int? =
  (value as? JsonPrimitive)?.takeIf { !it.isString && it.booleanOrNull == null }?.intOrNull

private fun exactInt(root: JsonObject, key: String, path: String, expected: Int, issues: MutableList<RoutineIssue>) {
  val value = root[key]
  if (value == null) {
    issues += RoutineIssue("$path/$key", "schema.required")
    return
  }
  val parsed = integer(value)
  if (parsed == null) issues += RoutineIssue("$path/$key", "schema.type")
  else if (parsed != expected) issues += RoutineIssue("$path/$key", "schema.const")
}

private fun intInRange(
  root: JsonObject,
  key: String,
  path: String,
  min: Int,
  max: Int,
  issues: MutableList<RoutineIssue>,
): Int? {
  val value = root[key]
  if (value == null) {
    issues += RoutineIssue("$path/$key", "schema.required")
    return null
  }
  val parsed = integer(value)
  if (parsed == null) issues += RoutineIssue("$path/$key", "schema.type")
  else if (parsed !in min..max) issues += RoutineIssue("$path/$key", "schema.range")
  return parsed
}

private fun stringArray(
  root: JsonObject,
  key: String,
  path: String,
  min: Int,
  max: Int,
  allowed: Set<String>?,
  pattern: Regex?,
  issues: MutableList<RoutineIssue>,
): List<String>? {
  val array = requiredArray(root, key, path, issues) ?: return null
  if (array.size !in min..max) issues += RoutineIssue("$path/$key", "schema.itemCount")
  val values = array.mapIndexedNotNull { index, element ->
    val value = (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    if (value == null) {
      issues += RoutineIssue("$path/$key/$index", "schema.type")
      null
    } else {
      if (allowed != null && value !in allowed) issues += RoutineIssue("$path/$key/$index", "schema.enum")
      if (pattern != null && !pattern.matches(value)) issues += RoutineIssue("$path/$key/$index", "schema.pattern")
      value
    }
  }
  if (values.size != values.toSet().size) issues += RoutineIssue("$path/$key", "schema.uniqueItems")
  return values
}

/** Tolerant reader behavior for future recovery values; the raw wire value is discarded. */
enum class RecoveryReason {
  ENROLLMENT_EXPIRED, ROUTINE_NOT_CONFIRMED, APP_RETURN_LOST, PREPARATION_FAILED,
  PROVIDER_UNAVAILABLE, AUTHORIZATION_DENIED, AUTHORIZATION_CONTEXT_MISMATCH, LATE_CALLBACK,
  PROVIDER_QUOTA, SOURCE_REAUTH, SOURCE_SYNCING, SOURCE_NOT_OBSERVED, ADMIN_BLOCKED,
  UNEXPECTED_SOURCE_SET, PARTIAL_SOURCE_SET, RUN_REPORTED_FAILED, GATEWAY_UNREACHABLE,
  INVALID_OUTPUT, POLICY_REJECTED, WRITE_CAP, CONFLICT, RATE_LIMITED, CONFIRM_PENDING,
  REVOKE_FAILED, PROVIDER_TASK_REMAINS, RETRIES_EXHAUSTED, REPEATED_CONFLICTS,
  VOLUME_ANOMALY, TIMEOUT, OFFLINE, INTERNAL, UNKNOWN,
}

fun decodeRecoveryReason(schemaVersion: Int, wireValue: String): RecoveryReason {
  if (schemaVersion != 1 || wireValue !in RECOVERY_REASONS) return RecoveryReason.UNKNOWN
  return runCatching { RecoveryReason.valueOf(wireValue.uppercase()) }.getOrDefault(RecoveryReason.UNKNOWN)
}
