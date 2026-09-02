package com.sloopworks.dayfold.client

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.reduxkotlin.Store

/**
 * CAL-10 (ADR 0063 §6, calendar-import-contract-design.md, ADR 0058 effect-ownership) — runtime-
 * owned effects for the reviewed Calendar→Dayfold import. The reducer (CalendarImportReducer)
 * stays pure; every DB write, id mint, revalidation read, and outbox interaction lives here.
 *
 * Revalidation (spec §3.5) is deliberately LOCAL: "re-read the destination Hub" reads the
 * already-synced `state.hubs` + a freshly loaded audience (via [hubEngine]), never a dedicated
 * network round trip beyond that — the spec's own §3.4 "Honest limit" already accepts this as a
 * client-side, non-airtight precondition (the server's hubWriteGate is the actual security
 * boundary, re-checked on every drained op). "Re-read the source event" re-runs
 * [CalendarPort.observeEvents] scoped to the one calendar the event came from.
 *
 * Apply progress (state 1→2) is observed by polling the enqueued ops' own outbox rows
 * ([ContentStore.outboxOpStates]) rather than hooking into the generic drain loop's dispatch —
 * SyncEngine stays a plain, feature-agnostic sender; this engine is simply one more caller of it,
 * matching ResponseEngine's posture (mint → optimistic/local write → enqueue → requestSync).
 */
