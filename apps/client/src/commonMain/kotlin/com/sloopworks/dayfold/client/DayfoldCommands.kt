package com.sloopworks.dayfold.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.reduxkotlin.Store

/**
 * Compose-free application command boundary.
 *
 * Every method copies the complete tap-time arguments before launching work. Platform UI objects
 * are intentionally absent; hosts finish native UI and pass immutable results such as an ID token.
 */
class DayfoldCommands internal constructor(
  private val store: Store<AppState>,
  private val scope: CoroutineScope? = null,
  private val authEngine: AuthEngine? = null,
  private val syncCoordinator: SyncCoordinator? = null,
  private val hubEngine: HubEngine? = null,
  private val searchEngine: SearchEngine? = null,
  private val nowEngine: NowEngine? = null,
  private val responseEngine: ResponseEngine? = null,
  private val contentStore: ContentStore? = null,
  private val sessionCoordinator: SessionCoordinator? = null,
  private val externalHubTargets: PendingExternalHubTargetCoordinator? = null,
  private val calendarCheckEngine: CalendarCheckEngine? = null,
  private val calendarImportEngine: CalendarImportEngine? = null,
  private val foregroundNotifier: LocalNotifier? = null,
  private val familyWorkLauncher: ((FamilySessionContext, suspend (FamilySessionContext) -> Unit) -> Boolean)? = null,
  private val bindSelectedFamily: suspend () -> Unit = {},
  private val beforeSearchNavigationCommit: suspend () -> Unit = {},
) : DayfoldCommandPort {
  companion object {
    private val EMPTY_SEARCH_STATUS = MutableStateFlow(SearchStatus())

    /** Creates a side-effect-free command set that still performs pure Redux navigation. */
    fun navigationOnly(store: Store<AppState>): DayfoldCommands = DayfoldCommands(store)
  }

  /** Dispatches one already-complete pure Redux intent. */
  fun dispatch(action: Action) { store.dispatch(action) }

  override val searchStatus: StateFlow<SearchStatus>
    get() = searchEngine?.status ?: EMPTY_SEARCH_STATUS

  override suspend fun search(query: String): SearchResponse =
    searchEngine?.search(query) ?: SearchResponse(
      requestGeneration = 0,
      bindingGeneration = searchStatus.value.bindingGeneration,
      corpusGeneration = searchStatus.value.corpusGeneration,
      readiness = searchStatus.value.readiness,
      failure = SearchFailure.UNAVAILABLE,
    )

  override fun openSearchResult(handle: SearchResultHandle) = launchEffect {
    val resolved = searchEngine?.resolve(handle) ?: return@launchEffect
    beforeSearchNavigationCommit()
    val destination = resolved.destination
    when (destination.kind) {
      SearchDestinationKind.CARD -> {
        val cardId = destination.cardId ?: return@launchEffect
        sessionCoordinator?.commitIfCurrent(resolved.context) {
          // Search's DB collector may publish before the ordinary Redux bridge at startup.
          // Publish the same canonical projection first so detail never no-ops or renders blank.
          store.dispatch(CardsLoaded(resolved.cards))
          store.dispatch(OpenDetailFromSearch(cardId))
        }
      }
      SearchDestinationKind.HUB,
      SearchDestinationKind.SECTION,
      SearchDestinationKind.BLOCK,
      -> {
        val hubId = destination.hubId ?: return@launchEffect
        val arrival = when (destination.kind) {
          SearchDestinationKind.HUB -> HubArrival(HubArrivalLevel.HUB, hubId, HubArrivalSource.SEARCH)
          SearchDestinationKind.SECTION -> HubArrival(
            HubArrivalLevel.SECTION,
            destination.sectionId ?: return@launchEffect,
            HubArrivalSource.SEARCH,
          )
          SearchDestinationKind.BLOCK -> HubArrival(
            HubArrivalLevel.BLOCK,
            destination.blockId ?: return@launchEffect,
            HubArrivalSource.SEARCH,
          )
          SearchDestinationKind.CARD -> error("handled above")
        }
        hubEngine?.openHub(
          context = resolved.context,
          hubId = hubId,
          returnDestination = HubReturnDestination.SEARCH,
          arrival = arrival,
        )
      }
    }
  }

  /** Completes provider authentication from an immutable host-produced token. */
  fun signIn(provider: String, idToken: String) {
    launchEffect {
      authEngine?.signIn(provider, FirebaseSignIn { idToken })
      bindSelectedFamily()
    }
  }

  /** Explicit debug/emulator provider exchange; never used as a native-UI cancellation fallback. */
  fun signInWithDevProvider(provider: String) {
    launchEffect {
      authEngine?.signIn(provider, providerSignIn = null)
      bindSelectedFamily()
    }
  }

  fun devSignIn() = launchEffect { authEngine?.devSignIn(); bindSelectedFamily() }
  override fun retryAuth() = launchEffect { authEngine?.restore(); bindSelectedFamily() }
  override fun createFamily(name: String) = launchIdentity {
    authEngine?.createFamily(it, name)
    bindSelectedFamily()
  }
  override fun signOut() = launchIdentity { authEngine?.signOut(it) }
  override fun redeemInvite(token: String) = launchIdentity {
    authEngine?.redeemInvite(it, token)
    bindSelectedFamily()
  }
  fun openInviteLink(raw: String) {
    val context = sessionCoordinator?.authSnapshot()
    launchEffect {
      authEngine?.openInviteLink(raw, context)
      bindSelectedFamily()
    }
  }
  fun openDeviceLink(raw: String) {
    val context = sessionCoordinator?.authSnapshot()
    launchEffect { authEngine?.openDeviceLink(raw, context) }
  }

  override fun loadApprovals(familyId: String) = launchFamily(familyId) { authEngine?.loadApprovals(it, familyId) }
  override fun approveMember(familyId: String, userId: String) =
    launchFamily(familyId) { authEngine?.approveMember(it, userId) }
  override fun declineMember(familyId: String, userId: String) =
    launchFamily(familyId) { authEngine?.declineMember(it, userId) }
  override fun loadMembers(familyId: String) = launchFamily(familyId) { authEngine?.loadMembers(it, familyId) }
  override fun removeMember(familyId: String, userId: String) =
    launchFamily(familyId) { authEngine?.removeMember(it, familyId, userId) }
  override fun mintInvite(familyId: String, mode: String) =
    launchFamily(familyId) { authEngine?.mintInvite(it, mode) }
  override fun revokeInvite(familyId: String, inviteId: String) =
    launchFamily(familyId) { authEngine?.revokeInvite(it, inviteId) }

  override fun updateAvatar(color: String?, avatarRef: String?) = launchIdentity {
    authEngine?.updateAvatar(it, color, avatarRef)
  }
  override fun updateDisplayName(name: String) = launchIdentity { authEngine?.updateDisplayName(it, name) }
  override fun loadDevices() = launchIdentity { authEngine?.loadDevices(it) }
  override fun revokeDevice(deviceId: String) = launchIdentity { authEngine?.revokeDevice(it, deviceId) }
  override fun lookupDevice(userCode: String) = launchIdentity { authEngine?.lookupDevice(it, userCode) }
  override fun approveDevice(familyId: String, userCode: String, hubIds: List<String>?) {
    val selectedHubIds = hubIds?.toList()
    launchIdentity { authEngine?.approveDevice(it, familyId, userCode, selectedHubIds) }
  }
  override fun denyDevice(familyId: String, userCode: String) =
    launchIdentity { authEngine?.denyDevice(it, familyId, userCode) }

  override fun refresh() { syncCoordinator?.requestSync(SyncReason.MANUAL_REFRESH) }
  override fun loadHubs() { syncCoordinator?.requestSync(SyncReason.MANUAL_REFRESH) }
  override fun nowShown(subjectKeys: Set<String>) {
    val captured = subjectKeys.toSet()
    foregroundNotifier?.let { cancelForegroundVisible(it, captured) }
    nowEngine?.noteShown(captured)
  }

  /** Opens the list with its return destination represented by one atomic Redux action. */
  override fun openHubs(returnDestination: HubReturnDestination) {
    store.dispatch(OpenHubs(returnDestination))
    loadHubs()
  }

  /** Opens one Hub using the family generation and IDs captured at the command edge. */
  override fun openHub(
    familyId: String,
    hubId: String,
    arrival: HubArrival?,
    returnDestination: HubReturnDestination,
  ) {
    if (hubEngine == null) {
      store.dispatch(OpenHubs(returnDestination))
      return
    }
    val initial = sessionCoordinator?.familySnapshot(familyId)
    if (initial == null) {
      Log.w("commands") { "Hub open rejected without a current family" }
      return
    }
    launchEffect {
      if (!sessionCoordinator.isCurrent(initial)) return@launchEffect
      if (!hubEngine.openHub(
          context = initial,
          hubId = hubId,
          returnDestination = returnDestination,
          arrival = arrival,
        )) {
        Log.w("commands") { "Hub open rejected by family admission" }
      }
    }
  }

  /**
   * Routes a platform notification target now or retains it until family restoration completes.
   * The newest target wins while no family is bound; warm targets are handled in submission order.
   */
  fun openExternalHub(
    target: DeepLinkTarget,
    onDiscarded: () -> Unit = {},
    onAdmitted: () -> Unit = {},
  ) {
    val coordinator = externalHubTargets
    if (coordinator == null) {
      try {
        onDiscarded()
      } catch (error: Exception) {
        Log.e("commands", error) { "external Hub discard acknowledgement failed" }
      }
    } else {
      coordinator.submit(target, onDiscarded, onAdmitted)
    }
  }

  /**
   * Atomically navigates away from [expectedHubId], then performs correlated subscription cleanup.
   * A delayed cleanup for Hub A cannot cancel the collector installed for Hub B.
   */
  override fun closeHub(expectedHubId: String, destination: HubReturnDestination) {
    if (store.state.hubs.currentHubId != expectedHubId) return
    val expectedRequest = store.state.hubs.currentHubRequest
    store.dispatch(
      when (destination) {
        HubReturnDestination.FEED_DETAIL -> CloseHubToFeed
        HubReturnDestination.SEARCH -> CloseHubToSearch
        HubReturnDestination.HUB_LIST -> CloseHub
      },
    )
    launchEffect { hubEngine?.closeHub(expectedHubId, expectedRequest) }
  }

  override fun loadAudience(familyId: String, hubId: String) =
    launchFamily(familyId) { hubEngine?.loadAudience(it, hubId) }
  override fun setHubRole(familyId: String, hubId: String, userId: String, role: String) =
    launchFamily(familyId) { hubEngine?.setParticipant(it, hubId, userId, role) }
  override fun removeHubParticipant(familyId: String, hubId: String, userId: String) =
    launchFamily(familyId) { hubEngine?.removeParticipant(it, hubId, userId) }
  override fun setHubVisibility(familyId: String, hubId: String, visibility: String) =
    launchFamily(familyId) { hubEngine?.setVisibility(it, hubId, visibility) }
  override fun toggleItem(familyId: String, blockId: String, itemId: String, done: Boolean) =
    launchFamily(familyId) { hubEngine?.toggleItem(it, blockId, itemId, done) }
  override fun retryBlock(familyId: String, blockId: String) =
    launchFamily(familyId) { hubEngine?.retryBlock(it, blockId) }
  override fun deleteBlock(familyId: String, blockId: String) =
    launchFamily(familyId) { hubEngine?.deleteBlock(it, blockId) }
  override fun hideBlock(familyId: String, blockId: String) =
    launchFamily(familyId) { hubEngine?.hideBlock(it, blockId) }
  override fun unhideBlock(familyId: String, blockId: String) =
    launchFamily(familyId) { hubEngine?.unhideBlock(it, blockId) }
  override fun setNotificationConfig(config: NotifConfig) = launchEffect { contentStore?.setNotifConfig(config) }

  // ADR 0064 — smart-content responses. Null engine = the navigation-only command set (used by
  // previews and pure-nav hosts), where a response write is a no-op rather than a crash.
  override fun mute(
    subjectRef: String,
    matchScope: MatchScope,
    audience: AudienceScope,
    label: String,
    sublabel: String?,
  ) = launchCurrentFamily {
    responseEngine?.mute(it, subjectRef, matchScope, audience, label, sublabel)
  }

  override fun markDone(subjectRef: String, label: String, note: String?) =
    launchCurrentFamily { responseEngine?.markDone(it, subjectRef, label, note) }

  override fun removeResponse(id: String) = launchCurrentFamily { responseEngine?.removeResponse(it, id) }

  override fun undoLastResponse() = launchCurrentFamily { responseEngine?.undoLastResponse(it) }

  override fun recordResponseOffer(subjectRef: String) =
    launchCurrentFamily { responseEngine?.recordResponseOffer(it, subjectRef) }

  override fun setCalendarEnabled(enabled: Boolean) { calendarCheckEngine?.setEnabled(enabled) }
  override fun setSelectedCalendars(calendarIds: Set<String>) { calendarCheckEngine?.setSelectedCalendars(calendarIds) }
  override fun loadAvailableCalendars() { calendarCheckEngine?.loadAvailableCalendars() }
  override fun requestCalendarPermission() { calendarCheckEngine?.requestPermission() }
  override fun startCalendarCheck() { calendarCheckEngine?.startCheck() }
  override fun resetLocalCalendarMatches() = launchEffect { calendarCheckEngine?.resetLocalMatches() }
  override fun setCalendarNotificationOwner(subjectKey: String, owner: CalendarNotificationOwner) =
    launchEffect { calendarCheckEngine?.setNotificationOwner(subjectKey, owner) }
  override fun openCalendarEventEditor(prefill: EventPrefill) { calendarCheckEngine?.openEventEditor(prefill) }
  override fun confirmCalendarMatch(subjectKey: String, eventId: String) = launchEffect { calendarCheckEngine?.confirmMatch(subjectKey, eventId) }
  override fun keepCalendarSeparate(subjectKey: String) { store.dispatch(KeepSeparate(subjectKey)) }
  override fun resolveAmbiguousCalendarMatch(subjectKey: String, chosenEventId: String) = launchEffect { calendarCheckEngine?.resolveAmbiguous(subjectKey, chosenEventId) }
  override fun ignoreCalendarItem(itemKey: String) { store.dispatch(IgnoreItem(itemKey)) }
  override fun undoCalendarIgnore(itemKey: String) { store.dispatch(UndoIgnore(itemKey)) }
  override fun chooseCalendarField(subjectKey: String, field: String, resolution: FieldResolution) { store.dispatch(FieldChoice(subjectKey, field, resolution)) }
  override fun keepCalendarSeriesOnly(subjectKey: String) { store.dispatch(KeepSeriesCalendarOnly(subjectKey)) }
  // CAL-10 (ADR 0063 §6) — startCalendarImport/confirmCalendarImport/reconfirmCalendarImport are
  // engine-owned effects (id minting, revalidation, DB/outbox writes); the rest are pure wizard-
  // navigation dispatched directly by the engine (a thin passthrough here, same as DiscardCalendarImport).
  override fun startCalendarImport(observation: CalendarEventObservation) { calendarImportEngine?.startImport(observation) }
  override fun chooseImportDestination(destination: ImportDestination) { calendarImportEngine?.chooseDestination(destination) }
  override fun setImportDescriptionOptIn(description: String?) { calendarImportEngine?.setDescriptionOptIn(description) }
  override fun proceedImportToAudience() { calendarImportEngine?.proceedToAudience() }
  override fun setImportAudience(visibility: HubVisibilityChoice, audience: List<String>) { calendarImportEngine?.setAudience(visibility, audience) }
  override fun proceedImportToConfirm() { calendarImportEngine?.proceedToConfirm() }
  override fun backImportStep() { calendarImportEngine?.back() }
  override fun confirmCalendarImport() { calendarImportEngine?.confirm() }
  override fun reconfirmCalendarImport() { calendarImportEngine?.reconfirm() }
  override fun discardCalendarImport() { calendarImportEngine?.discard() }

  private fun launchIdentity(block: suspend (AuthSessionContext) -> Unit) {
    val context = sessionCoordinator?.authSnapshot() ?: return
    launchEffect { if (sessionCoordinator.isCurrent(context)) block(context) }
  }

  private fun launchFamily(familyId: String, block: suspend (FamilySessionContext) -> Unit) {
    val context = sessionCoordinator?.familySnapshot(familyId) ?: return
    launchEffect { if (sessionCoordinator.isCurrent(context)) block(context) }
  }

  /** Captures the selected tenant at the command edge and submits only to its replaceable scope. */
  private fun launchCurrentFamily(block: suspend (FamilySessionContext) -> Unit) {
    val familyId = store.state.session.activeFamilyId ?: return
    val context = sessionCoordinator?.familySnapshot(familyId) ?: return
    val launcher = familyWorkLauncher
    if (launcher != null) {
      if (!launcher(context, block)) Log.w("commands") { "Response write rejected by family admission" }
    } else {
      // Unit-test/legacy construction has no runtime family owner; currentness still fences it.
      launchEffect { if (sessionCoordinator.isCurrent(context)) block(context) }
    }
  }

  private fun launchEffect(block: suspend () -> Unit) { scope?.launch { block() } }
}
