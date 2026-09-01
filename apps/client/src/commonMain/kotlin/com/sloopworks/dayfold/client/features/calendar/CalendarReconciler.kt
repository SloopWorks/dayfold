package com.sloopworks.dayfold.client

import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

// ADR 0063 §4 — the deterministic, conservative matching ladder. Pure: candidates, observations,
// bindings, and the clock are all injected (mirrors deriveNow/deriveEventCandidates) — no wall-
// clock, no randomness, no I/O. CalendarCheckEngine owns every DB/port read this needs; the
// reducer that consumes [ReconcileResult] stays pure too.
//
// The ladder, per candidate:
//  a. still-valid explicit binding — the bound platform event is still observed. If its fields
//     still fingerprint-match the candidate: matched, no gap. If they've drifted: details-differ
//     (never a silent overwrite).
//  b. strict deterministic fingerprint over EVERY unclaimed observation. Exactly one hit → auto-
//     bind. Multiple hits → ambiguous, never auto-bound.
//  c. a recurring observation that would otherwise satisfy (a)/(b) is deliberately never auto-
//     bound (first-slice limit, ADR 0063 §4) — it surfaces as a single-occurrence notice instead.
//  d. a high-confidence suggestion (same start instant + significant title token overlap) is
//     offered for confirmation — it never auto-selects, never writes, never suppresses the gap.
//  e. otherwise unresolved → a dayfold-only or calendar-only gap.

object CalendarReconciler {
  fun reconcile(
    candidates: List<DayfoldEventCandidate>,
    observations: List<CalendarEventObservation>,
    bindings: List<CalendarBinding>,
    nowInstant: Instant,
  ): ReconcileResult {
    val nowIso = nowInstant.toString()
    val bindingBySubject = bindings.associateBy { it.subjectKey }
    val obsById = observations.associateBy { it.platformEventId }
    val consumed = mutableSetOf<String>()
    val resolved = mutableSetOf<String>()

    val autoBindings = mutableListOf<CalendarBinding>()
    val differs = mutableListOf<DetailsDiffer>()
    val recurringNotices = mutableListOf<RecurringNotice>()
    val ambiguous = mutableListOf<AmbiguousMatch>()
    val suggested = mutableListOf<SuggestedMatch>()
    val dayfoldOnly = mutableListOf<DayfoldEventCandidate>()

    // ── Rung a: still-valid explicit bindings, resolved first so a bound event can never also
    // be claimed by rung b/c/d for a different subject. ──
    for (c in candidates) {
      val binding = bindingBySubject[c.subjectKey] ?: continue
      if (binding.relation != CalendarRelation.MATCHED) continue
      val platformEventId = binding.platformEventId ?: continue
      val obs = obsById[platformEventId] ?: continue // event no longer observed — not "still valid"
      resolved += c.subjectKey
      consumed += obs.platformEventId
      val observationFingerprint = fingerprintOfObservation(obs)
      when {
        obs.isRecurring -> recurringNotices += RecurringNotice(c, obs)
        observationFingerprint == fingerprintOfCandidate(c) -> autoBindings += binding.copy(
          sourceVersion = c.sourceVersion,
          fingerprint = observationFingerprint,
          lastSeenAt = nowIso,
          relation = CalendarRelation.MATCHED,
          updatedAt = nowIso,
        )
        else -> {
          val alreadyResolved = if (
            binding.sourceVersion == c.sourceVersion && binding.fingerprint == observationFingerprint
          ) resolvedCalendarFields(binding.reviewState) else emptySet()
          val pendingDiffs = diffFields(c, obs).filterNot { it.field in alreadyResolved }
          if (pendingDiffs.isEmpty()) {
            autoBindings += binding.copy(lastSeenAt = nowIso, updatedAt = nowIso)
          } else {
            differs += DetailsDiffer(c.subjectKey, c, obs, pendingDiffs)
          }
        }
      }
    }

    // ── Rungs b–e: everything not already resolved by an explicit binding. ──
    for (c in candidates) {
      if (c.subjectKey in resolved) continue
      val rejectedEventId = bindingBySubject[c.subjectKey]
        ?.takeIf { it.reviewState == "keep_separate" }
        ?.platformEventId
      val available = observations.filter { it.platformEventId !in consumed }
      val cfp = fingerprintOfCandidate(c)
      val strictHits = available.filter { it.platformEventId != rejectedEventId && fingerprintOfObservation(it) == cfp }

      when {
        strictHits.size == 1 -> {
          val obs = strictHits.single()
          consumed += obs.platformEventId
          if (obs.isRecurring) {
            recurringNotices += RecurringNotice(c, obs)
          } else {
            autoBindings += CalendarBinding(
              subjectKey = c.subjectKey,
              sourceVersion = c.sourceVersion,
              platformEventId = obs.platformEventId,
              calendarId = obs.calendarId,
              fingerprint = cfp,
              lastSeenAt = nowIso,
              relation = CalendarRelation.MATCHED,
              notificationOwner = CalendarNotificationOwner.CALENDAR,
              reviewState = null,
              createdAt = nowIso,
              updatedAt = nowIso,
            )
          }
        }
        strictHits.size > 1 -> {
          strictHits.forEach { consumed += it.platformEventId }
          ambiguous += AmbiguousMatch(c, strictHits)
        }
        else -> {
          // A recurring occurrence never strict-fingerprint-matches (its fingerprint carries a
          // non-null recurrence identity a candidate's never has) — check title+start correspondence
          // explicitly so a recurring series is never silently left out as a plain gap either.
          val recurringMatch = available.firstOrNull {
            it.isRecurring && it.startAt == c.startAt && normalizeTitle(it.title) == normalizeTitle(c.title)
          }
          if (recurringMatch != null) {
            consumed += recurringMatch.platformEventId
            recurringNotices += RecurringNotice(c, recurringMatch)
          } else {
            val suggestions = available.filter {
              it.platformEventId != rejectedEventId &&
                it.startAt == c.startAt && significantTitleOverlap(it.title, c.title)
            }
            if (suggestions.isNotEmpty()) {
              // A suggestion is surfaced as ITS OWN review item, not also a duplicate calendar-only
              // gap for the same observation — but it never writes/binds/suppresses (ADR 0063 §4c):
              // no autoBindings entry, and it stays a suggestion across passes until the user acts.
              suggestions.forEach { obs -> consumed += obs.platformEventId; suggested += SuggestedMatch(c, obs, evidenceFor(c, obs)) }
            } else {
              dayfoldOnly += c
            }
          }
        }
      }
    }

    val allClaimed = consumed + ambiguous.flatMap { am -> am.observations.map { it.platformEventId } }
    val calendarOnly = observations.filter { it.platformEventId !in allClaimed }

    return ReconcileResult(
      dayfoldOnly = dayfoldOnly,
      calendarOnly = calendarOnly,
      suggested = suggested,
      ambiguous = ambiguous,
      differs = differs,
      recurringNotices = recurringNotices,
      autoBindings = autoBindings,
    )
  }
}

