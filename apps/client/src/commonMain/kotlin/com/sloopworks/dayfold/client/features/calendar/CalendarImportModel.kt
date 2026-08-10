package com.sloopworks.dayfold.client

// CAL-10 (ADR 0063 §6, specs/calendar-import-contract-design.md) — the reviewed Calendar→Dayfold
// import: a member-reviewed proposal, never a background sync. Every type here mirrors the
// contract spec's §1 "the proposal type" section exactly — the field set IS the normalization
// boundary. Attendees/guests, meeting links, organizer identity, calendar account/provider ids,
// reminder config, the platform calendar/event id, recurrence id, and the reconciler's fingerprint
// are ABSENT, not filtered: there is no field on any of these types that could carry them (spec
// §1.2, ADR 0063 gate 6). The matching state the reconciler needs stays in CalendarBinding, joined
// to a proposal only by proposalId — never embedded here.

/** spec §1.1 — a closed union, not a nullable `allDay: Boolean` beside a timestamp, so "all-day
 *  date" and "instant" are non-confusable at the type level (mirrors Stop.at's convention). */
sealed interface EventInstant {
  data class Timed(val instant: String) : EventInstant   // RFC-3339 with offset
  data class AllDay(val date: String) : EventInstant      // YYYY-MM-DD
}

/** The wire value carried in a hub/block field for this instant — a bare string either way. */
val EventInstant.wire: String get() = when (this) {
  is EventInstant.Timed -> instant
  is EventInstant.AllDay -> date
}

/** spec §1.1 — structured only; never a free-text venue blob. */
data class StructuredLocation(val label: String, val address: String? = null)

/** spec §2 — visibility choice on a NEW Hub the import creates. Existing-Hub imports never touch
 *  visibility (§2.2) — the destination's own visibility is inherited unmodified. */
enum class HubVisibilityChoice {
  FAMILY, RESTRICTED;

  val wire: String get() = name.lowercase()
}

/** spec §2 — `NewHub(audience)` | `ExistingHub(hubId)`. [ImportDestination.ExistingHub] carries
 *  denormalized display fields the destination picker already has (title, the caller's own ADR
 *  0053 role) purely for UI/label purposes — they are never sent on the wire (§3.1 only ever PUTs
 *  `hubId` as a reference); the write never re-derives or re-asserts them. */
sealed interface ImportDestination {
  /** spec §2.1 — restricted-to-importer is the default; family/named-audience is the explicit
   *  audience-step choice, never inferred from the source calendar's own sharing. */
  data class NewHub(
    val visibility: HubVisibilityChoice = HubVisibilityChoice.RESTRICTED,
    val audience: List<String> = emptyList(),
  ) : ImportDestination

  /** spec §2.2 — only offered when [participationRole] ∈ {contributor, co_owner} or the caller
   *  authored the hub; the server independently re-enforces this (hubWriteGate) so a stale client
   *  list can never become a write. */
  data class ExistingHub(
    val hubId: String,
    val hubTitle: String,
    val participationRole: String,
  ) : ImportDestination
}

/**
 * spec §1.1 "Shape" — the ONLY representation that crosses from a calendar observation into the
 * import flow. [destination] is null until the destination step resolves it; every other field is
 * fixed at review-start (device randomness mints [proposalId]) except [description], which the
 * member may opt into on the fields step (spec OD-1, default off), and the fields the member edits
 * before applying (title/location).
 */
data class CalendarImportProposal(
  val proposalId: String,
  val title: String,
  val start: EventInstant,
  val end: EventInstant?,
  val timezone: String?,
  val location: StructuredLocation?,
  val description: String? = null,
  val destination: ImportDestination? = null,
)

/**
 * spec §3.3 — the target ids minted ONCE at confirm from device randomness and persisted in the
 * local proposal record. Every retry/re-flush/restart/re-review reuses the SAME ids, which is what
 * makes upsert-keyed convergence (not server dedupe) the whole idempotency story. Ids are NEVER
 * derived from calendar data (a hash of a platform event id would be a stable, server-visible
 * correlator — banned by spec §4.1).
 */
data class ImportOpIds(
  val hubId: String?,           // present only for a NewHub destination
  val sectionId: String,
  val blockIds: List<String>,   // milestone [, location] [, markdown] — matches materialize() order
)

/** spec §3.5 — the 7 apply states, plus the pre-apply wizard steps. A sealed hierarchy so the
 *  reducer's `when` stays exhaustive and the UI can render each apply state as its own screen
 *  (Import.dc.html §27). [ImportProposalState.None] is the machine's rest state — no active import. */
sealed interface ImportProposalState {
  data object None : ImportProposalState
  data class ChoosingDestination(val proposal: CalendarImportProposal) : ImportProposalState
  data class PreviewingFields(val proposal: CalendarImportProposal) : ImportProposalState
  data class ChoosingAudience(val proposal: CalendarImportProposal) : ImportProposalState
  data class Confirming(val proposal: CalendarImportProposal) : ImportProposalState

  /** state 1 — ops enqueued, drain in flight. "Your calendar event is untouched." */
  data class Applying(val proposal: CalendarImportProposal, val ids: ImportOpIds) : ImportProposalState

  /** state 2 — every op in the chain Acked; [audienceSummary] is the ACTUAL resulting audience
   *  ("only you can see it" / a named/family line), never a generic success message. */
  data class Saved(val proposal: CalendarImportProposal, val audienceSummary: String) : ImportProposalState

  /** state 3 — no connectivity at confirm (or the sender is backed off before the chain starts).
   *  [ids] is minted regardless of connectivity (spec §3.3 — ids are a confirm-time fact); what's
   *  withheld offline is enqueueing the ops themselves (spec: "no ops are enqueued yet"). */
  data class OfflineQueued(val proposal: CalendarImportProposal, val ids: ImportOpIds?) : ImportProposalState

  /** state 4 — calendar access was revoked/restricted before the chain enqueued. Hold, never
   *  guess: the source cannot be revalidated, so nothing may be applied from an unverifiable copy. */
  data class PermissionLost(val proposal: CalendarImportProposal) : ImportProposalState

  /** state 5 — revalidation (or a drained op) found the role denies the write. The review is kept
   *  — fields/opt-ins/ids survive — only [CalendarImportProposal.destination] is cleared, so the
   *  member re-opens destination choice rather than being silently retargeted. */
  data class RoleDenied(val proposal: CalendarImportProposal) : ImportProposalState

  /** state 6 — the source event's fingerprint changed since the review-start snapshot. Ids are
   *  retained: a re-confirm overwrites rather than duplicating. */
  data class SourceChanged(
    val proposal: CalendarImportProposal,
    val ids: ImportOpIds,
    val diffs: List<ImportFieldDiff>,
  ) : ImportProposalState

  /** state 7 — the destination Hub's version or resolved audience changed since confirm-time.
   *  [audiencePreview] is the CURRENT named audience, re-presented for a required re-confirm. */
  data class VersionConflict(
    val proposal: CalendarImportProposal,
    val ids: ImportOpIds,
    val audiencePreview: List<String>,
  ) : ImportProposalState
}

/** One field's before/after for the state-6 "what changed" panel. */
data class ImportFieldDiff(val field: String, val before: String, val after: String)
