package com.sloopworks.dayfold.client

// ADR 0064 — Tier-1 synced response rows (mute + done). Mirrors the server's
// content_responses table exactly: the wire names are snake_case, the Kotlin names are
// camelCase, and each enum serializes to the same lowercase token the CHECK constraints
// allow. A drift here is not a compile error — it is a rule the server stores but never
// matches — so `wire` is the single conversion point in both directions.

enum class ResponseKind {
  MUTE, DONE;

  val wire: String get() = name.lowercase()

  companion object {
    fun of(s: String): ResponseKind = if (s == "done") DONE else MUTE
  }
}

enum class MatchScope {
  SUBJECT, KIND, SOURCE;

  val wire: String get() = name.lowercase()

  companion object {
    fun of(s: String): MatchScope = entries.firstOrNull { it.wire == s } ?: SUBJECT
  }
}

enum class AudienceScope {
  PERSONAL, FAMILY;

  val wire: String get() = name.lowercase()

  companion object {
    /** Unknown → PERSONAL. Defaulting the other way would widen a rule on a parse slip. */
    fun of(s: String): AudienceScope = if (s == "family") FAMILY else PERSONAL
  }
}

data class ContentResponse(
  val id: String,
  val kind: ResponseKind,
  val subjectRef: String,
  val matchScope: MatchScope,
  val audienceScope: AudienceScope,
  /** Owner of a personal rule; null for family rules. */
  val userId: String?,
  /** Always attributed (decided Q2) — drives "muted by Mom". */
  val createdBy: String,
  val label: String,
  val sublabel: String? = null,
  val note: String? = null,
  val version: Long = 1L,
  /** Optimistic local write not yet acked — the States-P1 offline vocabulary. */
  val pending: Boolean = false,
)

/** Which surface opened the sheet. Only the copy differs; the verbs and their order do not. */
enum class ResponseSurface { NOW, DETAIL, HUB }

enum class ResponseStep { VERBS, SCOPE, DONE_NOTE }

data class ResponseSheetState(
  val subjectRef: String,
  val subjectTitle: String,
  val reasonKind: String,
  val source: String?,
  val surface: ResponseSurface,
  val step: ResponseStep = ResponseStep.VERBS,
  val pendingMatchScope: MatchScope = MatchScope.KIND,
  // Personal is the default and the only scope the swipe path can ever reach (ADR 0064 §4).
  val pendingAudience: AudienceScope = AudienceScope.PERSONAL,
)

/** Snackbar receipt shown after an act — "snackbar + Undo at act time". */
data class ResponseReceipt(
  val responseId: String,
  val message: String,
  val undoable: Boolean,
  val offline: Boolean,
)

data class ResponseState(
  val rules: List<ContentResponse> = emptyList(),
  val sheet: ResponseSheetState? = null,
  val lastReceipt: ResponseReceipt? = null,
)