// ADR 0063 §5 — items surfaced for the one calm in-app review, plus the auto-bindings the engine
// should persist (rung a refresh / rung b new bind). Matched-and-unchanged subjects appear in
// neither review bucket — there is nothing to review.
data class ReconcileResult(
  val dayfoldOnly: List<DayfoldEventCandidate> = emptyList(),
  val calendarOnly: List<CalendarEventObservation> = emptyList(),
  val suggested: List<SuggestedMatch> = emptyList(),
  val ambiguous: List<AmbiguousMatch> = emptyList(),
  val differs: List<DetailsDiffer> = emptyList(),
  val recurringNotices: List<RecurringNotice> = emptyList(),
  val autoBindings: List<CalendarBinding> = emptyList(),
) {
  /** The candidate behind a subjectKey-addressed review item, if [subjectKey] currently has one. */
  fun candidateFor(subjectKey: String): DayfoldEventCandidate? =
    suggested.firstOrNull { it.candidate.subjectKey == subjectKey }?.candidate
      ?: ambiguous.firstOrNull { it.candidate.subjectKey == subjectKey }?.candidate
      ?: differs.firstOrNull { it.subjectKey == subjectKey }?.candidate
      ?: recurringNotices.firstOrNull { it.candidate.subjectKey == subjectKey }?.candidate

  /** Drops every review item addressed by [subjectKey] (a confirm/resolve/dismiss verb). */
  fun withoutSubject(subjectKey: String): ReconcileResult = copy(
    dayfoldOnly = dayfoldOnly.filterNot { it.subjectKey == subjectKey },
    suggested = suggested.filterNot { it.candidate.subjectKey == subjectKey },
    ambiguous = ambiguous.filterNot { it.candidate.subjectKey == subjectKey },
    differs = differs.filterNot { it.subjectKey == subjectKey },
    recurringNotices = recurringNotices.filterNot { it.candidate.subjectKey == subjectKey },
  )

  /** IgnoreItem addressing — [itemKey] is a subjectKey, or "calendarEvent:<platformEventId>" for
   *  a bare calendar-only observation (which has no subjectKey to key on). */
  fun withoutItemKey(itemKey: String): ReconcileResult {
    val calendarEventId = itemKey.removePrefix(CALENDAR_EVENT_ITEM_KEY_PREFIX)
    return if (calendarEventId != itemKey) {
      copy(calendarOnly = calendarOnly.filterNot { it.platformEventId == calendarEventId })
    } else {
      withoutSubject(itemKey)
    }
  }

  /** FieldChoice resolution — drops [field] from a details-differ subject; drops the whole entry
   *  once every field is resolved. */
  fun resolveField(subjectKey: String, field: String): ReconcileResult = copy(
    differs = differs.mapNotNull { d ->
      if (d.subjectKey != subjectKey) return@mapNotNull d
      val remaining = d.diffs.filterNot { it.field == field }
      if (remaining.isEmpty()) null else d.copy(diffs = remaining)
    },
  )
}

