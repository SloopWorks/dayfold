package com.sloopworks.dayfold.client

import com.sloopworks.dayfold.client.cards.CardAction
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

// ADR 0044 Phase B — the PURE notification-SELECTION core. NO engine fork: selection runs over the
// RankedFeed the SAME rank()/nowFeed produced (ADR 0043 §2b "notifications = the top-K of the same
// ranking under the daily cap"). The notification-only knobs live in this sibling NotifConfig, never
// in RankConfig — rank() stays byte-identical and Phase-A-deterministic (NowRank.kt:16-19). Both
// knobs + the ledger are device-local, NEVER synced (ADR 0024). Clock + zone injected → deterministic,
// snapshot/property-testable.

// Device-local OS-permission states (ADR 0044 §1; ADR 0024 — NEVER synced). Bridged from the platform
// controllers and re-read on resume (OS permission is OS-owned truth, never DB-cached) → reflected into
// AppState for the opt-in ladder. Location: Denied → WhenInUse (foreground) → Always (background opt-in).
enum class LocationPermission { Denied, WhenInUse, Always }
// Notification authorization is a SEPARATE axis from location (Android 13 POST_NOTIFICATIONS / iOS UN
// auth). Blocked = granted-then-disabled-in-OS (e.g. channel importance NONE) — we detect, never override.
enum class NotificationPermission { Denied, Granted, Blocked }

// Device-local, never-synced (ADR 0024). Default-OFF (ADR 0044 §1). Defaults ratified 2026-06-30:
// cap 3/day, quiet 22:00–08:00 local.
data class NotifConfig(
  val enabled: Boolean = false,
  val quietStartMinuteOfDay: Int = 22 * 60,
  val quietEndMinuteOfDay: Int = 8 * 60,
  val dailyCap: Int = 3,
)

// ADR 0063 §7 — the derived reasonKinds that represent a generic "this event starts/is
// approaching" alert: the ones a matched calendar event's own native alert would duplicate.
// CHECKLIST/GEO (derived) and WEATHER/EMAIL/CLAUDE/EXTERNAL (authored) are semantically distinct
// action-oriented items and are never in this set — Calendar ownership must not silence them too.
val EVENT_START_REASON_KINDS: Set<String> = setOf(ReasonKind.COUNTDOWN, ReasonKind.MILESTONE, ReasonKind.WHEN)

// A pure "today" view of the device-local notification_log (caller derives by local date, see
// postedTodayCount). postedToday gates the cap; notifiedSubjects dedups within the day.
data class NotifLedger(
  val postedToday: Int = 0,
  val notifiedSubjects: Set<String> = emptySet(),
)

// toPost → fire now; held → deferred by quiet-hours (re-evaluate at window end, never dropped);
// capped → over the daily cap (also not dropped silently — surfaced as the calm "cap reached" state).
data class NotifPlan(
  val toPost: List<NowItem> = emptyList(),
  val held: List<NowItem> = emptyList(),
  val capped: List<NowItem> = emptyList(),
)

/**
 * Decide which ranked items become LOCAL notifications. Pure. Reads the prominent bands of [feed]
 * (NOW then SOON, in their already-ranked order) — never re-scores or re-sorts (that would be a covert
 * second ranker, ADR 0044 rejects it). Urgent (NOW-band or geo-active) bypasses quiet-hours but still
 * counts against the daily cap (operator-ratified). Already-notified [ledger] subjects and
 * foreground-[suppressedSubjects] are excluded (no double-nag with the in-feed surfacing).
 *
 * [calendarOwnedSubjects] (ADR 0063 §7) suppresses ONLY the event-start/time candidate
 * ([EVENT_START_REASON_KINDS]) for a subject whose calendar_binding.notification_owner is
 * `calendar` — a semantically distinct candidate for the SAME subjectKey (an incomplete
 * checklist, a weather qualification) still passes. This is deliberately a reasonKind filter,
 * never a whole-subjectKey suppression set (that would also discard the distinct candidate).
 * Because rank() dedups by EXACT subjectKey (NowRank.kt), a suppressed reasonKind can be the
 * ranked HEAD with the distinct candidate merely collapsed underneath it in [RankedItem
 * .collapsedWith] — [notifiableItem] looks past a suppressed head to the next-best collapsed peer
 * so that distinct candidate still gets its notification instead of being silently swallowed.
 * The CALENDAR_CHECK aggregate itself is never a notification candidate, full stop (ADR 0063 §5
 * "never an interruption") — excluded unconditionally, not merely by virtue of its calm banding.
 */
