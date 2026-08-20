package com.sloopworks.dayfold.client

import kotlinx.coroutines.flow.StateFlow

/**
 * UI-facing commands that may cross a Compose host boundary.
 *
 * The port exposes user intents, not the Redux store, engines, coroutine scopes, or mutable runtime
 * state. Implementations must have stable identity and capture every argument at the call edge before
 * starting asynchronous work. Platform-only authentication and deep-link admission stay on
 * [DayfoldCommands], outside this UI port.
 */
interface DayfoldCommandPort {
  /** Query-free engine lifecycle; raw search text never enters Redux or this stream. */
  val searchStatus: StateFlow<SearchStatus>
  suspend fun search(query: String): SearchResponse
  fun openSearchResult(handle: SearchResultHandle)
  fun retryAuth()
  fun createFamily(name: String)
  fun signOut()
  fun redeemInvite(token: String)
  fun loadApprovals(familyId: String)
  fun approveMember(familyId: String, userId: String)
  fun declineMember(familyId: String, userId: String)
  fun loadMembers(familyId: String)
  fun removeMember(familyId: String, userId: String)
  fun mintInvite(familyId: String, mode: String)
  fun revokeInvite(familyId: String, inviteId: String)
  fun updateAvatar(color: String?, avatarRef: String?)
  fun updateDisplayName(name: String)
  fun loadDevices()
  fun revokeDevice(deviceId: String)
  fun lookupDevice(userCode: String)
  fun approveDevice(familyId: String, userCode: String, hubIds: List<String>?)
  fun denyDevice(familyId: String, userCode: String)
  fun refresh()
  fun loadHubs()
  fun nowShown(subjectKeys: Set<String>)
  fun openHubs(returnDestination: HubReturnDestination = HubReturnDestination.HUB_LIST)
  fun openHub(
    familyId: String,
    hubId: String,
    arrival: HubArrival? = null,
    returnDestination: HubReturnDestination = HubReturnDestination.HUB_LIST,
  )
  fun closeHub(expectedHubId: String, destination: HubReturnDestination)
  fun loadAudience(familyId: String, hubId: String)
  fun setHubRole(familyId: String, hubId: String, userId: String, role: String)
  fun removeHubParticipant(familyId: String, hubId: String, userId: String)
  fun setHubVisibility(familyId: String, hubId: String, visibility: String)
  fun toggleItem(familyId: String, blockId: String, itemId: String, done: Boolean)
  fun retryBlock(familyId: String, blockId: String)
  fun deleteBlock(familyId: String, blockId: String)
  fun hideBlock(familyId: String, blockId: String)
  fun unhideBlock(familyId: String, blockId: String)
  fun setNotificationConfig(config: NotifConfig)
  // ADR 0064 — smart-content responses. Method-only, like the rest of this port: it crosses
  // into Swift as the exported framework's surface.
  fun mute(subjectRef: String, matchScope: MatchScope, audience: AudienceScope, label: String, sublabel: String?)
  fun markDone(subjectRef: String, label: String, note: String?)
  fun removeResponse(id: String)
  fun undoLastResponse()
  fun recordResponseOffer(subjectRef: String)
  // WI-447 (ADR 0063) — Calendar Check settings/permission/prefill/return surfaces. Real Android/
  // iOS permission + observation wiring is a later platform WI; these are inert without an engine.
  fun setCalendarEnabled(enabled: Boolean)
  fun setSelectedCalendars(calendarIds: Set<String>)
  fun loadAvailableCalendars()
  fun requestCalendarPermission()
  fun startCalendarCheck()
  fun resetLocalCalendarMatches()
  fun setCalendarNotificationOwner(subjectKey: String, owner: CalendarNotificationOwner)
  fun openCalendarEventEditor(prefill: EventPrefill)
  // WI-446 (ADR 0063 §4/§5) — the review-flow verbs over an in-progress CalendarCheckState.check
  // pass. confirmCalendarMatch/resolveAmbiguousCalendarMatch persist a binding (engine-owned);
  // the rest are pure local review decisions (reducer-owned, dispatched directly).
  fun confirmCalendarMatch(subjectKey: String, eventId: String)
  fun keepCalendarSeparate(subjectKey: String)
  fun resolveAmbiguousCalendarMatch(subjectKey: String, chosenEventId: String)
  fun ignoreCalendarItem(itemKey: String)
  fun undoCalendarIgnore(itemKey: String)
  fun chooseCalendarField(subjectKey: String, field: String, resolution: FieldResolution)
  fun keepCalendarSeriesOnly(subjectKey: String)
  // CAL-10 (ADR 0063 §6, calendar-import-contract-design.md) — the reviewed Calendar→Dayfold
  // import wizard. startCalendarImport begins a review from a calendar-only observation;
  // confirmCalendarImport mints ids + revalidates + enqueues (engine-owned); the rest are pure
  // wizard-navigation/field edits (reducer-owned, dispatched directly).
  fun startCalendarImport(observation: CalendarEventObservation)
  fun chooseImportDestination(destination: ImportDestination)
  fun setImportDescriptionOptIn(description: String?)
  fun proceedImportToAudience()
  fun setImportAudience(visibility: HubVisibilityChoice, audience: List<String>)
  fun proceedImportToConfirm()
  fun backImportStep()
  fun confirmCalendarImport()
  fun reconfirmCalendarImport()
  fun discardCalendarImport()
}
