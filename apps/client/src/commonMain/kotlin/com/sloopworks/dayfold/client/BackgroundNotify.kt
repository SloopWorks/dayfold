package com.sloopworks.dayfold.client

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

// ADR 0044 Phase B — the headless notification PASS. This is the pure heart of the background worker
// (geofence region-enter / scheduled wake / opportunistic reconcile): it holds NO Store. The platform
// actual reads a SYNCHRONOUS snapshot of the SQLDelight cache, hands it here, posts the result via
// LocalNotifier, and writes notification_log. NO ENGINE FORK — this builds a minimal AppState and calls
// the SAME nowFeed() the foreground render uses (incl. the authored lane + not_before gate), then the
// pure selectNotifications over the resulting RankedFeed. Quiet/cap live only in NotifConfig.

// One device-local notification_log row (subject + when it was posted). Drives the daily cap rollover
// (zone-aware, by-date) and within-day dedup.
data class NotifLogRow(val subjectKey: String, val notifiedAtIso: String)

// A synchronous read of everything the pass needs — gathered by the worker actual from the single
// process-shared ContentStore (sync getters), never a 2nd connection. Mirrors what nowFeed reads.
data class NotifSnapshot(
  val cards: List<Card> = emptyList(),
  val hubs: List<Hub> = emptyList(),
  val sections: List<HubSection> = emptyList(),
  val blocks: List<HubBlock> = emptyList(),
  val places: List<Place> = emptyList(),
  val surfacing: Map<String, SurfacingRecord> = emptyMap(),
  val config: NotifConfig = NotifConfig(),
  val log: List<NotifLogRow> = emptyList(),
)

// Default foreground-suppression window: a subject shown in-feed within this window is NOT also
// notified (no double-nag with the in-feed surfacing). Tunable; conservative by default.
val FOREGROUND_SUPPRESSION_WINDOW: Duration = 30.minutes

/**
 * Decide the LOCAL notifications for a background wake. Pure — clock + live location injected (the live
 * position never persists; ADR 0014). Builds a minimal [AppState] from [snapshot], runs nowFeed +
 * selectNotifications. The daily cap rolls over by local date (zone-aware) from the log; subjects already
 * notified today are deduped; subjects shown in-feed within [suppressionWindow] are suppressed.
 */
fun planBackgroundNotifications(
  snapshot: NotifSnapshot,
  nowIso: String,
  location: DeviceLocation?,
  zone: TimeZone = TimeZone.currentSystemDefault(),
  suppressionWindow: Duration = FOREGROUND_SUPPRESSION_WINDOW,
  deriveConfig: DeriveConfig = DeriveConfig(),
  rankConfig: RankConfig = RankConfig(),
): NotifPlan {
  if (!snapshot.config.enabled) return NotifPlan()

  val state = AppState(
    cards = snapshot.cards,
    hubs = HubState(hubs = snapshot.hubs),
    nowContent = NowContent(sections = snapshot.sections, blocks = snapshot.blocks, places = snapshot.places),
    surfacing = snapshot.surfacing,
  )
  // ADR 0049 Option A (#299): background pass never surfaces a coord-only authored geo trigger.
  val feed = nowFeed(state, nowIso, location, zone, deriveConfig, rankConfig, authoredCoordGeo = false)

  // daily-cap rollover + within-day dedup, computed by local date from the log.
  val notifiedAtIsos = snapshot.log.map { it.notifiedAtIso }
  val today = parseInstantFlexible(nowIso, zone)?.toLocalDateTime(zone)?.date
  val notifiedTodaySubjects = if (today == null) emptySet() else snapshot.log
    .filter { parseInstantFlexible(it.notifiedAtIso, zone)?.toLocalDateTime(zone)?.date == today }
    .map { it.subjectKey }.toSet()
  val ledger = NotifLedger(
    postedToday = postedTodayCount(notifiedAtIsos, nowIso, zone),
    notifiedSubjects = notifiedTodaySubjects,
  )

  val suppressed = foregroundSuppressedSubjects(snapshot.surfacing, nowIso, zone, suppressionWindow)

  return selectNotifications(feed, nowIso, zone, snapshot.config, ledger, suppressed)
}

