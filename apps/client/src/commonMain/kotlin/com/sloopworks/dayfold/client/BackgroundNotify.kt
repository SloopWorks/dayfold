package com.sloopworks.dayfold.client

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
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
  // The cache already contains only responses visible to this device's member. Done/family rows
  // apply without identity; viewerUserId lets the shared foreground selector apply personal mutes.
  val responses: List<ContentResponse> = emptyList(),
  val viewerUserId: String? = null,
  // ADR 0063 §7 — subjectKeys whose calendar_binding.notification_owner is `calendar`; threaded
  // straight into selectNotifications' event-start suppression, never re-derived here.
  val calendarOwnedSubjects: Set<String> = emptySet(),
)

/**
 * Process-local admission fence for notification work that touches both the family cache and OS state.
 *
 * A caller captures the current generation before waiting for the execution gate, then re-checks it
 * after entering. Closing admission invalidates already-queued callers and waits for an already-running
 * caller to finish before platform cleanup begins. Reopening mints another generation, so an old queued
 * caller still cannot revive an outgoing family's alarms or notification ledger after cleanup.
 */
internal enum class NotificationAdmissionBlocker { FAMILY, CONFIG }

internal class NotificationPassAdmissionCoordinator {
  private val stateGate = SynchronizedObject()
  private val executionGate = SynchronizedObject()
  private var generation = 0L
  private val blockers = mutableSetOf<NotificationAdmissionBlocker>()

  fun <T> runIfAdmitted(
    orElse: () -> T,
    afterAdmissionCaptured: () -> Unit = {},
    action: () -> T,
  ): T {
    val admittedGeneration = synchronized(stateGate) {
      generation.takeIf { blockers.isEmpty() }
    } ?: return orElse()
    afterAdmissionCaptured()

    return synchronized(executionGate) {
      val stillCurrent = synchronized(stateGate) {
        blockers.isEmpty() && generation == admittedGeneration
      }
      if (stillCurrent) action() else orElse()
    }
  }

  fun closeAndDrain(
    blocker: NotificationAdmissionBlocker,
    afterAdmissionClosed: () -> Unit = {},
    cleanup: () -> Unit,
  ) {
    synchronized(stateGate) {
      generation += 1L
      blockers += blocker
    }
    afterAdmissionClosed()
    synchronized(executionGate, cleanup)
  }