const val CALENDAR_EVENT_ITEM_KEY_PREFIX = "calendarEvent:"

fun calendarOnlyItemKey(platformEventId: String): String = "$CALENDAR_EVENT_ITEM_KEY_PREFIX$platformEventId"

data class SuggestedMatch(
  val candidate: DayfoldEventCandidate,
  val observation: CalendarEventObservation,
  val evidence: List<String>,
)

data class AmbiguousMatch(
  val candidate: DayfoldEventCandidate,
  val observations: List<CalendarEventObservation>,
)

data class FieldDiff(
  val field: String, // "title" | "start" | "end" | "location"
  val dayfoldValue: String?,
  val calendarValue: String?,
  val calendarWriteSupported: Boolean = true,
)

private const val RESOLVED_CALENDAR_FIELDS_PREFIX = "fields:"

internal fun resolvedCalendarFields(reviewState: String?): Set<String> =
  reviewState?.takeIf { it.startsWith(RESOLVED_CALENDAR_FIELDS_PREFIX) }
    ?.removePrefix(RESOLVED_CALENDAR_FIELDS_PREFIX)
    ?.split(',')?.filter(String::isNotBlank)?.toSet().orEmpty()

internal fun calendarFieldsReviewState(fields: Set<String>): String =
  RESOLVED_CALENDAR_FIELDS_PREFIX + fields.sorted().joinToString(",")

data class DetailsDiffer(
  val subjectKey: String,
  val candidate: DayfoldEventCandidate,
  val observation: CalendarEventObservation,
  val diffs: List<FieldDiff>,
)

// A recurring observation resolved against a candidate — surfaced as a notice, never a match
// (ADR 0063 §4 first-slice limit: no series-level binding yet).
data class RecurringNotice(
  val candidate: DayfoldEventCandidate,
  val observation: CalendarEventObservation,
)

// ── Horizon bounding (ADR 0063 §5) — the engine applies this to candidates before reconciling;
// CalendarPort.observeEvents bounds observations at the source. Both use the single
// CALENDAR_CHECK_HORIZON_DAYS constant — never a second literal. ──
fun candidatesInHorizon(
  candidates: List<DayfoldEventCandidate>,
  nowInstant: Instant,
  horizonDays: Int,
  zone: TimeZone = TimeZone.currentSystemDefault(),
): List<DayfoldEventCandidate> {
  val cutoff = nowInstant + horizonDays.days
  val today = nowInstant.toLocalDateTime(zone).date
  return candidates.filter { c ->
    val start = parseInstantFlexible(c.startAt, zone) ?: return@filter true
    if (start > cutoff) return@filter false
    val end = c.endAt?.let { parseInstantFlexible(it, zone) }
    when {
      end != null -> end >= nowInstant
      c.allDay -> start.toLocalDateTime(zone).date >= today
      else -> start >= nowInstant
    }
  }
}

// ── Fingerprinting (ADR 0063 §4) — normalized title + start/end + all-day + timezone +
// recurrence identity + structured location. A candidate's recurrence identity is always null
// (DayfoldEventCandidate has no recurrence concept), so it strict-matches only a genuinely
// one-off observation (recurrenceId == null) — a recurring observation's fingerprint never
// equals a candidate's, which is what keeps rung b from ever auto-binding a series occurrence. ──

internal fun fingerprintOfCandidate(c: DayfoldEventCandidate): String =
  strictFingerprint(c.title, c.startAt, c.endAt, c.allDay, c.timezone, null, c.location)

