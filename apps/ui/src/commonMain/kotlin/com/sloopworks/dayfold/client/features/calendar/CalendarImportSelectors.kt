package com.sloopworks.dayfold.client.features.calendar

import com.sloopworks.dayfold.client.CalendarImportProposal
import com.sloopworks.dayfold.client.EventInstant
import com.sloopworks.dayfold.client.ImportDestination
import com.sloopworks.dayfold.client.formatMetaWhen

// CAL-10 (ADR 0063 §6, calendar-import-contract-design.md) — pure UI viewstate over an in-progress
// CalendarImportProposal. Mirrors CalendarSelectors.kt: no DB/network here, only reshaping already-
// loaded state for the wizard screens in this package.

/** A human date/time line for the confirm card and field-preview row, e.g. "Sun, Jun 28 · 12:00 PM". */
fun EventInstant.displayLine(): String = when (this) {
  is EventInstant.Timed -> formatMetaWhen(instant) ?: instant
  is EventInstant.AllDay -> date
}

fun CalendarImportProposal.dateTimeLine(): String {
  val start = start.displayLine()
  val end = end?.displayLine()
  return if (end != null) "$start – $end" else start
}

/** How many fields go to Dayfold (spec §1.4 field-count line on Import.dc.html §24/§26): title +
 *  date/time + timezone (when present) + location (when present) — description is opt-in and
 *  counted only when included. */
fun CalendarImportProposal.approvedFieldCount(): Int =
  2 + (if (timezone != null) 1 else 0) + (if (location != null) 1 else 0) + (if (description != null) 1 else 0)

data class ImportDestinationRow(val hubId: String, val title: String, val role: String)

/** §26 confirm card's audience line — the ACTUAL resulting audience, never a generic "shared". */
fun ImportDestination?.audienceLine(): String = when (this) {
  null -> ""
  is ImportDestination.NewHub -> when {
    audience.size <= 1 -> "New Hub · Only me"
    else -> "New Hub · Shared with ${audience.size} people"
  }
  is ImportDestination.ExistingHub -> "Adding to: $hubTitle"
}
