package com.sloopworks.dayfold.client

import org.reduxkotlin.Store
import org.reduxkotlin.applyMiddleware
import org.reduxkotlin.compose
import org.reduxkotlin.middleware
import org.reduxkotlin.devtools.DevToolsConfig
import org.reduxkotlin.devtools.devTools
import org.reduxkotlin.concurrent.createConcurrentStore

// The route gate (pure): derived from (session, families). Family-null is a Feed
// substate, not a route. No session → SignIn; session + an active membership →
// Feed; session but only pending/none → CreateFamily (slice-1: the only way in is
// to create a family; invitee-join is slice 2).
fun routeFor(session: Session?, families: List<FamilyMembership>): Route = when {
  session == null -> Route.SignIn
  families.any { it.status == "active" } -> Route.Feed
  else -> Route.CreateFamily
}

fun activeFamilyIdFor(families: List<FamilyMembership>): String? =
  families.firstOrNull { it.status == "active" }?.familyId

// S6-D [C2]: a device grant can only be approved against a family the caller
// OWNS (a member-family approve → 403). The AuthorizeDevice family selector lists
// these; an empty result means the caller can't approve at all.
fun ownerFamiliesFor(families: List<FamilyMembership>): List<FamilyMembership> =
  families.filter { it.role == "owner" && it.status == "active" }

