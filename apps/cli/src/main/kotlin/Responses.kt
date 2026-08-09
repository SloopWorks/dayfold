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
