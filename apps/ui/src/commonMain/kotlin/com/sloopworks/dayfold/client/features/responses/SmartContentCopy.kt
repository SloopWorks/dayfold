package com.sloopworks.dayfold.client

// ADR 0064 GAP 5 — every user-facing string for Settings › Smart content lives here, in :ui.
// The :client selectors return structured data (a provenance enum, a user id); this file turns
// it into sentences. One module owns the copy for this feature — the verb rows already do.

/**
 * The three rule provenances are distinguished by SUB-LINE COPY, never by colour alone.
 * A pending rule overrides the sub-line with the offline honesty: a rule cannot stop a run
 * that already happened.
 */
fun sublineFor(row: RuleRow, memberNames: Map<String, String>): String = when {
  row.pending -> "Saved on this phone — syncs & takes effect next run"
  row.provenance == RuleProvenance.PERSONAL ->
    listOfNotNull(row.sublabel, "just you").joinToString(" · ")
  row.provenance == RuleProvenance.FAMILY ->
    "Whole family · muted by ${memberNames[row.authorUserId] ?: "a family member"} · any adult can remove"
  else -> "On this device · derived lane · never synced"
}

/**
 * The Done byline. The note is quoted when there is one — it is context twice over: for the
 * family (how it was resolved) and for the next run (completion plus note read as context,
 * not just suppression).
 */
fun doneSublineFor(row: DoneRow, memberNames: Map<String, String>): String {
  val who = memberNames[row.authorUserId] ?: "a family member"
  return if (row.note.isNullOrBlank()) who else "\"${row.note}\" · $who"
}

/**
 * The content-blindness reassurance, verbatim from the design. It is a promise the code keeps
 * literally: suppression is three string equalities on opaque identifier columns.
 */
const val SMART_CONTENT_PRIVACY_NOTE: String =
  "Your routine reads these before it adds anything. The server checks by ID — it never reads your content."

const val SMART_CONTENT_MUTED_HEADER: String = "MUTED RULES"
const val SMART_CONTENT_DONE_HEADER: String = "DONE"
const val SMART_CONTENT_OFFLINE_BANNER: String = "You're offline — responses are saved and will sync"