internal fun fingerprintOfObservation(o: CalendarEventObservation): String =
  strictFingerprint(o.title, o.startAt, o.endAt, o.allDay, o.timezone, o.recurrenceId, o.location)

private fun strictFingerprint(
  title: String,
  startAt: String,
  endAt: String?,
  allDay: Boolean,
  timezone: String,
  recurrenceIdentity: String?,
  location: CandidateLocation?,
): String = stableFingerprint(
  // Android CalendarContract represents all-day rows in UTC while EventKit commonly reports the
  // device/event zone. A bare calendar date has no wall-clock instant, so those provider labels
  // are representation metadata, not a meaningful scheduling difference.
  normalizeTitle(title), startAt, endAt, allDay.toString(), if (allDay) "all-day" else timezone, recurrenceIdentity,
  location?.label, location?.address, location?.lat?.toString(), location?.lng?.toString(),
)

internal fun normalizeTitle(t: String): String = t.trim().lowercase().replace(Regex("\\s+"), " ")

// ── Per-field diff (ADR 0063 §5 "compact compare-and-choose") — exactly the four rendered
// fields; attendees/description/etc. never enter this comparison (ADR 0063 §2/§3). ──
internal fun diffFields(candidate: DayfoldEventCandidate, obs: CalendarEventObservation): List<FieldDiff> {
  val diffs = mutableListOf<FieldDiff>()
  val isCard = candidate.source is CalendarCandidateSource.Card ||
    (candidate.source == null && SubjectRef.cardIdOf(candidate.subjectKey) != null)
  val isBlock = candidate.source is CalendarCandidateSource.Block ||
    (candidate.source == null && SubjectRef.blockIdOf(candidate.subjectKey) != null)
  val isLegacyHub = candidate.source.let { it is CalendarCandidateSource.Hub && it.type == null }
  if (normalizeTitle(candidate.title) != normalizeTitle(obs.title)) {
    diffs += FieldDiff(
      "title", candidate.title, obs.title,
      calendarWriteSupported = !isLegacyHub && (!isBlock || candidate.title != "Reminder"),
    )
  }
  if (candidate.startAt != obs.startAt) {
    diffs += FieldDiff("start", candidate.startAt, obs.startAt, calendarWriteSupported = !isLegacyHub)
  }
  if (candidate.endAt != obs.endAt) {
    diffs += FieldDiff(
      "end", candidate.endAt, obs.endAt,
      calendarWriteSupported = !isLegacyHub && !isCard && (!isBlock || candidate.endAt != null),
    )
  }
  val dayfoldLoc = locationText(candidate.location)
  val calendarLoc = locationText(obs.location)
  if (dayfoldLoc != calendarLoc) {
    diffs += FieldDiff("location", dayfoldLoc, calendarLoc, calendarWriteSupported = isCard || (isBlock && candidate.location != null))
  }
  return diffs
}

private fun locationText(l: CandidateLocation?): String? = when {
  l == null -> null
  l.address != null -> l.address
  l.label != null -> l.label
  l.lat != null && l.lng != null -> "${l.lat},${l.lng}"
  else -> null
}

// ── High-confidence suggestion (ADR 0063 §4c) — same start instant + significant title token
// overlap. Confirmation-only: never feeds a fingerprint, never auto-binds. ──
private val TITLE_STOPWORDS = setOf("a", "an", "the", "at", "to", "of", "and", "or", "in", "on", "for", "with")

private fun titleTokens(title: String): Set<String> =
  title.lowercase().split(Regex("[^a-z0-9]+")).filterTo(mutableSetOf()) { it.length > 2 && it !in TITLE_STOPWORDS }

internal fun significantTitleOverlap(a: String, b: String): Boolean {
  val ta = titleTokens(a)
  val tb = titleTokens(b)
  if (ta.isEmpty() || tb.isEmpty()) return false
  val overlap = ta intersect tb
  if (overlap.isEmpty()) return false
  return overlap.size.toDouble() / minOf(ta.size, tb.size) >= 0.5
}

private fun evidenceFor(candidate: DayfoldEventCandidate, obs: CalendarEventObservation): List<String> {
  val overlap = (titleTokens(candidate.title) intersect titleTokens(obs.title)).sorted()
  return listOfNotNull(
    "same start time".takeIf { candidate.startAt == obs.startAt },
    "shared words: ${overlap.joinToString(", ")}".takeIf { overlap.isNotEmpty() },
  )
}