fun selectNotifications(
  feed: RankedFeed,
  nowIso: String,
  zone: TimeZone,
  config: NotifConfig,
  ledger: NotifLedger = NotifLedger(),
  suppressedSubjects: Set<String> = emptySet(),
  calendarOwnedSubjects: Set<String> = emptySet(),
): NotifPlan {
  if (!config.enabled) return NotifPlan()

  val candidates = (feed.now + feed.soon).mapNotNull { r ->
    val promoted = notifiableItem(r, calendarOwnedSubjects) ?: return@mapNotNull null
    if (promoted.subjectKey in ledger.notifiedSubjects || promoted.subjectKey in suppressedSubjects) return@mapNotNull null
    r to promoted
  }
  if (candidates.isEmpty()) return NotifPlan()

  val quiet = inQuietHours(localMinuteOfDay(nowIso, zone), config)

  // quiet-hours holds non-urgent; urgent (NOW/geo) passes through.
  val held = ArrayList<NowItem>()
  val eligible = ArrayList<NowItem>()
  for ((r, promoted) in candidates) {
    val urgent = r.band == Band.NOW || promoted.geoActive
    if (quiet && !urgent) held += promoted else eligible += promoted
  }

  // daily cap — top-K of the eligible in ranked order; the tail is capped (never silently dropped).
  val remaining = (config.dailyCap - ledger.postedToday).coerceAtLeast(0)
  return NotifPlan(
    toPost = eligible.take(remaining),
    held = held,
    capped = eligible.drop(remaining),
  )
}

// The item within [r]'s cluster (the ranked head plus its collapsed dedup peers — head first,
// then peers in descending score order per NowRank.kt) that is actually notification-eligible:
// the head itself, unless it's a calendar-suppressed event-start or the CALENDAR_CHECK aggregate,
// in which case the next-best peer that isn't either. Null when nothing in the cluster qualifies.
private fun notifiableItem(r: RankedItem, calendarOwnedSubjects: Set<String>): NowItem? =
  (listOf(r.item) + r.collapsedWith).firstOrNull { isNotificationEligible(it, calendarOwnedSubjects) }

private fun isNotificationEligible(item: NowItem, calendarOwnedSubjects: Set<String>): Boolean =
  item.reasonKind != ReasonKind.CALENDAR_CHECK &&
    (item.subjectKey !in calendarOwnedSubjects || !isEventStartCandidate(item))

// The derived lane signals "this event starts" via reasonKind (EVENT_START_REASON_KINDS); the
// authored lane has no spare reasonKind slot for it (reasonKind there IS the provenance), so it
// carries the same signal on isEventStartAlert instead (WI-463 follow-up on ADR 0063 §7).
// internal, not private: BackgroundNotify.kt's planExactSchedules reuses this exact predicate so
// the exact-alarm suppression and the foreground/background notify suppression can never drift apart.
internal fun isEventStartCandidate(item: NowItem): Boolean =
  item.reasonKind in EVENT_START_REASON_KINDS || item.isEventStartAlert

// Wrap-aware: a window with start > end (e.g. 22:00→08:00) spans midnight. End-exclusive.
fun inQuietHours(minuteOfDay: Int, config: NotifConfig): Boolean {
  val s = config.quietStartMinuteOfDay
  val e = config.quietEndMinuteOfDay
  return if (s <= e) minuteOfDay in s until e else (minuteOfDay >= s || minuteOfDay < e)
}

// Local minute-of-day (0..1439) for the injected clock; null-safe → 0 (treated as outside quiet only
// if config window excludes midnight, which the default does not — but a bad clock should never throw).
private fun localMinuteOfDay(nowIso: String, zone: TimeZone): Int {
  val t = parseInstantFlexible(nowIso, zone)?.toLocalDateTime(zone)?.time ?: return 0
  return t.hour * 60 + t.minute
}

// The nearest-N saved places to the device (haversine; reuse NowDerive's geometry). The iOS 20-region
// / Android 100 cap is applied by the caller via [n]; eviction = farthest-first (this sort + take).
fun nearestNPlaces(places: List<Place>, location: DeviceLocation, n: Int): List<Place> =
  places.sortedBy { haversineMeters(location.lat, location.lng, it.lat, it.lng) }.take(n)

// Notification tap → the existing Phase-A cross-surface deep-link (ADR 0043 §4 / 0006). No new surface.
fun notificationActionFor(item: NowItem): CardAction? =
  item.target?.let { target ->
    val arrival = when {
      target.blockId != null -> HubArrival(HubArrivalLevel.BLOCK, target.blockId, HubArrivalSource.BRIEFING)
      target.sectionId != null -> HubArrival(HubArrivalLevel.SECTION, target.sectionId, HubArrivalSource.BRIEFING)
      else -> null
    }
    CardAction.OpenHub(target.hubId, arrival)
  }

// How many of [notifiedAtIsos] fall on the same LOCAL date as [nowIso] — the cap's daily rollover,
// computed by-date (no midnight reset job needed; survives process death). Pure.
fun postedTodayCount(notifiedAtIsos: List<String>, nowIso: String, zone: TimeZone): Int {
  val today = parseInstantFlexible(nowIso, zone)?.toLocalDateTime(zone)?.date ?: return 0
  return notifiedAtIsos.count { parseInstantFlexible(it, zone)?.toLocalDateTime(zone)?.date == today }
}
