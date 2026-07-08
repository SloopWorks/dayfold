package com.sloopworks.dayfold.client

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
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

// Friendly label for an authored timestamp in a DETAILS row / hero panel (leaveBy,
// rsvpBy, startAt, closesAt, modified, email date). Shows the AUTHORED wall-clock —
// e.g. leaveBy "2026-07-08T09:25:00-07:00" → "Jul 8, 9:25 AM" — so an appointment
// reads in ITS OWN zone, not the viewer's. A time component → "Mon D, h:mm AM";
// date-only ("2026-06-18") → "Mon D". Null/blank → null; any other shape passes
// through unchanged (never blanks an authored value). Pure — no clock/tz needed
// (the offset is read off the string, then dropped to show the local wall time).
private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private fun shortDate(d: LocalDate): String = "${MONTHS[d.month.ordinal]} ${d.day}"
private fun clockTime(t: LocalTime): String {
  val meridiem = if (t.hour < 12) "AM" else "PM"
  val h12 = ((t.hour + 11) % 12) + 1                     // 0→12, 13→1, 23→11
  return "$h12:${t.minute.toString().padStart(2, '0')} $meridiem"
}
fun formatMetaWhen(iso: String?): String? {
  val raw = iso?.trim()?.ifBlank { null } ?: return null
  // date-only "YYYY-MM-DD" first: normalizeTs would misread the day ("-18") as a tz
  // offset, so parse it directly to a date (no time component to show).
  if (Regex("""^\d{4}-\d{2}-\d{2}$""").matches(raw)) {
    return runCatching { LocalDate.parse(raw) }.getOrNull()?.let { shortDate(it) } ?: raw
  }
  val t = normalizeTs(raw) ?: return raw
  // read the local wall-clock: strip the trailing offset / Z (only needed for an
  // absolute instant, not to show the authored local time).
  val local = t.substringBefore('Z').let { Regex("([+-]\\d{2}:\\d{2})$").replace(it, "") }
  runCatching { LocalDateTime.parse(local) }.getOrNull()?.let { return "${shortDate(it.date)}, ${clockTime(it.time)}" }
  return iso   // unrecognized shape → the original authored string, unchanged
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
