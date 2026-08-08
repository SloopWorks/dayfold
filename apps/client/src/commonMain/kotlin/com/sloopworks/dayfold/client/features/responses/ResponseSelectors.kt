package com.sloopworks.dayfold.client

// ADR 0064 GAP 5 — the Settings › Smart content projection.
//
// These selectors return STRUCTURED DATA, never rendered copy: the provenance as an enum and
// the author as a user id. :ui assembles every user-facing string. Returning sentences from
// here would put this feature's copy in two modules (the verb rows already live in :ui) and
// make a copy change churn the logic tests.

/**
 * Where a rule lives — distinguished in the UI by SUB-LINE COPY, never by colour alone
 * (GAP 5, first point).
 */
enum class RuleProvenance {
  /** Scoped to the viewer; the routine still mints for everyone else. */
  PERSONAL,

  /** Attributed, visible to all, removable by any adult. */
  FAMILY,

  /** A derived-lane rule enforced on-device only — never synced. */
  DEVICE,
}

data class RuleRow(
  val id: String,
  val provenance: RuleProvenance,
  /** Who set it — the byline's INPUT, not the byline. */
  val authorUserId: String,
  val label: String,
  val sublabel: String?,
  /** Optimistic write not yet synced; :ui renders the "takes effect next run" honesty. */
  val pending: Boolean,
  /** A personal rule is removable only by its owner; a family rule by any adult. */
  val removable: Boolean,
)

data class DoneRow(
  val id: String,
  val title: String,
  val authorUserId: String,
  /** The raw note, unquoted — :ui decides how to present it. */
  val note: String?,
  val pending: Boolean,
)

data class SmartContentModel(
  val mutedRules: List<RuleRow> = emptyList(),
  val doneRecords: List<DoneRow> = emptyList(),
)

fun smartContentSections(
  rules: List<ContentResponse>,
  doneRecords: List<ContentResponse>,
  viewerUserId: String?,
): SmartContentModel = SmartContentModel(
  mutedRules = rules.map { r ->
    val provenance = when {
      r.audienceScope == AudienceScope.FAMILY -> RuleProvenance.FAMILY
      else -> RuleProvenance.PERSONAL
    }
    RuleRow(
      id = r.id,
      provenance = provenance,
      authorUserId = r.createdBy,
      label = r.label,
      sublabel = r.sublabel,
      pending = r.pending,
      // Decided Q2: any adult removes a family rule; a personal rule is its owner's alone.
      removable = provenance == RuleProvenance.FAMILY || (viewerUserId != null && r.userId == viewerUserId),
    )
  },
  doneRecords = doneRecords.map { d ->
    DoneRow(
      id = d.id,
      title = d.label,
      authorUserId = d.createdBy,
      note = d.note,
      pending = d.pending,
    )
  },
)

/** Split the one synced table into the two Settings sections. */
fun smartContentSections(state: AppState): SmartContentModel {
  val all = state.responses.rules
  return smartContentSections(
    rules = all.filter { it.kind == ResponseKind.MUTE },
    doneRecords = all.filter { it.kind == ResponseKind.DONE },
    viewerUserId = state.session.session?.userId,
  )
}
