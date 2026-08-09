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

  /** User confirms a suggested match (rung c) is correct — persist the binding, then resolve it. */
  suspend fun confirmMatch(subjectKey: String, eventId: String) {
    persistConfirmedMatch(subjectKey, eventId)
    store.dispatch(ConfirmMatch(subjectKey, eventId))
  }

  /** User picks the correct event among an ambiguous candidate's multiple strict hits. */
  suspend fun resolveAmbiguous(subjectKey: String, chosenEventId: String) {
    persistConfirmedMatch(subjectKey, chosenEventId)
    store.dispatch(ResolveAmbiguous(subjectKey, chosenEventId))
  }

  private suspend fun persistConfirmedMatch(subjectKey: String, eventId: String) {
    val results = store.state.calendar.check.results
    val pair = results.suggested.firstOrNull { it.candidate.subjectKey == subjectKey && it.observation.platformEventId == eventId }
      ?.let { it.candidate to it.observation }
      ?: results.ambiguous.firstOrNull { it.candidate.subjectKey == subjectKey }
        ?.let { am -> am.observations.firstOrNull { it.platformEventId == eventId }?.let { obs -> am.candidate to obs } }
      ?: return
    val (candidate, obs) = pair
    val nowIso = nowProvider()
    withContext(databaseDispatcher) {
      contentStore.upsertCalendarBinding(
        CalendarBinding(
          subjectKey = candidate.subjectKey,
          sourceVersion = candidate.sourceVersion,
          platformEventId = obs.platformEventId,
          calendarId = obs.calendarId,
          fingerprint = fingerprintOfObservation(obs),
          lastSeenAt = nowIso,
          relation = CalendarRelation.MATCHED,
          notificationOwner = CalendarNotificationOwner.CALENDAR,
          reviewState = null,
          createdAt = nowIso,
          updatedAt = nowIso,
        ),
      )
    }
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

  fun stop() {
    // No standing subscriptions or channels to tear down (unlike NowEngine/SyncEngine) — every
    // pass is a bounded, one-shot suspend call on the caller-owned [scope].
  }
}
