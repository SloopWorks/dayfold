package com.sloopworks.dayfold.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val DIFF_JSON = Json { ignoreUnknownKeys = true }

data class RoutineReadResponse(val statusCode: Int, val body: String)

/** The network seam is deliberately read-only: it exposes no generic method or write verb. */
fun interface RoutineCardReader {
  fun readCards(): RoutineReadResponse
}

data class RoutineDiffSummary(val create: Int, val noChange: Int, val conflict: Int) {
  fun render(): String = "changeset diff: create=$create no_change=$noChange conflict=$conflict"
}

data class RoutineCommandResult(val exitCode: Int, val stdout: String = "", val stderr: String = "")

private data class CanonicalCard(
  val id: String,
  val kind: String,
  val title: String,
  val bodyMd: String?,
  val hubRef: String?,
  val hasUnexpectedContent: Boolean = false,
)

sealed interface ChangesetInvocation {
  data class Validate(val manifestFile: String, val changesetFile: String) : ChangesetInvocation
  data class Diff(val manifestFile: String, val changesetFile: String, val currentFile: String?) : ChangesetInvocation
  data object Invalid : ChangesetInvocation
}

/** Parse the full argv for the nested `changeset` command without accepting hidden flags. */
fun parseChangesetInvocation(args: Array<String>): ChangesetInvocation {
  if (args.getOrNull(0) != "changeset") return ChangesetInvocation.Invalid
  return when (args.getOrNull(1)) {
    "validate" -> if (args.size == 4) ChangesetInvocation.Validate(args[2], args[3]) else ChangesetInvocation.Invalid
    "diff" -> when {
      args.size == 4 -> ChangesetInvocation.Diff(args[2], args[3], null)
      args.size == 6 && args[4] == "--current" -> ChangesetInvocation.Diff(args[2], args[3], args[5])
      else -> ChangesetInvocation.Invalid
    }
    else -> ChangesetInvocation.Invalid
  }
}

fun validateChangesetCommand(manifestRaw: String, changesetRaw: String): RoutineCommandResult {
  val validation = validateRoutineContracts(manifestRaw, changesetRaw)
  if (!validation.isValid) return validationFailure(validation.issues)
  val operationCount = validation.value!!.changeset.operations.size
  return RoutineCommandResult(
    exitCode = 0,
    stdout = "changeset valid: operations=$operationCount execution_mode=shadow authority=create_card",
  )
}

/**
 * Pure create-only comparison. The reader factory is not touched until both local
 * contracts pass, which keeps validation at zero HTTP and invalid diff at zero HTTP.
 */
fun diffChangesetCommand(
  manifestRaw: String,
  changesetRaw: String,
  currentRaw: String? = null,
  readerFactory: (() -> RoutineCardReader)? = null,
): RoutineCommandResult {
  val validation = validateRoutineContracts(manifestRaw, changesetRaw)
  if (!validation.isValid) return validationFailure(validation.issues)

  val resolvedCurrent = if (currentRaw != null) currentRaw else {
    val reader = try {
      readerFactory?.invoke()
    } catch (_: Exception) {
      return RoutineCommandResult(1, stderr = "changeset diff failed: network_read_setup")
    } ?: return RoutineCommandResult(2, stderr = "changeset diff failed: current_required")
    val response = try {
      reader.readCards()
    } catch (_: Exception) {
      return RoutineCommandResult(1, stderr = "changeset diff failed: network_read")
    }
    when (response.statusCode) {
      200 -> response.body
      403 -> return RoutineCommandResult(
        1,
        stderr = "changeset diff failed: family-wide content:read is required; a hub-only credential cannot compare cards. Re-run `dayfold login` only if the owner intends to grant that scope.",
      )
      401 -> return RoutineCommandResult(1, stderr = "changeset diff failed: session expired; run `dayfold login`")
      else -> return RoutineCommandResult(1, stderr = "changeset diff failed: card read returned HTTP ${response.statusCode}")
    }
  }

  val current = parseCurrentCards(resolvedCurrent)
  if (current.issues.isNotEmpty()) return validationFailure(current.issues)
  val summary = diffCreateOnly(validation.value!!, current.cards)
  return RoutineCommandResult(0, stdout = summary.render())
}

private data class CurrentCards(val cards: Map<String, CanonicalCard>, val issues: List<RoutineIssue>)

