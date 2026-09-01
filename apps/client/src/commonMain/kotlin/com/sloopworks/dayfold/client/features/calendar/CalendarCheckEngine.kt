package com.sloopworks.dayfold.client

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
  private val requestSync: () -> Unit = {},
) {
  private val checkMutex = Mutex()
  private val settingsMutex = Mutex()

  /** UI/host entry point: fire-and-forget a reconciliation pass on the engine's own scope. */
  fun startCheck() {
    scope.launch { runCheck() }
  }

  /** Direct invocation is retained for deterministic tests; production callers use [startCheck]. */
  internal suspend fun runCheck(): Unit = checkMutex.withLock {
    store.dispatch(StartCalendarCheck)

    val permission = calendarPort.permissionState()
    val settings = withContext(databaseDispatcher) { contentStore.calendarSettings() }
    val bindings = withContext(databaseDispatcher) { contentStore.allCalendarBindings() }
    val persistedIgnored = bindings
      .filter { it.relation == CalendarRelation.IGNORED }
      .sortedBy { it.updatedAt }
      .map { it.subjectKey }
    val ignoredHistory = (
      persistedIgnored + store.state.calendar.check.ignoreHistory + store.state.calendar.check.ignored
    ).distinct()
    val nowIso = nowProvider()

    if (permission != CalendarPermission.Granted || !settings.featureEnabled || settings.selectedCalendarIds.isEmpty()) {
      store.dispatch(CalendarCheckCompleted(ReconcileResult(), permission, nowIso, stale = true, ignoredKeys = ignoredHistory))
      return@withLock
    }

    val zone = zoneProvider()
    val nowInstant = Instant.parse(nowIso)
    val ignored = ignoredHistory.toSet()

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

    val result = CalendarReconciler.reconcile(candidates, observations, bindings, nowInstant)

    withContext(databaseDispatcher) {
      result.autoBindings.forEach { contentStore.upsertCalendarBinding(it) }
      contentStore.setCalendarSettings(settings.copy(lastCheckAt = nowIso))
    }

    store.dispatch(CalendarCheckCompleted(result, permission, nowIso, stale = false, ignoredKeys = ignoredHistory))
  }

  /** Successful content refresh hook. Disabled Calendar Check stays completely idle. */
  internal suspend fun runIfEnabled() {
    val settings = withContext(databaseDispatcher) { contentStore.calendarSettings() }
    if (settings.featureEnabled && settings.selectedCalendarIds.isNotEmpty()) runCheck()
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

  /** Persists a local dismissal before removing it from the current result set. */
  suspend fun ignoreItem(itemKey: String) {
    val results = store.state.calendar.check.results
    val eventId = itemKey.takeIf { it.startsWith(CALENDAR_EVENT_ITEM_KEY_PREFIX) }
      ?.removePrefix(CALENDAR_EVENT_ITEM_KEY_PREFIX)
    val observation = eventId?.let { id -> results.calendarOnly.firstOrNull { it.platformEventId == id } }
    val candidate = results.dayfoldOnly.firstOrNull { it.subjectKey == itemKey }
      ?: results.candidateFor(itemKey)
    val nowIso = nowProvider()
    if (observation != null || candidate != null) {
      withContext(databaseDispatcher) {
        contentStore.upsertCalendarBinding(
          CalendarBinding(
            subjectKey = itemKey,
            sourceVersion = candidate?.sourceVersion ?: fingerprintOfObservation(requireNotNull(observation)),
            platformEventId = observation?.platformEventId,
            calendarId = observation?.calendarId,
            fingerprint = observation?.let(::fingerprintOfObservation),
            lastSeenAt = nowIso,
            relation = CalendarRelation.IGNORED,
            reviewState = "ignored",
            createdAt = nowIso,
            updatedAt = nowIso,
          ),
        )
      }
    }
    store.dispatch(IgnoreItem(itemKey))
  }

  suspend fun undoIgnore(itemKey: String) {
    withContext(databaseDispatcher) {
      contentStore.calendarBindingBySubjectKey(itemKey)
        ?.takeIf { it.relation == CalendarRelation.IGNORED }
        ?.let { contentStore.deleteCalendarBindingForSubject(itemKey) }
    }
    store.dispatch(UndoIgnore(itemKey))
    runIfEnabled()
  }

  /** Rejecting a fuzzy suggestion is durable for that exact subject/event pair. */
  suspend fun keepSeparate(subjectKey: String) {
    val pair = store.state.calendar.check.results.suggested
      .firstOrNull { it.candidate.subjectKey == subjectKey }
    if (pair != null) {
      val nowIso = nowProvider()
      withContext(databaseDispatcher) {
        contentStore.upsertCalendarBinding(
          CalendarBinding(
            subjectKey = subjectKey,
            sourceVersion = pair.candidate.sourceVersion,
            platformEventId = pair.observation.platformEventId,
            calendarId = pair.observation.calendarId,
            fingerprint = fingerprintOfObservation(pair.observation),
            lastSeenAt = nowIso,
            relation = CalendarRelation.NEEDS_REVIEW,
            reviewState = "keep_separate",
            createdAt = nowIso,
            updatedAt = nowIso,
          ),
        )
      }
    }
    store.dispatch(KeepSeparate(subjectKey))
  }

  suspend fun keepSeriesCalendarOnly(subjectKey: String) {
    val notice = store.state.calendar.check.results.recurringNotices
      .firstOrNull { it.candidate.subjectKey == subjectKey }
    if (notice != null) {
      val nowIso = nowProvider()
      withContext(databaseDispatcher) {
        contentStore.upsertCalendarBinding(
          CalendarBinding(
            subjectKey = subjectKey,
            sourceVersion = notice.candidate.sourceVersion,
            platformEventId = notice.observation.platformEventId,
            calendarId = notice.observation.calendarId,
            fingerprint = fingerprintOfObservation(notice.observation),
            lastSeenAt = nowIso,
            relation = CalendarRelation.IGNORED,
            reviewState = "series_calendar_only",
            createdAt = nowIso,
            updatedAt = nowIso,
          ),
        )
      }
    }
    store.dispatch(KeepSeriesCalendarOnly(subjectKey))
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

  /** Resolves one rendered difference. Keep/leave choices are device-local and durable for the
   * exact compared versions; Use Calendar performs a typed optimistic Dayfold write first. */
  suspend fun resolveField(subjectKey: String, field: String, resolution: FieldResolution) {
    val differ = store.state.calendar.check.results.differs.firstOrNull { it.subjectKey == subjectKey } ?: return
    val rendered = differ.diffs.firstOrNull { it.field == field } ?: return
    if (resolution == FieldResolution.USE_CALENDAR) {
      if (!rendered.calendarWriteSupported) return
      val applied = withContext(databaseDispatcher) {
        contentStore.applyCalendarFieldValue(
          differ.candidate, differ.observation, field, nowProvider(), Ulid.next(),
        )
      }
      if (!applied) return
      requestSync()
    }

    val nowIso = nowProvider()
    withContext(databaseDispatcher) {
      val existing = contentStore.calendarBindingBySubjectKey(subjectKey) ?: return@withContext
      val fields = resolvedCalendarFields(existing.reviewState) + field
      contentStore.upsertCalendarBinding(
        existing.copy(
          sourceVersion = differ.candidate.sourceVersion,
          fingerprint = fingerprintOfObservation(differ.observation),
          reviewState = calendarFieldsReviewState(fields),
          lastSeenAt = nowIso,
          updatedAt = nowIso,
        ),
      )
    }
    store.dispatch(FieldChoice(subjectKey, field, resolution))
  }

  /** Clears every local binding on this device (ADR 0063 §5 review action) — feature-scoped, not a sign-out. */
  suspend fun resetLocalMatches() {
    withContext(databaseDispatcher) { contentStore.resetCalendarBindings() }
    store.dispatch(ResetLocalMatches)
  }

  suspend fun unlinkMatch(subjectKey: String) {
    withContext(databaseDispatcher) { contentStore.deleteCalendarBindingForSubject(subjectKey) }
    runIfEnabled()
  }

  fun openMatchedEvent(subjectKey: String) {
    scope.launch {
      val eventId = withContext(databaseDispatcher) {
        contentStore.calendarBindingBySubjectKey(subjectKey)?.platformEventId
      }
      if (eventId != null) calendarPort.openEvent(eventId)
    }
  }

  fun openObservedEvent(platformEventId: String) { scope.launch { calendarPort.openEvent(platformEventId) } }

  /** WI-447 primer "Continue" — fires the OS permission prompt seam (no result promise). */
  fun requestPermission() {
    calendarPort.requestPermission {
      scope.launch {
        runCheck()
        if (calendarPort.permissionState() == CalendarPermission.Granted) loadAvailableCalendars()
      }
    }
  }

  /** WI-447 native handoff — fires the native event editor. UI/host owns the prefill data.
   *  CAL-9 — routes the platform's completion outcome (when it can reliably report one) into the
   *  shared CalendarEditorReturned action. A SAVED outcome with calendar access already granted
   *  also kicks off a fresh check in the background: the delegate result alone is honest enough to
   *  claim "added" immediately, but a real binding (with the platform event id) still needs a pass
   *  over observeEvents. */
  fun openEventEditor(prefill: EventPrefill) {
    store.dispatch(CalendarEditorOpened)
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
      settingsMutex.withLock {
        val current = withContext(databaseDispatcher) { contentStore.calendarSettings() }
        val next = if (enabled) {
          current.copy(featureEnabled = current.selectedCalendarIds.isNotEmpty())
        } else {
          current.copy(featureEnabled = false, selectedCalendarIds = emptySet())
        }
        withContext(databaseDispatcher) {
          contentStore.setCalendarSettings(next)
          if (!enabled) contentStore.resetCalendarBindings()
        }
        store.dispatch(CalendarSettingsLoaded(next))
        if (!enabled) store.dispatch(ResetLocalMatches)
      }
      if (enabled) runIfEnabled()
    }
  }

  /** Setup completion is one ordered transaction from the UI's point of view: selected calendars
   * and opt-in are persisted together before the first pass can observe them. */
  fun enable(calendarIds: Set<String>) {
    if (calendarIds.isEmpty()) return
    scope.launch {
      settingsMutex.withLock {
        val current = withContext(databaseDispatcher) { contentStore.calendarSettings() }
        val next = current.copy(featureEnabled = true, selectedCalendarIds = calendarIds)
        withContext(databaseDispatcher) { contentStore.setCalendarSettings(next) }
        store.dispatch(CalendarSettingsLoaded(next))
      }
      runCheck()
    }
  }

  /** WI-447 chooser "Include N calendars" / change-calendars sheet. Fire-and-forget. */
  fun setSelectedCalendars(calendarIds: Set<String>) {
    scope.launch {
      settingsMutex.withLock {
        val current = withContext(databaseDispatcher) { contentStore.calendarSettings() }
        val removed = current.selectedCalendarIds - calendarIds
        val next = current.copy(
          featureEnabled = current.featureEnabled && calendarIds.isNotEmpty(),
          selectedCalendarIds = calendarIds,
        )
        withContext(databaseDispatcher) {
          removed.forEach(contentStore::deleteCalendarBindingsForCalendar)
          contentStore.setCalendarSettings(next)
        }
        store.dispatch(CalendarSettingsLoaded(next))
        if (calendarIds.isEmpty()) store.dispatch(ResetLocalMatches)
      }
      runIfEnabled()
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