// Hand-written root reducer (locked decision: no combineReducers). Card data
// arrives only via CardsLoaded (DB→store bridge); sync actions carry status only.
// Auth actions (S5) recompute route/activeFamilyId from (session, families).
fun rootReducer(state: AppState, action: Any): AppState = when (action) {
  is SyncStarted -> state.copy(syncing = true, error = null)
  is SyncSucceeded -> state.copy(syncing = false, error = null)
  is SyncFailed -> state.copy(syncing = false, error = action.message)
  is CardsLoaded ->                                    // DB is truth → full replace;
    state.copy(                                         // prune nav stack of synced-away ids
      cards = action.cards,
      detailStack = state.detailStack.filter { id -> action.cards.any { it.id == id } },
    )
  is NavToDetail ->                                     // push, dedup re-tap of top;
    // only navigate to a card we actually have — a dangling related-edge targetId
    // (target not in the family cache) is a no-op, not a jarring dump to the feed.
    if (state.detailStack.lastOrNull() == action.cardId || state.cards.none { it.id == action.cardId }) state
    else state.copy(detailStack = state.detailStack + action.cardId)
  is NavBack -> state.copy(detailStack = state.detailStack.dropLast(1))
  // Post-recreation restore: set the saved stack verbatim. Cards are usually not yet
  // loaded here (fresh store), so we DON'T gate on card presence like NavToDetail —
  // CardsLoaded's filter (above) drops any id whose card never comes back.
  is RestoreDetailStack -> state.copy(detailStack = action.ids)
  is Back -> backAction(state)?.let { rootReducer(state, it) } ?: state

  // ── Hubs (ADR 0006 render · ADR 0030 visibility) ──
  is OpenHubs -> state.copy(route = Route.Hubs, currentHubId = null, currentHubTree = null, hubError = null, hubFromDetail = false)
  is OpenFeed -> state.copy(route = Route.Feed, hubFromDetail = false)
  is SetHubReturnToDetail -> state.copy(hubFromDetail = action.value)   // #299-followup: mark cross-surface deep-link origin
  // DB-fed via the SyncEngine hub bridge (one-writer-per-slice). Prunes currentHubId
  // + currentHubTree when the open hub is no longer in the DB (e.g. revocation tombstone).
  is HubsLoaded -> state.copy(
    hubs = action.hubs,
    hubsBusy = false,
    currentHubId = state.currentHubId?.takeIf { id -> action.hubs.any { it.id == id } },
    currentHubTree = if (state.currentHubId != null && action.hubs.none { it.id == state.currentHubId }) null else state.currentHubTree,
    timelineDetail = if (state.currentHubId != null && action.hubs.none { it.id == state.currentHubId }) null else state.timelineDetail,
  )
  is HubsFailed -> state.copy(hubsBusy = false, hubError = action.message)
  is OpenHub -> state.copy(currentHubId = action.hubId, currentHubTree = null, hubsBusy = true, hubError = null, hubFocusBlockId = null, showHidden = false, timelineDetail = null)
  is HubTreeLoaded -> state.copy(hubsBusy = false, currentHubTree = action.tree, hubError = null)
  is HubNotFound -> state.copy(hubsBusy = false, currentHubId = null, currentHubTree = null, hubError = "That hub is no longer available.", timelineDetail = null)
  is CloseHub -> state.copy(currentHubId = null, currentHubTree = null, hubFocusBlockId = null, showHidden = false, timelineDetail = null, hubFromDetail = false)
  // Cross back to the Feed card detail this hub was deep-linked from: route → Feed (the detailStack
  // card re-renders on ContentHost), and clear the hub substate + the origin flag.
  is CloseHubToFeed -> state.copy(route = Route.Feed, currentHubId = null, currentHubTree = null, hubFocusBlockId = null, showHidden = false, timelineDetail = null, hubFromDetail = false)
  is OpenTimelineDetail -> state.copy(timelineDetail = action.scale)  // ADR 0045 — open the timeline detail overlay
  is CloseTimelineDetail -> state.copy(timelineDetail = null)         // ADR 0045 — close the timeline detail overlay
  is SetHubFocus -> state.copy(hubFocusBlockId = action.blockId)
  is SetHubFilter -> state.copy(hubFilter = action.filter)
  // W5 hide (ADR 0038 §W5) — DB-fed hidden ids + the per-view "Show hidden" toggle.
  is HiddenLoaded -> state.copy(hiddenIds = action.ids)
  is SetShowHidden -> state.copy(showHidden = action.show)
  // ADR 0043 Phase A — DB→store bridges (sole writers; full replace, DB is truth).
  is NowContentLoaded -> state.copy(nowContent = action.content)
  is SurfacingLoaded -> state.copy(surfacing = action.records)
  is NotifConfigLoaded -> state.copy(notifConfig = action.config)              // ADR 0044 Phase B
  is LocationPermissionLoaded -> state.copy(locationPermission = action.state)
  is NotificationPermissionLoaded -> state.copy(notificationPermission = action.state)
  is OpenAudienceSheet -> state.copy(audienceSheetOpen = true, currentHubAudience = null, audienceError = null)
  is HubAudienceLoaded -> state.copy(currentHubAudience = action.audience, audienceError = null)
  is CloseAudienceSheet -> state.copy(audienceSheetOpen = false, currentHubAudience = null, audienceError = null)

  // ── auth / session (S5) ──
  is AuthRestoring -> state.copy(route = Route.Loading)
  is SessionRestored -> state.copy(
    session = action.session,
    route = if (action.session == null) Route.SignIn else Route.Loading, // whoami next
  )
  is SignInRequested -> state.copy(authBusy = true, authError = null, pendingProvider = action.provider)
  is SignInSucceeded -> state.copy(
    session = action.session, authBusy = false, authError = null, pendingProvider = null,
    route = Route.Loading,
  )
  is SignInFailed -> state.copy(authBusy = false, authError = action.message, pendingProvider = null)
  is SessionRotated -> state.copy(session = action.session)   // refresh-and-retry; route unchanged
  is MembershipsLoaded -> state.copy(
    families = action.families,
    activeFamilyId = activeFamilyIdFor(action.families),
    route = routeFor(state.session, action.families),
  )
  is CreateFamilyRequested -> state.copy(authBusy = true, authError = null)
  is FamilyCreated -> {
    val fams = state.families + FamilyMembership(action.familyId, action.name, role = "owner", status = "active")
    state.copy(
      families = fams, activeFamilyId = action.familyId, authBusy = false, authError = null,
      route = routeFor(state.session, fams),
    )
  }
  is AuthOpFailed -> state.copy(authBusy = false, authError = action.message)
  // Restore-path terminal outcomes — both exit Loading (never wedge the spinner).
  is SessionExpired -> AppState(route = Route.SignIn, authError = "Your session expired — please sign in again.")
  is RestoreFailed -> state.copy(route = Route.AuthError, authBusy = false, authError = action.message)
  is OpenAccount -> state.copy(route = Route.Account)    // overlay on the signed-in Feed
  is CloseAccount -> state.copy(route = routeFor(state.session, state.families))  // back to the gate
  is OpenProximity -> state.copy(route = Route.Proximity)   // ADR 0044 Phase B — background-proximity settings
  is CloseProximity -> state.copy(route = Route.Account)
  is SignedOut -> AppState(route = Route.SignIn)        // clear session + feed
  is SignOutRequested -> state.copy(signOutBusy = true)

  // ── invitee-join (S5 slice-2) ──
  is OpenJoinInvite -> state.copy(route = Route.JoinInvite, joinBusy = false, joinOutcome = null, joinFamilyName = null)
  // route=JoinInvite pinned on the redeem path so the busy/outcome is ALWAYS visible —
  // incl. a deep-link redeem where a concurrent restore's MembershipsLoaded→routeFor would
  // otherwise clobber the route (ADR 0048). No-op for the paste flow (already on JoinInvite).
  is RedeemRequested -> state.copy(route = Route.JoinInvite, joinBusy = true, joinOutcome = null)
  is InviteRedeemed -> state.copy(route = Route.JoinInvite, joinBusy = false, joinOutcome = "waiting", joinFamilyName = action.familyName)
  is InviteRejected -> state.copy(route = Route.JoinInvite, joinBusy = false, joinOutcome = action.reason)
  is JoinDismissed -> state.copy(
    joinBusy = false, joinOutcome = null, joinFamilyName = null,
    route = routeFor(state.session, state.families),    // exit the join flow → gate (CreateFamily/Feed)
  )

  // ── owner-side approvals (S6) ──
  is OpenMembers -> state.copy(route = Route.Members)
  is RosterLoaded -> state.copy(members = action.members, rosterBusy = false, rosterError = null, memberOpId = null)
  is MemberRemoved -> state.copy(members = state.members.filterNot { it.uid == action.uid }, memberOpId = null)
  is OpenDevices -> state.copy(route = Route.Devices)
  is DevicesLoaded -> state.copy(devices = action.devices, deviceListBusy = false, deviceListError = null, deviceOpId = null)
  is DeviceRevoked -> state.copy(devices = state.devices.filterNot { it.id == action.id }, deviceOpId = null)
  is ApprovalsRequested -> state.copy(approvalsBusy = true)
  is ApprovalsLoaded -> state.copy(approvalsBusy = false, pendingApprovals = action.pending, outstandingInvites = action.invites)
  is OpenInvite -> state.copy(route = Route.Invite, mintedInvite = null, mintError = null, inviteBusy = false)
  is InviteModeSelected -> state.copy(inviteMode = action.mode, mintedInvite = null, mintError = null)
  is MintRequested -> state.copy(inviteBusy = true, mintError = null)
  is InviteMinted -> state.copy(inviteBusy = false, mintedInvite = action.invite)
  is MintFailed -> state.copy(inviteBusy = false, mintError = action.reason)
  is InviteRevokeRequested -> state.copy(inviteOpId = action.id)
  is InviteRevoked -> state.copy(outstandingInvites = state.outstandingInvites.filterNot { it.id == action.id }, inviteOpId = null)
  is InviteDismissed -> state.copy(route = Route.Members, mintedInvite = null, mintError = null, inviteBusy = false)
  is MemberResolved -> state.copy(pendingApprovals = state.pendingApprovals.filterNot { it.uid == action.uid }, memberOpId = null)
  is ApprovalsFailed -> state.copy(approvalsBusy = false, memberOpId = null)
  is MemberOpRequested -> state.copy(memberOpId = action.uid)
  is RosterRequested -> state.copy(rosterBusy = true, rosterError = null)
  is RosterFailed -> state.copy(rosterBusy = false, rosterError = action.message, memberOpId = null)
  is DeviceOpRequested -> state.copy(deviceOpId = action.id)
  is DevicesRequested -> state.copy(deviceListBusy = true, deviceListError = null)
  is DevicesFailed -> state.copy(deviceListBusy = false, deviceListError = action.message, deviceOpId = null)
  is AudienceFailed -> state.copy(audienceError = action.message)
  // ADR 0053 DC4 — a setParticipant/removeParticipant/setVisibility failure surfaces
  // on the same audienceError slot the sheet already renders (mirrors AudienceFailed).
  is HubManageFailed -> state.copy(audienceError = action.message)
  // own profile (task 4) — ProfileLoaded is a full replace (DB/server is truth,
  // like RosterLoaded), and also clears avatarOpId/avatarError (mirrors RosterLoaded
  // clearing memberOpId — a background reload should never leave a stuck busy/error).
  // AvatarOpRequested applies the picked value optimistically AND marks avatarOpId
  // busy (mirrors MemberOpRequested/DeviceOpRequested). AvatarUpdated (success)
  // applies the SERVER-returned value and clears the busy/error state. AvatarUpdateFailed
  // reverts to the previous value, clears the busy marker, and sets avatarError
  // (mirrors rosterError/devicesError).
  is ProfileLoaded -> state.copy(
    myDisplayName = action.profile.displayName,
    myAvatarColor = action.profile.avatarColor,
    myAvatarRef = action.profile.avatarRef,
    avatarOpId = null,
    avatarError = null,
  )
  is AvatarOpRequested -> state.copy(myAvatarColor = action.avatarColor, myAvatarRef = action.avatarRef, avatarOpId = "pending", avatarError = null)
  is AvatarUpdated -> state.copy(myAvatarColor = action.avatarColor, myAvatarRef = action.avatarRef, avatarOpId = null, avatarError = null)
  is AvatarUpdateFailed -> state.copy(myAvatarColor = action.prevAvatarColor, myAvatarRef = action.prevAvatarRef, avatarOpId = null, avatarError = action.message)

  // ── CLI/device approval (S6-D) ──
  is OpenEnterCode -> state.copy(
    route = Route.EnterCode, pendingDevice = null, deviceBusy = false, deviceError = null, deviceOutcome = null,
  )
  is OpenScan -> state.copy(route = Route.ScanPrimer, deviceError = null)
  is ScanPermissionGranted -> state.copy(route = Route.ScanDevice)
  is ScanPermissionDenied -> state.copy(route = Route.ScanDenied)
  is DeviceLookupRequested -> state.copy(deviceBusy = true, deviceError = null)
  is DevicePendingLoaded -> state.copy(
    deviceBusy = false, pendingDevice = action.device, route = Route.AuthorizeDevice, deviceOutcome = null, deviceResuming = false,
  )
  is DeviceLookupNotFound -> state.copy(
    deviceBusy = false, pendingDevice = null, route = Route.AuthorizeDevice, deviceOutcome = "expired", deviceResuming = false,
  )
  is DeviceLookupFailed -> state.copy(deviceBusy = false, deviceError = action.message, deviceResuming = false)  // stays put
  is ApproveDeviceRequested -> state.copy(deviceBusy = true, deviceError = null)
  is DenyDeviceRequested -> state.copy(deviceBusy = true, deviceError = null)
  is DeviceApproved -> state.copy(deviceBusy = false, deviceOutcome = "approved")
  is DeviceDenied -> state.copy(deviceBusy = false, deviceOutcome = "denied")
  is DeviceApproveExpired -> state.copy(deviceBusy = false, deviceOutcome = "expired")
  is DeviceOpFailed -> state.copy(deviceBusy = false, deviceError = action.message)
  is CloseDeviceFlow -> state.copy(
    route = routeFor(state.session, state.families),
    pendingDevice = null, deviceBusy = false, deviceError = null, deviceOutcome = null, deviceResuming = false,
  )
  is DeviceLinkStashed -> state.copy(pendingDeviceLink = action.code)   // await sign-in
  is DeviceLinkConsumed -> state.copy(pendingDeviceLink = null, deviceResuming = true)  // engine looks it up → Finishing
  is InviteLinkStashed -> state.copy(pendingInviteLink = action.token)  // await sign-in (ADR 0048)
  is InviteLinkConsumed -> state.copy(pendingInviteLink = null)          // engine redeems it

  else -> state
}

// AGENT-readable text action log → stdout (desktop) / logcat tag System.out
// (Android: `adb logcat -s System.out`). Cheap text feedback on the redux loop
// for future sessions — no screenshot/vision needed. Pairs with the on-screen
// devtools drawer (ADR 0019).
private val actionLog = middleware<AppState> { store, next, action ->
  val r = next(action)
  val s = store.state
  ClientLog.log("redux", "${action::class.simpleName} → cards=${s.cards.size} syncing=${s.syncing} error=${s.error}")
  r
}

// [F5] thread-safe store: the SyncClient effect dispatches from Dispatchers.IO
// while the Compose UI reads on main — needs synchronized dispatch.
// `debug=true` composes the redux-kotlin-devtools `devTools()` enhancer (records
// to DevToolsHub → in-app drawer) WITH the text action-log middleware. Release
// passes debug=false (neither).
fun createAppStore(initial: AppState = AppState(), debug: Boolean = true): Store<AppState> =
  if (debug) createConcurrentStore(
    ::rootReducer, initial,
    enhancer = compose(devTools(DevToolsConfig(instanceId = "family-ai", name = "Family AI")), applyMiddleware(actionLog)),
  )
  else createConcurrentStore(::rootReducer, initial)