  fun openAdmission(blocker: NotificationAdmissionBlocker) = synchronized(stateGate) {
    if (blockers.remove(blocker)) generation += 1L
  }
}

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
    session = SessionState(
      session = snapshot.viewerUserId?.let { Session(access = "", refresh = "", userId = it) },
    ),
    content = ContentState(cards = snapshot.cards),
    hubs = HubState(hubs = snapshot.hubs),
    responses = ResponseState(rules = snapshot.responses),
    now = NowState(
      content = NowContent(sections = snapshot.sections, blocks = snapshot.blocks, places = snapshot.places),
      surfacing = snapshot.surfacing,
    ),
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

  return selectNotifications(feed, nowIso, zone, snapshot.config, ledger, suppressed, snapshot.calendarOwnedSubjects)
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
    onComplete: () -> Unit = {},
  ): NotifPlan {
    // A Done response is authoritative even if its source tombstone was skipped by an old cursor.
    // Clear any already-delivered notification before planning new work for this snapshot.
    completedNotificationSubjects(snapshot).forEach(notifier::cancel)
    val plan = planBackgroundNotifications(snapshot, nowIso, location, zone)
    val specs = plan.toPost.map { it.toNotificationSpec() }
    if (specs.isEmpty()) {
      onComplete()
      return plan
    }
    try {
      notifier.ensureChannel()
      notifier.postGroup(specs) { accepted ->
        try {
          plan.toPost.filter { it.subjectKey in accepted }
            .forEach { recordPosted(it.subjectKey, nowIso) }
        } finally {
          onComplete()
        }
      }
    } catch (error: Throwable) {
      onComplete()
      throw error
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
 *
 * ADR 0063 §7 — [snapshot.calendarOwnedSubjects] filters out the event-start candidate here too, not
 * only in [selectNotifications]. Android's exact-alarm receiver re-verifies at fire time either way, but
 * iOS delivers a pre-baked scheduled notification with no re-run (ADR 0044 — `reconcileExactSchedules`
 * applies its posture filters at schedule time) — arming the alarm here is the only chance to suppress it.
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
  val responseSuppressed = ResponseRules.suppress(
    derived + authored,
    snapshot.responses,
    snapshot.viewerUserId,
  )
  val calendarSuppressed = responseSuppressed.filterNot {
    it.subjectKey in snapshot.calendarOwnedSubjects && it.reasonKind in EVENT_START_REASON_KINDS
  }

  // keep the soonest future trigger per subject, within the horizon.
  return calendarSuppressed.mapNotNull { item ->
    val at = item.triggerAtIso?.let { parseInstantFlexible(it, zone) } ?: return@mapNotNull null
    if (at <= now || at - now > horizon) return@mapNotNull null
    item to at
  }
    .groupBy { it.first.subjectKey }
    .map { (_, group) -> group.minBy { it.second.toEpochMilliseconds() }.first }
    .map { ExactSchedule(atIso = it.triggerAtIso!!, spec = it.toNotificationSpec()) }
}

/** Done subjects whose posted/exact notifications must be cancelled even if source rows remain stale. */
internal fun completedNotificationSubjects(snapshot: NotifSnapshot): Set<String> =
  snapshot.responses.asSequence()
    .filter { it.kind == ResponseKind.DONE }
    .map { it.subjectRef }
    .toSet()

/**
 * Subjects whose exact-notification source still exists and is visible to this viewer. Unlike
 * [planExactSchedules], this deliberately keeps past triggers: after iOS fires an exact request it
 * moves from pending to delivered, and an ordinary reconciliation must not erase that unread banner
 * merely because its trigger is now behind the clock.
 */
internal fun activeExactNotificationSubjects(
  snapshot: NotifSnapshot,
  nowIso: String,
  zone: TimeZone = TimeZone.currentSystemDefault(),
  rankConfig: RankConfig = RankConfig(),
): Set<String> {
  if (!snapshot.config.enabled) return emptySet()

  val now = parseInstantFlexible(nowIso, zone)
  val candidates = mutableListOf<NowItem>()
  snapshot.cards.asSequence()
    // A card can remain useful after its timed reminder is removed. In that case the card itself is
    // still live, but it no longer owns an exact-notification source and must not preserve old history.
    .filter { card -> card.notBefore != null || card.triggers.orEmpty().any { it.whenTrigger?.at != null } }
    .filter { card ->
      now == null || card.expiresAt == null ||
        (parseInstantFlexible(card.expiresAt, zone)?.let { it > now } ?: true)
    }
    .mapTo(candidates) { cardToNowItem(it, rankConfig, nowIso, zone) }

  snapshot.hubs.forEach { hub ->
    val trigger = hub.countdownTo ?: hub.startAt ?: return@forEach
    candidates += exactSourceItem(
      id = "exact-source:countdown:${hub.id}",
      kind = ReasonKind.COUNTDOWN,
      title = hub.title,
      subjectKey = SubjectRef.node(hub.id),
      triggerAtIso = trigger,
    )
  }

  val hubIdBySection = snapshot.sections.mapNotNull { section ->
    section.hubId?.let { section.id to it }
  }.toMap()
  val hubById = snapshot.hubs.associateBy { it.id }
  snapshot.blocks.forEach { block ->
    val hubId = block.sectionId?.let(hubIdBySection::get) ?: return@forEach
    val subjectKey = SubjectRef.node(hubId, block.sectionId, block.id)
    val title = block.bodyMd?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
      ?: block.payload?.label
      ?: "Reminder"

    block.payload?.date?.takeIf { block.type == "milestone" }?.let { date ->
      candidates += exactSourceItem(
        id = "exact-source:milestone:${block.id}",
        kind = ReasonKind.MILESTONE,
        title = title,
        subjectKey = subjectKey,
        triggerAtIso = date,
      )
    }
    if (block.type == "checklist" && block.payload?.items?.any { !it.done } == true) {
      val hubTrigger = hubById[hubId]?.let { it.countdownTo ?: it.startAt }
      if (hubTrigger != null) candidates += exactSourceItem(
        id = "exact-source:checklist:${block.id}",
        kind = ReasonKind.CHECKLIST,
        title = title,
        subjectKey = subjectKey,
        triggerAtIso = hubTrigger,
      )
    }
    block.triggers.orEmpty().forEachIndexed { index, trigger ->
      trigger.whenTrigger?.at?.let { at ->
        candidates += exactSourceItem(
          id = "exact-source:when:${block.id}:$index",
          kind = ReasonKind.WHEN,
          title = title,
          subjectKey = subjectKey,
          triggerAtIso = at,
        )
      }
    }
  }

  return ResponseRules.suppress(
    candidates.filterNot {
      it.subjectKey in snapshot.calendarOwnedSubjects && it.reasonKind in EVENT_START_REASON_KINDS
    },
    snapshot.responses,
    snapshot.viewerUserId,
  ).mapTo(linkedSetOf()) { it.subjectKey }
}

private fun exactSourceItem(
  id: String,
  kind: String,
  title: String,
  subjectKey: String,
  triggerAtIso: String,
) = NowItem(
  id = id,
  origin = Origin.DERIVED,
  reasonKind = kind,
  title = title,
  why = title,
  subjectKey = subjectKey,
  target = null,
  triggerAtIso = triggerAtIso,
)

/**
 * iOS delivers exact requests without a fire-time app pass, so posture is baked into the pending
 * plan. Apply cap and dedup per schedule's local date: exhausting today's allowance must not erase
 * tomorrow's already-armable reminders when Notification Center receives the replacement plan.
 */
internal fun selectIosExactSchedules(
  schedules: List<ExactSchedule>,
  config: NotifConfig,
  log: List<NotifLogRow>,
  zone: TimeZone,
): List<ExactSchedule> {
  if (!config.enabled || config.dailyCap <= 0) return emptyList()

  val postedByDate = mutableMapOf<kotlinx.datetime.LocalDate, Int>()
  val notifiedSubjectsByDate = mutableMapOf<kotlinx.datetime.LocalDate, MutableSet<String>>()
  log.forEach { row ->
    val date = parseInstantFlexible(row.notifiedAtIso, zone)?.toLocalDateTime(zone)?.date
      ?: return@forEach
    postedByDate[date] = (postedByDate[date] ?: 0) + 1
    notifiedSubjectsByDate.getOrPut(date) { mutableSetOf() } += row.subjectKey
  }

  val plannedByDate = mutableMapOf<kotlinx.datetime.LocalDate, Int>()
  return schedules.filter { schedule ->
    val local = parseInstantFlexible(schedule.atIso, zone)?.toLocalDateTime(zone)
      ?: return@filter false
    val date = local.date
    if (schedule.spec.subjectKey in notifiedSubjectsByDate[date].orEmpty()) return@filter false
    if ((postedByDate[date] ?: 0) + (plannedByDate[date] ?: 0) >= config.dailyCap) {
      return@filter false
    }
    val minuteOfDay = local.hour * 60 + local.minute
    if (!schedule.spec.urgent && inQuietHours(minuteOfDay, config)) return@filter false
    plannedByDate[date] = (plannedByDate[date] ?: 0) + 1
    true
  }
}

// Foreground-surfaced suppression (the other direction): when an item becomes visible in the live feed,
// cancel any standing notification for it — the user is looking right at it. Called from the render path
// with the feed's visible subject keys.
fun cancelForegroundVisible(notifier: LocalNotifier, visibleSubjectKeys: Set<String>) {
  visibleSubjectKeys.forEach { notifier.cancelDelivered(it) }
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
