package com.sloopworks.dayfold.client

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
  suspend fun eligibleDestinationHubs(): List<ImportDestination.ExistingHub> {
    val viewer = store.state.session.session?.userId ?: return emptyList()
    val out = mutableListOf<ImportDestination.ExistingHub>()
    for (hub in store.state.hubs.hubs) {
      if (hub.createdBy == viewer) {
        out += ImportDestination.ExistingHub(hub.id, hub.title, "co_owner")
        continue
      }
      val audience = loadHubAudience(hub.id)
      val role = audience?.members?.firstOrNull { it.uid == viewer }?.participationRole
      if (role == "contributor" || role == "co_owner") out += ImportDestination.ExistingHub(hub.id, hub.title, role)
    }
    return out
  }

  /** Review start — derives the normalized [CalendarImportProposal] from one calendar-only
   *  observation and starts the wizard (spec §1). Nothing calendar-identifying survives past this
   *  boundary: the proposal type has no field for [observation]'s platform/calendar ids. */
  fun startImport(observation: CalendarEventObservation) {
    val proposal = CalendarImportProposal(
      proposalId = idProvider(),
      title = observation.title,
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
  // observeEvents() read at confirm to detect drift (spec §3.5 state 6). In-memory only, like
  // CalendarCheckEngine's observations: never persisted raw (ADR 0063 §3).
  private val sourceSnapshots = mutableMapOf<String, CalendarEventObservation>()

  /** Confirm (spec §3.5) — revalidate, mint ids (once), persist the local record, and enqueue. */
  fun confirm() {
    scope.launch {
      val current = store.state.calendar.importState as? ImportProposalState.Confirming ?: return@launch
      val proposal = current.proposal
      val destination = proposal.destination ?: return@launch
      val source = sourceSnapshots[proposal.proposalId]

      if (calendarPort.permissionState() != CalendarPermission.Granted) {
        store.dispatch(ImportPermissionLost)
        return@launch
      }

      // §3.5 state 6 — re-read the source event; a changed fingerprint requires an explicit re-confirm.
      if (source != null) {
        val fresh = calendarPort.observeEvents(setOf(source.calendarId), CALENDAR_CHECK_HORIZON_DAYS)
          .firstOrNull { it.platformEventId == source.platformEventId }
        if (fresh != null && fingerprintOfObservation(fresh) != fingerprintOfObservation(source)) {
          sourceSnapshots[proposal.proposalId] = fresh
          val ids = withContext(databaseDispatcher) { mintOrReuseIds(proposal, destination) }
          store.dispatch(
            ImportSourceChanged(
              ids = ids,
              diffs = diffSourceFields(source, fresh),
              refreshedProposal = proposal.copy(
                title = fresh.title,
                start = if (fresh.allDay) EventInstant.AllDay(fresh.startAt) else EventInstant.Timed(fresh.startAt),
                end = fresh.endAt?.let { if (fresh.allDay) EventInstant.AllDay(it) else EventInstant.Timed(it) },
              ),
            ),
          )
          return@launch
        }
      }

      // §3.5 state 5/7 — re-read the destination Hub (client-side, from already-synced state; the
      // spec's own §3.4 "Honest limit" accepts this is not a fresh network read).
      if (destination is ImportDestination.ExistingHub) {
        val hub = store.state.hubs.hubs.firstOrNull { it.id == destination.hubId }
        if (hub == null) {
          store.dispatch(ImportRoleDenied)
          return@launch
        }
        val viewer = store.state.session.session?.userId
        val audience = loadHubAudience(destination.hubId)
        val role = audience?.members?.firstOrNull { it.uid == viewer }?.participationRole
        val stillPermitted = hub.createdBy == viewer || role == "contributor" || role == "co_owner"
        if (!stillPermitted) {
          store.dispatch(ImportRoleDenied)
          return@launch
        }
        val namedAudience = audience?.members?.filter { it.permitted }?.map { it.uid } ?: emptyList()
        val baseline = knownAudience[proposal.proposalId]
        if (baseline != null && namedAudience.toSet() != baseline) {
          knownAudience[proposal.proposalId] = namedAudience.toSet()
          val ids = withContext(databaseDispatcher) { mintOrReuseIds(proposal, destination) }
          store.dispatch(ImportVersionConflict(ids, namedAudience))
          return@launch
        }
        knownAudience[proposal.proposalId] = namedAudience.toSet()
      }

      val ids = withContext(databaseDispatcher) { mintOrReuseIds(proposal, destination) }
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
        return@launch
      }

      withContext(databaseDispatcher) {
        contentStore.setCalendarImportStatus(proposal.proposalId, "applying", nowIso)
        contentStore.enqueueImportOps(ops, nowIso)
      }
      store.dispatch(ImportApplyStarted(ids))
      syncEngine.requestSync(SyncReason.OUTBOX_MUTATION)
      pollApply(proposal, destination, ids, ops.map { it.opId })
    }
  }

  // Confirm-time audience snapshot per proposal — the state-7 precondition (spec §3.4/§3.5).
  private val knownAudience = mutableMapOf<String, Set<String>>()

  /** Ids are minted ONCE at first confirm and reused on every retry/re-confirm (spec §3.3). */
  private fun mintOrReuseIds(proposal: CalendarImportProposal, destination: ImportDestination): ImportOpIds {
    val existing = contentStore.calendarImportIds(proposal.proposalId)
    if (existing != null) return existing
    val hubId = if (destination is ImportDestination.NewHub) idProvider() else null
    val ids = ImportOpIds(hubId, idProvider(), List(importBlockCount(proposal)) { idProvider() })
    contentStore.upsertCalendarImport(
      proposalId = proposal.proposalId,
      proposalJson = "", destinationJson = "",
      hubId = ids.hubId, sectionId = ids.sectionId, blockIds = ids.blockIds,
      status = "applying", nowIso = nowProvider(),
    )
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
      val states = withContext(databaseDispatcher) { contentStore.outboxOpStates(opIds) }
      if (states.values.any { it == null }) {
        // A dropped op — role/absent/invisible (spec §3.5 state 5) is the only Drop path an import
        // op reaches (410/404 tombstone doesn't apply to a create; 412 is unreachable per §3.4).
        withContext(databaseDispatcher) { contentStore.deleteCalendarImport(proposal.proposalId) }
        store.dispatch(ImportRoleDenied)
        return
      }
      if (states.values.any { it == "failed" }) return // calm "will retry" — leave Applying, a later poll/manual retry resumes
      if (states.values.all { it == "acked" }) {
        withContext(databaseDispatcher) {
          bindOnAck(proposal, ids)
          contentStore.setCalendarImportStatus(proposal.proposalId, "saved", nowProvider())
        }
        store.dispatch(ImportSaved(audienceSummary(destination)))
        return
      }
    }
  }

  /** spec §3.6 — close the self-match loop: the imported milestone block is itself a
   *  DayfoldEventCandidate (it carries a `when.at` trigger), so without a binding it would
   *  re-surface on the very next reconcile as a Dayfold-only gap. */
  private fun bindOnAck(proposal: CalendarImportProposal, ids: ImportOpIds) {
    val source = sourceSnapshots[proposal.proposalId] ?: return
    val hubId = ids.hubId ?: (store.state.calendar.importState.proposalOrNull()?.destination as? ImportDestination.ExistingHub)?.hubId ?: return
    val subjectKey = SubjectRef.node(hubId, ids.sectionId, ids.blockIds.first())
    val nowIso = nowProvider()
    contentStore.upsertCalendarBinding(
      CalendarBinding(
        subjectKey = subjectKey,
        sourceVersion = proposal.proposalId,
        platformEventId = source.platformEventId,
        calendarId = source.calendarId,
        fingerprint = fingerprintOfObservation(source),
        lastSeenAt = nowIso,
        relation = CalendarRelation.MATCHED,
        notificationOwner = CalendarNotificationOwner.CALENDAR,
        reviewState = null,
        createdAt = nowIso,
        updatedAt = nowIso,
      ),
    )
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
    }

  fun chooseDestination(destination: ImportDestination) = store.dispatch(ChooseImportDestination(destination))
  fun setDescriptionOptIn(description: String?) = store.dispatch(SetImportDescriptionOptIn(description))
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
      scope.launch { withContext(databaseDispatcher) { contentStore.deleteCalendarImport(proposalId) } }
    }
  }

  fun stop() {
    // No standing subscriptions — every confirm() is a bounded, one-shot suspend call on [scope].
  }
}
