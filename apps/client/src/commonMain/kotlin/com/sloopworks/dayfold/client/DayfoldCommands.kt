package com.sloopworks.dayfold.client

import kotlinx.coroutines.CoroutineScope
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
  private val nowEngine: NowEngine? = null,
  private val responseEngine: ResponseEngine? = null,
  private val contentStore: ContentStore? = null,
  private val sessionCoordinator: SessionCoordinator? = null,
  private val externalHubTargets: PendingExternalHubTargetCoordinator? = null,
  private val calendarCheckEngine: CalendarCheckEngine? = null,
  private val calendarImportEngine: CalendarImportEngine? = null,
  private val bindSelectedFamily: suspend () -> Unit = {},
) : DayfoldCommandPort {
  companion object {
    /** Creates a side-effect-free command set that still performs pure Redux navigation. */
    fun navigationOnly(store: Store<AppState>): DayfoldCommands = DayfoldCommands(store)
  }

  /** Dispatches one already-complete pure Redux intent. */
  fun dispatch(action: Action) { store.dispatch(action) }

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
  override fun deleteAccount() = deleteAccount(afterRemoteDelete = {})

  /**
   * Deletes the server account, then lets the host revoke/delete its native identity
   * credential before the shared terminal cleanup clears the local session.
   */
  fun deleteAccount(afterRemoteDelete: suspend () -> Unit) = launchIdentity {
    authEngine?.deleteAccount(it, afterRemoteDelete)
  }
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
  override fun nowShown(subjectKeys: Set<String>) { nowEngine?.noteShown(subjectKeys.toSet()) }

  /** Opens the list with its return destination represented by one atomic Redux action. */
  override fun openHubs(returnDestination: HubReturnDestination) {
    store.dispatch(OpenHubs(returnDestination))
    loadHubs()
  }

  /** Opens one Hub using the family generation and IDs captured at the command edge. */
  override fun openHub(
    familyId: String,
    hubId: String,
    focusBlockId: String?,
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
      if (!hubEngine.openHub(initial, hubId, focusBlockId, returnDestination)) {
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
    store.dispatch(if (destination == HubReturnDestination.FEED_DETAIL) CloseHubToFeed else CloseHub)
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
  ) = launchEffect { responseEngine?.mute(subjectRef, matchScope, audience, label, sublabel) }

  override fun markDone(subjectRef: String, label: String, note: String?) =
    launchEffect { responseEngine?.markDone(subjectRef, label, note) }

  override fun removeResponse(id: String) = launchEffect { responseEngine?.removeResponse(id) }

  override fun undoLastResponse() = launchEffect { responseEngine?.undoLastResponse() }

  override fun recordResponseOffer(subjectRef: String) =
    launchEffect { responseEngine?.recordResponseOffer(subjectRef) }

  override fun setCalendarEnabled(enabled: Boolean) { calendarCheckEngine?.setEnabled(enabled) }
  override fun enableCalendarCheck(calendarIds: Set<String>) { calendarCheckEngine?.enable(calendarIds) }
  override fun setSelectedCalendars(calendarIds: Set<String>) { calendarCheckEngine?.setSelectedCalendars(calendarIds) }
  override fun loadAvailableCalendars() { calendarCheckEngine?.loadAvailableCalendars() }
  override fun requestCalendarPermission() { calendarCheckEngine?.requestPermission() }
  override fun startCalendarCheck() { calendarCheckEngine?.startCheck() }
  override fun resetLocalCalendarMatches() = launchEffect { calendarCheckEngine?.resetLocalMatches() }
  override fun setCalendarNotificationOwner(subjectKey: String, owner: CalendarNotificationOwner) =
    launchEffect { calendarCheckEngine?.setNotificationOwner(subjectKey, owner) }
  override fun openCalendarEventEditor(prefill: EventPrefill) { calendarCheckEngine?.openEventEditor(prefill) }
  override fun openObservedCalendarEvent(platformEventId: String) { calendarCheckEngine?.openObservedEvent(platformEventId) }
  override fun openMatchedCalendarEvent(subjectKey: String) { calendarCheckEngine?.openMatchedEvent(subjectKey) }
  override fun unlinkCalendarMatch(subjectKey: String) = launchEffect { calendarCheckEngine?.unlinkMatch(subjectKey) }
  override fun confirmCalendarMatch(subjectKey: String, eventId: String) = launchEffect { calendarCheckEngine?.confirmMatch(subjectKey, eventId) }
  override fun keepCalendarSeparate(subjectKey: String) = launchEffect { calendarCheckEngine?.keepSeparate(subjectKey) }
  override fun resolveAmbiguousCalendarMatch(subjectKey: String, chosenEventId: String) = launchEffect { calendarCheckEngine?.resolveAmbiguous(subjectKey, chosenEventId) }
  override fun ignoreCalendarItem(itemKey: String) = launchEffect { calendarCheckEngine?.ignoreItem(itemKey) }
  override fun undoCalendarIgnore(itemKey: String) = launchEffect { calendarCheckEngine?.undoIgnore(itemKey) }
  override fun chooseCalendarField(subjectKey: String, field: String, resolution: FieldResolution) =
    launchEffect { calendarCheckEngine?.resolveField(subjectKey, field, resolution) }
  override fun keepCalendarSeriesOnly(subjectKey: String) = launchEffect { calendarCheckEngine?.keepSeriesCalendarOnly(subjectKey) }
  // CAL-10 (ADR 0063 §6) — startCalendarImport/confirmCalendarImport/reconfirmCalendarImport are
  // engine-owned effects (id minting, revalidation, DB/outbox writes); the rest are pure wizard-
  // navigation dispatched directly by the engine (a thin passthrough here, same as DiscardCalendarImport).
  override fun startCalendarImport(observation: CalendarEventObservation) { calendarImportEngine?.startImport(observation) }
  override fun loadCalendarImportDestinations() { calendarImportEngine?.loadEligibleDestinations() }
  override fun chooseImportDestination(destination: ImportDestination) { calendarImportEngine?.chooseDestination(destination) }
  override fun setImportDescriptionIncluded(include: Boolean) { calendarImportEngine?.setDescriptionIncluded(include) }
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

  private fun launchEffect(block: suspend () -> Unit) { scope?.launch { block() } }
}
