package com.sloopworks.dayfold.client

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// spec §1.4/§3.1/§6 build-step-2 — materialization + op composition. Pure: no DB/network/ids-
// minting side effects, so it is unit-testable headlessly. One proposal materializes into exactly
// one section plus 1-3 blocks, IDENTICALLY for both destinations (spec pass-2 simplification #3 —
// one materializer, not a per-destination split).

/** spec OD-3 — the bounded template-catalog key for an imported event. */
const val CALENDAR_IMPORT_HUB_TYPE = "event"

/** spec §1.4 — the section every import lands in, on both destinations. */
const val CALENDAR_IMPORT_SECTION_TITLE = "From your calendar"

/** spec §3.1 — one row per enqueued op, exactly what ContentStore.enqueueImportOps writes to the
 *  outbox. `dependsOn` forms one causal chain through the terminal block: it both gates send
 *  readiness and lets a parent failure cascade-drop every write that has not happened yet. */
data class MaterializedOp(
  val opId: String,
  val targetKind: String,   // "hub" | "section" | "block"
  val targetId: String,
  val type: String,         // "upsertHub" | "upsertSection" | "upsertBlock"
  val payload: String,      // the JSON PUT body
  val dependsOn: String?,
)

/**
 * spec §1.4/§3.1 — composes the existing `upsertHub`/`upsertSection`/`upsertBlock` ops (no new op
 * type, no new endpoint). `existingSectionId`, when non-null, reuses the destination Hub's last
 * live section instead of creating a new one (OD-6) — the caller resolves that DB read, keeping
 * this function pure. `base_version` is always null: every op targets a client-minted,
 * not-yet-existing row (spec §3.4 — the Hub-grain precondition lives in the caller's revalidation,
 * never in an op's own base_version).
 */
fun materialize(
  proposal: CalendarImportProposal,
  destination: ImportDestination,
  ids: ImportOpIds,
  nowIso: String,
  opIdProvider: () -> String,
  existingSectionId: String? = null,
): List<MaterializedOp> {
  val ops = mutableListOf<MaterializedOp>()

  var hubOpId: String? = null
  if (destination is ImportDestination.NewHub) {
    hubOpId = opIdProvider()
    ops += MaterializedOp(
      opId = hubOpId, targetKind = "hub", targetId = requireNotNull(ids.hubId) { "NewHub destination requires ids.hubId" },
      type = "upsertHub", payload = hubPutBody(ids.hubId, proposal, destination),
      dependsOn = null,
    )
  }

  val hubIdForSection = when (destination) {
    is ImportDestination.NewHub -> requireNotNull(ids.hubId)
    is ImportDestination.ExistingHub -> destination.hubId
  }
  val sectionTargetId = existingSectionId ?: ids.sectionId
  val sectionOpId = if (existingSectionId != null) null else opIdProvider()
  if (sectionOpId != null) {
    ops += MaterializedOp(
      opId = sectionOpId, targetKind = "section", targetId = sectionTargetId,
      type = "upsertSection", payload = sectionPutBody(sectionTargetId, hubIdForSection),
      dependsOn = hubOpId,
    )
  }

  // The blocks are a LINEAR chain, not siblings. Besides making cascade-drop complete, this makes
  // the final block a trustworthy commit marker: acking it proves every reviewed field was acked.
  // This must not rely on op-id ordering because ULIDs minted within one millisecond have random
  // tails and every row in one import intentionally shares the same created_at.
  var predecessorOpId = sectionOpId ?: hubOpId
  var idx = 0
  fun appendBlock(payload: String) {
    val opId = opIdProvider()
    ops += MaterializedOp(
      opId = opId, targetKind = "block", targetId = ids.blockIds[idx++],
      type = "upsertBlock", payload = payload, dependsOn = predecessorOpId,
    )
    predecessorOpId = opId
  }
  appendBlock(milestoneBlockBody(sectionTargetId, proposal, nowIso))
  if (proposal.location != null) {
    appendBlock(locationBlockBody(sectionTargetId, proposal.location, nowIso))
  }
  return ops
}

/** How many block ids [materialize] will need for [proposal] — the caller mints exactly this many
 *  at confirm time before calling [materialize]. */
fun importBlockCount(proposal: CalendarImportProposal): Int =
  1 + (if (proposal.location != null) 1 else 0)

// ── PUT body builders — spec §4.1's exact allowed key sets. Nothing calendar-derived (platform
// event/calendar id, account name, fingerprint, recurrence id) is ever a key here — there is no
// variable in scope that holds one; the proposal type itself cannot carry them (spec §1.2). ──

private fun hubPutBody(hubId: String, proposal: CalendarImportProposal, destination: ImportDestination.NewHub): String =
  buildJsonObject {
    put("id", hubId)
    put("type", CALENDAR_IMPORT_HUB_TYPE)
    put("title", proposal.title)
    put("visibility", destination.visibility.wire)
    if (destination.visibility == HubVisibilityChoice.RESTRICTED) {
      putJsonArray("audience") { destination.audience.forEach { add(JsonPrimitive(it)) } }
    }
    put("start_at", proposal.start.wire)
    proposal.end?.let { put("end_at", it.wire) }
  }.toString()

private fun sectionPutBody(sectionId: String, hubId: String): String =
  buildJsonObject {
    put("hubId", hubId)
    put("id", sectionId)
    put("title", CALENDAR_IMPORT_SECTION_TITLE)
  }.toString()

/** spec §1.3 — the fixed literal `"calendar"`; the calendar's display name stays device-local
 *  (rendered pre-write from the local binding, never sent). `at` is the device apply clock, never
 *  the calendar event's own created/updated time (a weak per-event correlator). */
private fun calendarProvenance(nowIso: String) = buildJsonObject {
  put("source", "calendar")
  put("at", nowIso)
}

private fun milestoneBlockBody(sectionId: String, proposal: CalendarImportProposal, nowIso: String): String =
  buildJsonObject {
    put("sectionId", sectionId)
    put("type", "milestone")
    putJsonObject("payload") {
      put("date", proposal.start.wire)
      put("label", proposal.title)
      proposal.end?.let { put("end", it.wire) }
      proposal.timezone?.let { put("tz", it) }
    }
    if (proposal.start is EventInstant.Timed) {
      putJsonArray("triggers") {
        add(buildJsonObject { putJsonObject("when") { put("fact_ref", "payload:milestone") } })
      }
    }
    put("provenance", calendarProvenance(nowIso))
  }.toString()

private fun locationBlockBody(sectionId: String, location: StructuredLocation, nowIso: String): String =
  buildJsonObject {
    put("sectionId", sectionId)
    put("type", "location")
    putJsonObject("payload") {
      put("label", location.label)
      location.address?.let { put("address", it) }
    }
    put("provenance", calendarProvenance(nowIso))
  }.toString()
