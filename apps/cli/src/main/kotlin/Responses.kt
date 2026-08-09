package com.sloopworks.dayfold.cli

/**
 * ADR 0064 — the authoring path reads the family's response rules BEFORE it writes.
 *
 * The write boundary enforces suppression regardless (a muted subject 409s), but a routine
 * that only learns about a rule from a rejection cannot tell the operator anything useful: it
 * reports N failed writes instead of "skipped N muted subjects". Filtering here turns an
 * error into an expected, reportable outcome — the distinction ADR 0062 draws for run records.
 *
 * The predicate is the SAME three string equalities the server and the client use. All three
 * must agree; a divergence here shows up as a write the CLI thought it made.
 */

enum class CliResponseKind { MUTE, DONE }
enum class CliMatchScope { SUBJECT, KIND, SOURCE }

/** The subset of a response row the pre-flight needs. Labels and notes are never read. */
data class CliResponseRule(
  val id: String,
  val kind: CliResponseKind,
  val subjectRef: String,
  val matchScope: CliMatchScope,
  /** The human label — never read by the server, and the whole point for an authoring agent. */
  val label: String? = null,
  val sublabel: String? = null,
  val audienceScope: String = "family",
)

/** The subset of a changeset op the pre-flight needs. */
data class CliChangesetOp(
  val id: String,
  val subjectRef: String,
  val kind: String?,
  val source: String?,
)

data class SkippedOp(val id: String, val reason: String, val ruleId: String)

data class FilterResult(val kept: List<CliChangesetOp>, val skipped: List<SkippedOp>) {
  /** The run-receipt line: expected outcomes, not failures. */
  fun render(): String =
    if (skipped.isEmpty()) "no muted subjects"
    else "skipped ${skipped.size} muted subject${if (skipped.size == 1) "" else "s"}"
}

fun matchesRule(op: CliChangesetOp, rule: CliResponseRule): Boolean = when (rule.matchScope) {
  // EXACT equality, never prefix containment — a hub-level key is a prefix of every block
  // beneath it, so a prefix match would silently suppress a whole hub.
  CliMatchScope.SUBJECT -> op.subjectRef == rule.subjectRef
  CliMatchScope.KIND -> op.kind != null && "kind:${op.kind}" == rule.subjectRef
  CliMatchScope.SOURCE -> op.source != null && "source:${op.source}" == rule.subjectRef
}

/**
 * Drop every op a rule suppresses. Both mute and done rows suppress: a done subject is
 * resolved, so re-minting it is exactly the re-extraction the Done verb exists to stop.
 *
 * Personal-scoped rules are NOT applied here. The CLI writes for the whole family, and the
 * server handles a personal mute by stripping that member from the card's audience rather
 * than rejecting the write — skipping the op outright would suppress it for everyone.
 */
fun filterMutedOps(ops: List<CliChangesetOp>, rules: List<CliResponseRule>): FilterResult {
  if (rules.isEmpty()) return FilterResult(ops, emptyList())
  val kept = mutableListOf<CliChangesetOp>()
  val skipped = mutableListOf<SkippedOp>()
  for (op in ops) {
    val hit = rules.firstOrNull { matchesRule(op, it) }
    if (hit == null) {
      kept += op
    } else {
      skipped += SkippedOp(op.id, if (hit.kind == CliResponseKind.DONE) "done" else "muted", hit.id)
    }
  }
  return FilterResult(kept, skipped)
}


// ── Reading the family's rules before authoring ───────────────────────────────────────────
//
// The server matches by ID and never reads a label. The AGENT is the one that has to avoid
// authoring similar content in the first place, and the labels are the only semantic signal
// it gets — so `dayfold responses list` prints them, and the push pre-flight quotes them when
// it skips an op.

/** Parse GET /families/:fid/responses. Hand-rolled, matching the CLI's no-framework style. */
fun parseResponses(json: String): List<CliResponseRule> {
  val out = mutableListOf<CliResponseRule>()
  // Objects inside the "responses" array; the CLI has no JSON lib, and these are flat rows.
  val arrayStart = json.indexOf("\"responses\"")
  if (arrayStart == -1) return out
  var i = json.indexOf('[', arrayStart)
  if (i == -1) return out
  var depth = 0
  var objStart = -1
  while (i < json.length) {
    when (json[i]) {
      '{' -> { if (depth == 0) objStart = i; depth++ }
      '}' -> {
        depth--
        if (depth == 0 && objStart >= 0) {
          parseRuleObject(json.substring(objStart, i + 1))?.let { out += it }
          objStart = -1
        }
      }
      ']' -> if (depth == 0) return out
    }
    i++
  }
  return out
}