class CalendarImportEngine(
  private val store: Store<AppState>,
  private val contentStore: ContentStore,
  private val syncEngine: SyncEngine,
  private val calendarPort: CalendarPort,
  // Seam over HubEngine.loadAudience (returns the just-loaded state.hubs.currentAudience, or null
  // on failure) — a lambda rather than a HubEngine dependency so this engine is unit-testable with
  // a plain fake, matching CalendarCheckEngine's CalendarPort seam pattern.
  private val loadHubAudience: suspend (hubId: String) -> HubAudience?,
  private val isOnline: () -> Boolean,
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
  private val nowProvider: () -> String = { kotlin.time.Clock.System.now().toString() },
  private val idProvider: () -> String = { Ulid.next() },
  private val databaseDispatcher: CoroutineDispatcher = Dispatchers.Default,
  private val pollIntervalMs: Long = 400L,
  private val pollMaxAttempts: Int = 30,
) {

  /** Destination step (spec §2.2) — Hubs where the caller is an author or holds ADR 0053
   *  contributor/co_owner. A UX affordance only: the server independently re-enforces this on
   *  every drained op (hubWriteGate), so a stale list here can never become a write. */
  suspend fun eligibleDestinationHubs(): List<ImportDestination.ExistingHub> =
    eligibleDestinationOptions().map { it.destination }

  private suspend fun eligibleDestinationOptions(): List<CalendarImportDestinationOption> {
    val viewer = store.state.session.session?.userId ?: return emptyList()
    val out = mutableListOf<CalendarImportDestinationOption>()
    val audienceIds = mutableMapOf<String, Set<String>>()
    val versions = mutableMapOf<String, Long?>()
    for (hub in store.state.hubs.hubs) {
      // Person-by-person audience disclosure is a precondition. If it cannot be loaded, this Hub
      // is conservatively omitted rather than rendered with an empty, falsely-private audience.
      val audience = loadHubAudience(hub.id) ?: continue
      val role = audience.members.firstOrNull { it.uid == viewer }?.participationRole
      val participation = if (hub.createdBy == viewer) "co_owner" else role
      if (participation == "contributor" || participation == "co_owner") {
        val permitted = audience.members.filter { it.permitted }
        out += CalendarImportDestinationOption(
          destination = ImportDestination.ExistingHub(hub.id, hub.title, participation),
          audienceNames = permitted.map { it.displayName?.takeIf(String::isNotBlank) ?: "Family member" },
        )
        audienceIds[hub.id] = permitted.map { it.uid }.toSet()
        versions[hub.id] = hub.version
      }
    }
    destinationAudienceByHub = audienceIds
    destinationVersionByHub = versions
    return out
  }

  /** Loads the destination picker on the engine scope. Only normalized Dayfold Hub/audience data
   * enters Redux; native calendar identifiers remain inside this engine. */
  fun loadEligibleDestinations() {
    scope.launch { store.dispatch(CalendarImportDestinationsLoaded(eligibleDestinationOptions())) }
  }

  private var destinationAudienceByHub: Map<String, Set<String>> = emptyMap()
  private var destinationVersionByHub: Map<String, Long?> = emptyMap()

  /** Review start — derives the normalized [CalendarImportProposal] from one calendar-only
   *  observation and starts the wizard (spec §1). Nothing calendar-identifying survives past this
   *  boundary: the proposal type has no field for [observation]'s platform/calendar ids. */
  fun startImport(observation: CalendarEventObservation) {
    // A recurring series is never imported as a series. If a caller explicitly reaches this
    // boundary for one observed occurrence, only that normalized occurrence is reviewed; the
    // recurrence identity remains structurally absent from CalendarImportProposal.
    val proposal = CalendarImportProposal(
      proposalId = idProvider(),
      title = normalizeImportTitle(observation.title),
      start = if (observation.allDay) EventInstant.AllDay(observation.startAt) else EventInstant.Timed(observation.startAt),
      end = observation.endAt?.let { if (observation.allDay) EventInstant.AllDay(it) else EventInstant.Timed(it) },
      timezone = observation.timezone,
      location = observation.location?.let { loc ->
        if (loc.label == null && loc.address == null) null else StructuredLocation(label = loc.label ?: "", address = loc.address)
      },
    )
    sourceSnapshots[proposal.proposalId] = observation
    store.dispatch(StartCalendarImport(proposal))
  }

  // Review-start snapshot of the source event, keyed by proposalId — compared against a fresh
  // observeEvents() read at confirm to detect drift (spec §3.5 state 6). Raw observations stay
  // in-memory; after confirm, only the mechanical event/calendar ids + fingerprint are persisted
  // in a provisional device-local calendar_binding so a restart can safely revalidate.
  private val sourceSnapshots = mutableMapOf<String, CalendarEventObservation>()

  /** Confirm (spec §3.5) — revalidate, mint ids (once), persist the local record, and enqueue. */
  fun confirm() { scope.launch { confirmCurrent() } }

  private suspend fun confirmCurrent() {
      val current = store.state.calendar.importState as? ImportProposalState.Confirming ?: return
      val proposal = current.proposal
      val destination = proposal.destination ?: return
      val source = sourceSnapshots[proposal.proposalId]
      // Confirm-time id mint + local proposal persistence happen before every hold state. This is
      // what lets permission loss and offline recovery retain the reviewed proposal without ever
      // enqueueing an unverifiable write.
      val ids = withContext(databaseDispatcher) { mintOrReuseIds(proposal, destination) }

      if (calendarPort.permissionState() != CalendarPermission.Granted) {
        withContext(databaseDispatcher) {
          contentStore.setCalendarImportStatus(proposal.proposalId, "permission_lost", nowProvider())
        }
        store.dispatch(ImportPermissionLost)
        return
      }

      // §3.5 state 6 — re-read the source event; a changed fingerprint requires an explicit re-confirm.
      if (source != null) {
        val fresh = calendarPort.observeEvents(setOf(source.calendarId), CALENDAR_CHECK_HORIZON_DAYS)
          .firstOrNull { it.platformEventId == source.platformEventId }
        if (fresh == null) {
          store.dispatch(
            ImportSourceChanged(
              ids,
              listOf(ImportFieldDiff("event", "Available when reviewed", "No longer available")),
              proposal,
            ),
          )
          return
        }
        if (fingerprintOfObservation(fresh) != fingerprintOfObservation(source)) {
          sourceSnapshots[proposal.proposalId] = fresh
          store.dispatch(
            ImportSourceChanged(
              ids = ids,
              diffs = diffSourceFields(source, fresh),
              refreshedProposal = proposal.copy(
                title = normalizeImportTitle(fresh.title),
                start = if (fresh.allDay) EventInstant.AllDay(fresh.startAt) else EventInstant.Timed(fresh.startAt),
                end = fresh.endAt?.let { if (fresh.allDay) EventInstant.AllDay(it) else EventInstant.Timed(it) },
                timezone = fresh.timezone,
                location = fresh.location?.let { loc ->
                  if (loc.label == null && loc.address == null) null
                  else StructuredLocation(label = loc.label ?: "", address = loc.address)
                },
              ),
            ),
          )
          return
        }
      }

      // §3.5 state 5/7 — re-read the destination Hub (client-side, from already-synced state; the
      // spec's own §3.4 "Honest limit" accepts this is not a fresh network read).
      if (destination is ImportDestination.ExistingHub) {
        val hub = store.state.hubs.hubs.firstOrNull { it.id == destination.hubId }
        if (hub == null) {
          store.dispatch(ImportRoleDenied)
          return
        }
        val viewer = store.state.session.session?.userId
        val audience = loadHubAudience(destination.hubId)
        if (audience == null) {
          store.dispatch(ImportRoleDenied)
          return
        }
        val role = audience.members.firstOrNull { it.uid == viewer }?.participationRole
        val stillPermitted = hub.createdBy == viewer || role == "contributor" || role == "co_owner"
        if (!stillPermitted) {
          store.dispatch(ImportRoleDenied)
          return
        }
        val namedAudience = audience.members.filter { it.permitted }.map { it.uid }
        val baseline = knownAudience[proposal.proposalId]
        val baselineVersion = knownDestinationVersion[proposal.proposalId]
        val versionChanged = baselineVersion != null && hub.version != baselineVersion
        if ((baseline != null && namedAudience.toSet() != baseline) || versionChanged) {
          knownAudience[proposal.proposalId] = namedAudience.toSet()
          knownDestinationVersion[proposal.proposalId] = hub.version
          withContext(databaseDispatcher) { mintOrReuseIds(proposal, destination) }
          store.dispatch(ImportVersionConflict(ids, namedAudience))
          return
        }
        knownAudience[proposal.proposalId] = namedAudience.toSet()
        knownDestinationVersion[proposal.proposalId] = hub.version
      }

      val nowIso = nowProvider()
      val existingSectionId = if (destination is ImportDestination.ExistingHub) {
        withContext(databaseDispatcher) { contentStore.liveSectionIdForHub(destination.hubId) }
      } else null
      val ops = materialize(proposal, destination, ids, nowIso, idProvider, existingSectionId)

      if (!isOnline()) {
        withContext(databaseDispatcher) {
          contentStore.setCalendarImportStatus(proposal.proposalId, "offline_queued", nowIso)
        }
        store.dispatch(ImportOfflineQueued(ids))
        return
      }

      withContext(databaseDispatcher) {
        contentStore.setCalendarImportStatus(proposal.proposalId, "applying", nowIso)
        contentStore.applyCalendarImportOptimistically(
          proposal = proposal,
          destination = destination,
          ids = ids,
          ops = ops,
          existingSectionId = existingSectionId,
          importerId = store.state.session.session?.userId,
          nowIso = nowIso,
        )
      }
      store.dispatch(ImportApplyStarted(ids))
      syncEngine.requestSync(SyncReason.OUTBOX_MUTATION)
      pollApply(proposal, destination, ids, ops.map { it.opId })
  }

  // Confirm-time audience snapshot per proposal — the state-7 precondition (spec §3.4/§3.5).
  private val knownAudience = mutableMapOf<String, Set<String>>()
  private val knownDestinationVersion = mutableMapOf<String, Long?>()

  /** Ids are minted ONCE at first confirm and reused on every retry/re-confirm (spec §3.3). */
  private fun mintOrReuseIds(proposal: CalendarImportProposal, destination: ImportDestination): ImportOpIds {
    val existing = contentStore.calendarImportIds(proposal.proposalId)
    val ids = existing ?: run {
      val hubId = if (destination is ImportDestination.NewHub) idProvider() else null
      ImportOpIds(hubId, idProvider(), List(importBlockCount(proposal)) { idProvider() })
    }
    val nowIso = nowProvider()
    contentStore.upsertCalendarImport(
      proposal = proposal,
      destination = destination,
      ids = ids,
      audienceIds = knownAudience[proposal.proposalId].orEmpty(),
      hubVersion = knownDestinationVersion[proposal.proposalId],
      status = "applying",
      nowIso = nowIso,
    )
    sourceSnapshots[proposal.proposalId]?.let { source ->
      contentStore.upsertCalendarBinding(
        CalendarBinding(
          subjectKey = provisionalImportSubject(proposal.proposalId),
          sourceVersion = proposal.proposalId,
          platformEventId = source.platformEventId,
          calendarId = source.calendarId,
          fingerprint = fingerprintOfObservation(source),
          lastSeenAt = nowIso,
          relation = CalendarRelation.NEEDS_REVIEW,
          createdAt = nowIso,
          updatedAt = nowIso,
        ),
      )
    }
    return ids
  }

  private suspend fun pollApply(
    proposal: CalendarImportProposal,
    destination: ImportDestination,
    ids: ImportOpIds,
    opIds: List<String>,
  ) {
    repeat(pollMaxAttempts) {
      delay(pollIntervalMs)
      val status = withContext(databaseDispatcher) { contentStore.calendarImportStatus(proposal.proposalId) }
      if (status == "saved") {
        publishSaved(proposal, destination)
        return
      }
      val states = withContext(databaseDispatcher) { contentStore.outboxOpStates(opIds) }
      if (states.values.any { it == null }) {
        // A dropped op — role/absent/invisible (spec §3.5 state 5) is the only Drop path an import
        // op reaches (410/404 tombstone doesn't apply to a create; 412 is unreachable per §3.4).
        withContext(databaseDispatcher) {
          contentStore.unresolvedCalendarImports()
            .firstOrNull { it.proposal.proposalId == proposal.proposalId }
            ?.let(contentStore::rollbackCalendarImport)
        }
        store.dispatch(ImportRoleDenied)
        return
      }
      if (states.values.any { it == "failed" }) {
        withContext(databaseDispatcher) {
          contentStore.setCalendarImportStatus(proposal.proposalId, "offline_queued", nowProvider())
        }
        store.dispatch(ImportOfflineQueued(ids))
        return
      }
      if (states.values.all { it == "acked" }) {
        // The terminal ack normally sets saved atomically. This fallback covers a legacy queued
        // chain produced before that path existed; the next poll observes the durable status.
        withContext(databaseDispatcher) { contentStore.setCalendarImportStatus(proposal.proposalId, "saved", nowProvider()) }
        publishSaved(proposal, destination)
        return
      }
    }

    // A backoff can leave every op pending longer than this screen's bounded poll. Do not strand
    // the UI in Applying: retain the durable proposal/ids and let the next successful sync resume
    // or publish the terminal ack. This changes copy/state only; the already-enqueued ops remain.
    val finalStatus = withContext(databaseDispatcher) { contentStore.calendarImportStatus(proposal.proposalId) }
    if (finalStatus == "saved") {
      publishSaved(proposal, destination)
    } else {
      withContext(databaseDispatcher) {
        contentStore.setCalendarImportStatus(proposal.proposalId, "offline_queued", nowProvider())
      }
      store.dispatch(ImportOfflineQueued(ids))
    }
  }

  private fun publishSaved(proposal: CalendarImportProposal, destination: ImportDestination) {
    store.dispatch(ImportSaved(audienceSummary(destination)))
    sourceSnapshots.remove(proposal.proposalId)
    knownAudience.remove(proposal.proposalId)
    knownDestinationVersion.remove(proposal.proposalId)
  }

  private fun audienceSummary(destination: ImportDestination): String = when (destination) {
    is ImportDestination.NewHub -> when {
      destination.visibility == HubVisibilityChoice.FAMILY -> "Hub created — your family can see it"
      destination.audience.size <= 1 -> "Hub created — only you can see it"
      else -> "Hub created — shared with ${destination.audience.size} people"
    }
    is ImportDestination.ExistingHub -> "Added to ${destination.hubTitle}"
  }

  private fun diffSourceFields(before: CalendarEventObservation, after: CalendarEventObservation): List<ImportFieldDiff> =
    buildList {
      if (before.title != after.title) add(ImportFieldDiff("title", before.title, after.title))
      if (before.startAt != after.startAt) add(ImportFieldDiff("start", before.startAt, after.startAt))
      if (before.endAt != after.endAt) add(ImportFieldDiff("end", before.endAt ?: "", after.endAt ?: ""))
      if (before.timezone != after.timezone) add(ImportFieldDiff("timezone", before.timezone, after.timezone))
      if (before.location != after.location) {
        add(ImportFieldDiff("location", before.location.importDisplay(), after.location.importDisplay()))
      }
    }

  fun chooseDestination(destination: ImportDestination) {
    val proposalId = store.state.calendar.importState.proposalOrNull()?.proposalId
    if (proposalId != null && destination is ImportDestination.ExistingHub) {
      destinationAudienceByHub[destination.hubId]?.let { knownAudience[proposalId] = it }
      knownDestinationVersion[proposalId] = destinationVersionByHub[destination.hubId]
    }
    store.dispatch(ChooseImportDestination(destination))
  }
  fun proceedToAudience() = store.dispatch(ProceedImportToAudience)
  fun setAudience(visibility: HubVisibilityChoice, audience: List<String>) = store.dispatch(SetImportAudience(visibility, audience))
  fun proceedToConfirm() = store.dispatch(ProceedImportToConfirm)
  fun back() = store.dispatch(BackImportStep)
  fun reconfirm() = store.dispatch(ReconfirmCalendarImport).also { confirm() }

  fun discard() {
    val proposalId = store.state.calendar.importState.proposalOrNull()?.proposalId
    store.dispatch(DiscardCalendarImport)
    if (proposalId != null) {
      sourceSnapshots.remove(proposalId)
      knownAudience.remove(proposalId)
      knownDestinationVersion.remove(proposalId)
      scope.launch {
        withContext(databaseDispatcher) {
          contentStore.deleteCalendarImport(proposalId)
          contentStore.deleteCalendarBindingForSubject(provisionalImportSubject(proposalId))
        }
      }
    }
  }

  private val resumeMutex = Mutex()

  /** Replays one crash/offline-held proposal after connectivity returns. Target ids and the
   * review-start fingerprint are reused; no outbox op is emitted before both source and
   * destination revalidation run again. */
  suspend fun resumePending() = resumeMutex.withLock {
    if (!isOnline()) return@withLock
    val currentState = store.state.calendar.importState
    if (currentState is ImportProposalState.OfflineQueued && currentState.ids != null) {
      val status = withContext(databaseDispatcher) {
        contentStore.calendarImportStatus(currentState.proposal.proposalId)
      }
      val destination = currentState.proposal.destination
      if (status == "saved" && destination != null) {
        publishSaved(currentState.proposal, destination)
        return@withLock
      }
    }
    when (currentState) {
      ImportProposalState.None,
      is ImportProposalState.OfflineQueued,
      is ImportProposalState.PermissionLost -> Unit
      else -> return@withLock
    }
    val pending = withContext(databaseDispatcher) { contentStore.unresolvedCalendarImports().firstOrNull() }
      ?: return@withLock
    val binding = withContext(databaseDispatcher) {
      contentStore.calendarBindingBySubjectKey(provisionalImportSubject(pending.proposal.proposalId))
    } ?: return@withLock
    val eventId = binding.platformEventId ?: return@withLock
    val calendarId = binding.calendarId ?: return@withLock
    val proposal = pending.proposal.copy(destination = pending.destination)
    sourceSnapshots[proposal.proposalId] = observationFromPersistedProposal(proposal, eventId, calendarId)
    knownAudience[proposal.proposalId] = pending.audienceIds
    knownDestinationVersion[proposal.proposalId] = pending.hubVersion
    store.dispatch(StartCalendarImport(proposal.copy(destination = null)))
    store.dispatch(ChooseImportDestination(pending.destination))
    store.dispatch(ProceedImportToAudience)
    store.dispatch(ProceedImportToConfirm)
    confirmCurrent()
  }

  fun stop() {
    // No standing subscriptions — every confirm() is a bounded, one-shot suspend call on [scope].
  }
}

private fun provisionalImportSubject(proposalId: String): String = "calendarImport:$proposalId"

private fun normalizeImportTitle(value: String): String = value.trim().replace(Regex("""\s+"""), " ")

private fun CandidateLocation?.importDisplay(): String = this?.let {
  listOfNotNull(it.label?.takeIf(String::isNotBlank), it.address?.takeIf(String::isNotBlank)).joinToString(", ")
}.orEmpty()

private fun observationFromPersistedProposal(
  proposal: CalendarImportProposal,
  platformEventId: String,
  calendarId: String,
): CalendarEventObservation = CalendarEventObservation(
  platformEventId = platformEventId,
  calendarId = calendarId,
  title = proposal.title,
  startAt = proposal.start.wire,
  endAt = proposal.end?.wire,
  allDay = proposal.start is EventInstant.AllDay,
  timezone = proposal.timezone ?: "UTC",
  location = proposal.location?.let { CandidateLocation(label = it.label, address = it.address) },
  isRecurring = false,
)
