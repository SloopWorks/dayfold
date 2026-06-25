package com.sloopworks.dayfold.client

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime

// Friendly briefing date, e.g. "Thursday, June 25". Pure (takes a LocalDate) so the
// composable injects today via the clock+timezone and tests pass a fixed date.
fun formatDayLabel(date: LocalDate): String {
  fun title(s: String) = s.lowercase().replaceFirstChar { it.uppercase() }
  return "${title(date.dayOfWeek.name)}, ${title(date.month.name)} ${date.day}"
}

// Calm countdown labels for the Hubs surface (ADR 0006). The API serves DB-shaped
// timestamptz strings ("2026-06-24 07:23:51.41-07"), not ISO — normalize, then
// diff against now. Pure + nowIso/tz-injectable so it's testable without a clock.
// CALENDAR-day math (local dates), not elapsed hours: an event at 6am tomorrow
// reads "Tomorrow" even at 8pm tonight (10h away), as a person would expect.

internal fun normalizeTs(s: String?): String? {
  if (s.isNullOrBlank()) return null
  var t = s.trim().replace(' ', 'T')
  // ensure a parseable tz offset: "…-07" → "…-07:00"; "…+0530" → "…+05:30"; "Z" ok.
  val tz = Regex("([+-])(\\d{2})(\\d{2})?$").find(t)
  if (tz != null) {
    val (sign, hh, mm) = tz.destructured
    t = t.substring(0, tz.range.first) + "$sign$hh:${if (mm.isEmpty()) "00" else mm}"
  }
  return t
}

private fun parseOrNull(s: String?): Instant? = normalizeTs(s)?.let { runCatching { Instant.parse(it) }.getOrNull() }
private fun parseDate(s: String?, tz: TimeZone): LocalDate? = parseOrNull(s)?.toLocalDateTime(tz)?.date

// The "when" badge for an event hub. An explicit countdown_to wins; otherwise, for
// a start/end span, show "Now" while it's in progress (so an active vacation reads
// "Now", not "Yesterday"), count down before it, and count up after it. Calendar-day
// based via [countdownLabel]. Returns null when there's no date at all.
fun hubWhenLabel(
  countdownTo: String?, startAt: String?, endAt: String?, nowIso: String,
  tz: TimeZone = TimeZone.currentSystemDefault(),
): String? {
  if (countdownTo != null) return countdownLabel(countdownTo, nowIso, tz)
  if (startAt == null) return null
  val start = parseDate(startAt, tz); val end = parseDate(endAt, tz); val now = parseDate(nowIso, tz)
  if (start != null && end != null && now != null) {
    return when {
      now < start -> countdownLabel(startAt, nowIso, tz)   // upcoming
      now <= end -> "Now"                                   // in progress
      else -> countdownLabel(endAt, nowIso, tz)             // ended → "N days ago"
    }
  }
  return countdownLabel(startAt, nowIso, tz)
}

// "Today" | "Tomorrow" | "in N days" | "Yesterday" | "N days ago" | null.
// targetIso = the hub's countdown_to ?: start_at; nowIso = an ISO/DB now. Compared
// as LOCAL CALENDAR dates (tz, default system) so the label matches the wall date.
fun countdownLabel(targetIso: String?, nowIso: String, tz: TimeZone = TimeZone.currentSystemDefault()): String? {
  val target = parseOrNull(targetIso)?.toLocalDateTime(tz)?.date ?: return null
  val now = parseOrNull(nowIso)?.toLocalDateTime(tz)?.date ?: return null
  val days = now.daysUntil(target)
  return when {
    days == 0 -> "Today"
    days == 1 -> "Tomorrow"
    days > 1 -> "in $days days"
    days == -1 -> "Yesterday"
    else -> "${-days} days ago"
  }
}