private fun field(obj: String, name: String): String? {
  val key = "\"$name\""
  val at = obj.indexOf(key)
  if (at == -1) return null
  var i = obj.indexOf(':', at + key.length)
  if (i == -1) return null
  i++
  while (i < obj.length && obj[i].isWhitespace()) i++
  if (i >= obj.length) return null
  if (obj.startsWith("null", i)) return null
  if (obj[i] != '"') {              // number / bool — read to the delimiter
    val end = obj.indexOfFirst(i) { it == ',' || it == '}' }
    return obj.substring(i, end).trim()
  }
  val sb = StringBuilder()
  i++
  while (i < obj.length) {
    val c = obj[i]
    if (c == '\\' && i + 1 < obj.length) {
      when (val n = obj[i + 1]) {
        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
        else -> sb.append(n)
      }
      i += 2
      continue
    }
    if (c == '"') break
    sb.append(c)
    i++
  }
  return sb.toString()
}

private inline fun String.indexOfFirst(from: Int, pred: (Char) -> Boolean): Int {
  var i = from
  while (i < length) { if (pred(this[i])) return i; i++ }
  return length
}

private fun parseRuleObject(obj: String): CliResponseRule? {
  val id = field(obj, "id") ?: return null
  val subjectRef = field(obj, "subject_ref") ?: return null
  val kind = if (field(obj, "kind") == "done") CliResponseKind.DONE else CliResponseKind.MUTE
  val scope = when (field(obj, "match_scope")) {
    "kind" -> CliMatchScope.KIND
    "source" -> CliMatchScope.SOURCE
    else -> CliMatchScope.SUBJECT
  }
  return CliResponseRule(
    id = id, kind = kind, subjectRef = subjectRef, matchScope = scope,
    label = field(obj, "label"), sublabel = field(obj, "sublabel"),
    audienceScope = field(obj, "audience_scope") ?: "family",
  )
}

/**
 * The brief an authoring agent reads BEFORE composing. Deliberately prose, not JSON: it is
 * consumed by a model deciding what not to write, and "don't make weather cards" is a clearer
 * instruction than a subject_ref.
 */
fun renderResponsesBrief(rules: List<CliResponseRule>, hiddenPersonal: Int): String {
  if (rules.isEmpty() && hiddenPersonal == 0) return "No response rules — nothing is muted or marked done."
  val sb = StringBuilder()
  val mutes = rules.filter { it.kind == CliResponseKind.MUTE }
  val dones = rules.filter { it.kind == CliResponseKind.DONE }
  if (mutes.isNotEmpty()) {
    sb.appendLine("DO NOT AUTHOR (${mutes.size} mute rule${if (mutes.size == 1) "" else "s"}):")
    mutes.forEach { r ->
      val what = r.label ?: r.subjectRef
      val how = when (r.matchScope) {
        CliMatchScope.KIND -> "any card of this kind"
        CliMatchScope.SOURCE -> "anything from this source"
        CliMatchScope.SUBJECT -> "this exact subject"
      }
      sb.appendLine("  - $what — $how (${r.subjectRef})")
      r.sublabel?.let { sb.appendLine("      context: $it") }
    }
  }
  if (dones.isNotEmpty()) {
    sb.appendLine("ALREADY DONE — do not re-create (${dones.size}):")
    dones.forEach { sb.appendLine("  - ${it.label ?: it.subjectRef} (${it.subjectRef})") }
  }
  if (hiddenPersonal > 0) {
    sb.appendLine(
      "Plus $hiddenPersonal personal rule${if (hiddenPersonal == 1) "" else "s"} belonging to other " +
        "members — not shown (whose is not yours to know); those members are excluded automatically.",
    )
  }
  sb.appendLine()
  sb.append("Write nothing similar to the above. A push that matches is rejected with 409 subject-muted.")
  return sb.toString()
}


/**
 * A top-level string field from an authored card body, for the pre-flight's match inputs
 * (`kind`, and `provenance.source` which the CLI stores flattened as `source`). Deliberately
 * minimal — the pre-flight needs two opaque tokens, not a JSON model.
 */
fun jsonStringField(json: String, name: String): String? = field(json, name)
