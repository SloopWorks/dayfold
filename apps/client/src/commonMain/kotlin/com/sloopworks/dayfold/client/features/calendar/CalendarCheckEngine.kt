package com.sloopworks.dayfold.client

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import org.reduxkotlin.Store

/**
 * CAL-4 (ADR 0063 §4/§5, ADR 0058 effect-ownership) — runtime-owned Calendar Check effects. The
 * reducer stays pure; every DB read/write and every [CalendarPort] call lives here.
 *
 * A pass reads already-synced typed content + the device calendar port + the local binding table,
 * runs [CalendarReconciler.reconcile] off-main, persists only the auto-bindings it produced, and
 * publishes the results. Observation lists are NEVER persisted — [CalendarEventObservation] stays
 * in-memory only for the duration of one pass (ADR 0063 §3).
 */
class CalendarCheckEngine(
  private val store: Store<AppState>,
  private val contentStore: ContentStore,
  private val calendarPort: CalendarPort,
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
  private val nowProvider: () -> String = { Clock.System.now().toString() },
  private val zoneProvider: () -> TimeZone = { TimeZone.currentSystemDefault() },
  private val databaseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
  /** UI/host entry point: fire-and-forget a reconciliation pass on the engine's own scope. */
  fun startCheck() {
    scope.launch { runCheck() }
  }

  /** Direct invocation is retained for deterministic tests; production callers use [startCheck]. */
  internal suspend fun runCheck() {
    store.dispatch(StartCalendarCheck)

    val permission = calendarPort.permissionState()
    val settings = withContext(databaseDispatcher) { contentStore.calendarSettings() }
    val nowIso = nowProvider()

    if (permission != CalendarPermission.Granted || !settings.featureEnabled || settings.selectedCalendarIds.isEmpty()) {
      store.dispatch(CalendarCheckCompleted(ReconcileResult(), permission, nowIso, stale = true))
      return
    }

    val zone = zoneProvider()
    val nowInstant = Instant.parse(nowIso)
    val ignored = store.state.calendar.check.ignored

    val candidates = withContext(databaseDispatcher) {
      candidatesInHorizon(
        deriveEventCandidates(
          contentStore.activeHubs(), contentStore.allSections(), contentStore.allBlocks(), contentStore.activeCards(), zone,
        ),
        nowInstant, CALENDAR_CHECK_HORIZON_DAYS, zone,
      )
    }.filterNot { it.subjectKey in ignored }

    val observations = calendarPort.observeEvents(settings.selectedCalendarIds, CALENDAR_CHECK_HORIZON_DAYS)
      .filterNot { calendarOnlyItemKey(it.platformEventId) in ignored }

    val bindings = withContext(databaseDispatcher) { contentStore.allCalendarBindings() }

    val result = CalendarReconciler.reconcile(candidates, observations, bindings, nowInstant)

    withContext(databaseDispatcher) {
      result.autoBindings.forEach { contentStore.upsertCalendarBinding(it) }
      contentStore.setCalendarSettings(settings.copy(lastCheckAt = nowIso))
    }

    store.dispatch(CalendarCheckCompleted(result, permission, nowIso, stale = false))
  }

  /** User confirms a suggested match (rung c) is correct — persist the binding, then resolve it.
   *  A no-op if [subjectKey]/[eventId] no longer names a pending pair (e.g. a fresh check landed
   *  between render and tap) — the stale UI item is left for the next CalendarCheckCompleted to
   *  correct, rather than dispatching a resolve that silently drops it with nothing persisted. */
  suspend fun confirmMatch(subjectKey: String, eventId: String) {
    if (persistConfirmedMatch(subjectKey, eventId)) store.dispatch(ConfirmMatch(subjectKey, eventId))
  }

  /** User picks the correct event among an ambiguous candidate's multiple strict hits. Same
   *  stale-pair no-op guard as [confirmMatch]. */
  suspend fun resolveAmbiguous(subjectKey: String, chosenEventId: String) {
    if (persistConfirmedMatch(subjectKey, chosenEventId)) store.dispatch(ResolveAmbiguous(subjectKey, chosenEventId))
  }

  private suspend fun persistConfirmedMatch(subjectKey: String, eventId: String): Boolean {
    val results = store.state.calendar.check.results
    val pair = results.suggested.firstOrNull { it.candidate.subjectKey == subjectKey && it.observation.platformEventId == eventId }
      ?.let { it.candidate to it.observation }
      ?: results.ambiguous.firstOrNull { it.candidate.subjectKey == subjectKey }
        ?.let { am -> am.observations.firstOrNull { it.platformEventId == eventId }?.let { obs -> am.candidate to obs } }
      ?: return false
    val (candidate, obs) = pair
    val nowIso = nowProvider()
    withContext(databaseDispatcher) {
      // WI-463 follow-up on WI-445 — this user-confirmed bind can still be a RE-bind of a subject
      // that had a prior binding row (e.g. the linked event was deleted/recreated with a new
      // platformEventId). Preserve any prior SetNotificationOwner override instead of silently
      // resetting it to CALENDAR; only a subject with no prior binding at all defaults to CALENDAR.
      val priorOwner = contentStore.calendarBindingBySubjectKey(candidate.subjectKey)?.notificationOwner
      contentStore.upsertCalendarBinding(
        CalendarBinding(
          subjectKey = candidate.subjectKey,
          sourceVersion = candidate.sourceVersion,
          platformEventId = obs.platformEventId,
          calendarId = obs.calendarId,
          fingerprint = fingerprintOfObservation(obs),
          lastSeenAt = nowIso,
          relation = CalendarRelation.MATCHED,
          notificationOwner = priorOwner ?: CalendarNotificationOwner.CALENDAR,
          reviewState = null,
          createdAt = nowIso,
          updatedAt = nowIso,
        ),
      )
    }
    return true
  }

  /** Flips which side owns the generic start-time alert for an already-matched subject (ADR 0063 §7). */
  suspend fun setNotificationOwner(subjectKey: String, owner: CalendarNotificationOwner) {
    val nowIso = nowProvider()
    withContext(databaseDispatcher) {
      contentStore.calendarBindingBySubjectKey(subjectKey)?.let { existing ->
        contentStore.upsertCalendarBinding(existing.copy(notificationOwner = owner, updatedAt = nowIso))
      }
    }
    store.dispatch(SetNotificationOwner(subjectKey, owner))
  }

  /** Clears every local binding on this device (ADR 0063 §5 review action) — feature-scoped, not a sign-out. */
  suspend fun resetLocalMatches() {
    withContext(databaseDispatcher) { contentStore.resetCalendarBindings() }
    store.dispatch(ResetLocalMatches)
  }

  /** WI-447 primer "Continue" — fires the OS permission prompt seam (no result promise). */
  fun requestPermission() { calendarPort.requestPermission() }

  /** WI-447 native handoff — fires the native event editor. UI/host owns the prefill data.
   *  CAL-9 — routes the platform's completion outcome (when it can reliably report one) into the
   *  shared CalendarEditorReturned action. A SAVED outcome with calendar access already granted
   *  also kicks off a fresh check in the background: the delegate result alone is honest enough to
   *  claim "added" immediately, but a real binding (with the platform event id) still needs a pass
   *  over observeEvents. */
  fun openEventEditor(prefill: EventPrefill) {
    calendarPort.openEventEditor(prefill) { outcome ->
      store.dispatch(CalendarEditorReturned(outcome))
      if (outcome == CalendarEditorOutcome.SAVED && calendarPort.permissionState() == CalendarPermission.Granted) {
        startCheck()
      }
    }
  }

  /** WI-447 Settings on/off toggle. Fire-and-forget, mirrors [startCheck]. */
  fun setEnabled(enabled: Boolean) {
    scope.launch {
      val next = withContext(databaseDispatcher) { contentStore.calendarSettings() }.copy(featureEnabled = enabled)
      withContext(databaseDispatcher) { contentStore.setCalendarSettings(next) }
      store.dispatch(SetCalendarEnabled(enabled))
    }
  }

  /** WI-447 chooser "Include N calendars" / change-calendars sheet. Fire-and-forget. */
  fun setSelectedCalendars(calendarIds: Set<String>) {
    scope.launch {
      val next = withContext(databaseDispatcher) { contentStore.calendarSettings() }.copy(selectedCalendarIds = calendarIds)
      withContext(databaseDispatcher) { contentStore.setCalendarSettings(next) }
      store.dispatch(SetSelectedCalendars(calendarIds))
    }
  }

  /** WI-447 chooser candidate list — OS-owned truth, re-read on demand, never persisted. */
  fun loadAvailableCalendars() {
    scope.launch { store.dispatch(DeviceCalendarsLoaded(calendarPort.listCalendars())) }
  }

  fun stop() {
    // No standing subscriptions or channels to tear down (unlike NowEngine/SyncEngine) — every
    // pass is a bounded, one-shot suspend call on the caller-owned [scope].
  }
}