private fun parseCurrentCards(raw: String): CurrentCards {
  val issues = mutableListOf<RoutineIssue>()
  val root = try {
    DIFF_JSON.parseToJsonElement(raw)
  } catch (_: Exception) {
    return CurrentCards(emptyMap(), listOf(RoutineIssue("/current", "json.syntax")))
  }
  val cardsElement: JsonElement? = when (root) {
    is JsonArray -> root
    is JsonObject -> root["cards"]
    else -> null
  }
  val cards = cardsElement as? JsonArray
    ?: return CurrentCards(emptyMap(), listOf(RoutineIssue("/current/cards", "schema.type")))
  val result = linkedMapOf<String, CanonicalCard>()
  cards.forEachIndexed { index, element ->
    val path = "/current/cards/$index"
    val card = element as? JsonObject
    if (card == null) {
      issues += RoutineIssue(path, "schema.type")
      return@forEachIndexed
    }
    val id = card.string("id")
    if (id == null) {
      issues += RoutineIssue("$path/id", "schema.type")
      return@forEachIndexed
    }
    if (result.containsKey(id)) {
      issues += RoutineIssue("$path/id", "current.duplicate_id")
      return@forEachIndexed
    }
    val kind = card.string("kind")
    val title = card.string("title")
    if (kind == null) issues += RoutineIssue("$path/kind", "schema.type")
    if (title == null) issues += RoutineIssue("$path/title", "schema.type")
    if (kind == null || title == null) return@forEachIndexed
    val body = card.nullableString("body_md", path, issues)
    val hubRef = when {
      card.containsKey("hubRef") -> card.nullableString("hubRef", path, issues)
      else -> card.nullableString("hub_ref", path, issues)
    }
    val bothHubFormsDisagree = card.string("hubRef") != null && card.string("hub_ref") != null &&
      card.string("hubRef") != card.string("hub_ref")
    result[id] = CanonicalCard(
      id = id,
      kind = kind,
      title = title,
      bodyMd = body,
      hubRef = hubRef,
      hasUnexpectedContent = bothHubFormsDisagree || card.hasUnexpectedContent(),
    )
  }
  return CurrentCards(result, issues)
}

private fun JsonObject.string(key: String): String? =
  (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.nullableString(key: String, path: String, issues: MutableList<RoutineIssue>): String? {
  val value = this[key] ?: return null
  if (value is JsonNull) return null
  val string = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
  if (string == null) issues += RoutineIssue("$path/$key", "schema.type")
  return string
}

/**
 * Server-managed fields and absent/null defaults do not create a conflict. Any
 * other existing business content does: a create-only proposal must not silently
 * treat an id carrying actions, payload, timing, media, or restricted ACL state
 * as equivalent and thereby imply an overwrite.
 */
private fun JsonObject.hasUnexpectedContent(): Boolean {
  val compared = setOf("id", "kind", "title", "body_md", "hubRef", "hub_ref")
  val serverManaged = setOf(
    "family_id", "version", "created_at", "updated_at", "deleted_at", "provenance",
  )
  return entries.any { (key, value) ->
    when {
      key in compared || key in serverManaged -> false
      key == "visibility" -> value !is JsonNull && (value as? JsonPrimitive)?.contentOrNull != "family"
      key == "audience" -> value !is JsonNull && !(value is JsonArray && value.isEmpty())
      else -> when (value) {
        is JsonNull -> false
        is JsonArray -> value.isNotEmpty()
        is JsonObject -> value.isNotEmpty()
        else -> true
      }
    }
  }
}

private fun diffCreateOnly(contracts: RoutineContracts, current: Map<String, CanonicalCard>): RoutineDiffSummary {
  var create = 0
  var noChange = 0
  var conflict = 0
  contracts.changeset.operations.forEach { operation ->
    val proposed = CanonicalCard(
      id = operation.card.id,
      kind = operation.card.kind,
      title = operation.card.title,
      bodyMd = operation.card.bodyMd,
      hubRef = operation.targetHubId,
    )
    val existing = current[operation.card.id]
    when {
      existing == null -> create += 1
      existing == proposed -> noChange += 1
      else -> conflict += 1
    }
  }
  return RoutineDiffSummary(create, noChange, conflict)
}

private fun validationFailure(issues: List<RoutineIssue>): RoutineCommandResult {
  val lines = issues.distinct().sortedWith(compareBy<RoutineIssue> { it.path }.thenBy { it.code })
    .joinToString("\n") { "  ${it.path} ${it.code}" }
  return RoutineCommandResult(1, stderr = "changeset invalid:\n$lines")
}
