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
 */
fun selectNotifications(
  feed: RankedFeed,
  nowIso: String,
  zone: TimeZone,
  config: NotifConfig,
  ledger: NotifLedger = NotifLedger(),
  suppressedSubjects: Set<String> = emptySet(),
): NotifPlan {
  if (!config.enabled) return NotifPlan()

  val candidates = (feed.now + feed.soon).filter {
    it.item.subjectKey !in ledger.notifiedSubjects && it.item.subjectKey !in suppressedSubjects
  }
  if (candidates.isEmpty()) return NotifPlan()

  val quiet = inQuietHours(localMinuteOfDay(nowIso, zone), config)

  // quiet-hours holds non-urgent; urgent (NOW/geo) passes through.
  val held = ArrayList<NowItem>()
  val eligible = ArrayList<NowItem>()
  for (r in candidates) {
    val urgent = r.band == Band.NOW || r.item.geoActive
    if (quiet && !urgent) held += r.item else eligible += r.item
  }

  // daily cap — top-K of the eligible in ranked order; the tail is capped (never silently dropped).
  val remaining = (config.dailyCap - ledger.postedToday).coerceAtLeast(0)
  return NotifPlan(
    toPost = eligible.take(remaining),
    held = held,
    capped = eligible.drop(remaining),
  )
}

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
  item.target?.let { CardAction.OpenHub(it.hubId, it.blockId) }

// How many of [notifiedAtIsos] fall on the same LOCAL date as [nowIso] — the cap's daily rollover,
// computed by-date (no midnight reset job needed; survives process death). Pure.
fun postedTodayCount(notifiedAtIsos: List<String>, nowIso: String, zone: TimeZone): Int {
  val today = parseInstantFlexible(nowIso, zone)?.toLocalDateTime(zone)?.date ?: return 0
  return notifiedAtIsos.count { parseInstantFlexible(it, zone)?.toLocalDateTime(zone)?.date == today }
}