// The worker's commonMain orchestration: plan → post → log. Holds NO Store. The platform actual builds
// the [LocalNotifier] + supplies the snapshot/clock/location + persists the log via [recordPosted]
// (a tiny idempotent notification_log insert). Returns the full plan so held/capped can be projected.
class BackgroundNotificationRunner(
  private val notifier: LocalNotifier,
  private val recordPosted: (subjectKey: String, nowIso: String) -> Unit,
) {
  fun run(
    snapshot: NotifSnapshot,
    nowIso: String,
    location: DeviceLocation?,
    zone: kotlinx.datetime.TimeZone = kotlinx.datetime.TimeZone.currentSystemDefault(),
  ): NotifPlan {
    val plan = planBackgroundNotifications(snapshot, nowIso, location, zone)
    val specs = plan.toPost.map { it.toNotificationSpec() }
    if (specs.isNotEmpty()) {
      notifier.ensureChannel()
      notifier.postGroup(specs)
      plan.toPost.forEach { recordPosted(it.subjectKey, nowIso) }
    }
    return plan
  }
}

// One exact local notification to schedule at a known future instant.
data class ExactSchedule(val atIso: String, val spec: NotificationSpec)

// The default horizon for exact scheduling — only the next ~2 days of timed items are armed (re-armed on
// each sync), so a far-future event doesn't hold a stale alarm for weeks.
val EXACT_SCHEDULE_HORIZON: Duration = (48 * 60).minutes

/**
 * Plan the EXACT local notifications for known future instants (when.at / countdown / milestone) — a
 * periodic worker can't fire on time under Doze, so these are armed at sync time and the OS wakes us at
 * the instant (AlarmManager / UNCalendarNotificationTrigger). Pure: reads BOTH raw lanes directly —
 * deriveNow + cardToNowItem (NOT nowFeed, whose not_before gate would hide the very future authored items
 * we want to wake for) — and keeps each subject's SOONEST future trigger within [horizon]. At FIRE time
 * the receiver re-runs the full pass, so cap/quiet/dedup are honored then; this only decides WHEN to wake.
 */
fun planExactSchedules(
  snapshot: NotifSnapshot,
  nowIso: String,
  zone: TimeZone = TimeZone.currentSystemDefault(),
  horizon: Duration = EXACT_SCHEDULE_HORIZON,
  deriveConfig: DeriveConfig = DeriveConfig(),
  rankConfig: RankConfig = RankConfig(),
): List<ExactSchedule> {
  if (!snapshot.config.enabled) return emptyList()
  val now = parseInstantFlexible(nowIso, zone) ?: return emptyList()

  val derived = deriveNow(
    hubs = snapshot.hubs, sections = snapshot.sections, blocks = snapshot.blocks, places = snapshot.places,
    nowIso = nowIso, location = null, zone = zone, config = deriveConfig,
  )
  val authored = snapshot.cards.map { cardToNowItem(it, rankConfig, nowIso, zone) }

  // keep the soonest future trigger per subject, within the horizon.
  return (derived + authored).mapNotNull { item ->
    val at = item.triggerAtIso?.let { parseInstantFlexible(it, zone) } ?: return@mapNotNull null
    if (at <= now || at - now > horizon) return@mapNotNull null
    item to at
  }
    .groupBy { it.first.subjectKey }
    .map { (_, group) -> group.minBy { it.second.toEpochMilliseconds() }.first }
    .map { ExactSchedule(atIso = it.triggerAtIso!!, spec = it.toNotificationSpec()) }
}

// Foreground-surfaced suppression (the other direction): when an item becomes visible in the live feed,
// cancel any standing notification for it — the user is looking right at it. Called from the render path
// with the feed's visible subject keys.
fun cancelForegroundVisible(notifier: LocalNotifier, visibleSubjectKeys: Set<String>) {
  visibleSubjectKeys.forEach { notifier.cancel(it) }
}

// Subjects shown in-feed within [window] before [nowIso] — suppressed from a background notification so
// the user is never pinged about something they just saw. Pure; reuses the surfacing.lastShown clock.
internal fun foregroundSuppressedSubjects(
  surfacing: Map<String, SurfacingRecord>,
  nowIso: String,
  zone: TimeZone,
  window: Duration,
): Set<String> {
  val now = parseInstantFlexible(nowIso, zone) ?: return emptySet()
  return surfacing.entries.filterTo(mutableSetOf()) { (_, rec) ->
    val shown = rec.lastShownAtIso?.let { parseInstantFlexible(it, zone) } ?: return@filterTo false
    shown <= now && now - shown <= window
  }.map { it.key }.toSet()
}
