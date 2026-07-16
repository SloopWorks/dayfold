package com.sloopworks.dayfold.client

import org.reduxkotlin.Store
import org.reduxkotlin.StoreEnhancer
import org.reduxkotlin.applyMiddleware
import org.reduxkotlin.compose
import org.reduxkotlin.middleware
import org.reduxkotlin.devtools.DevToolsConfig
import org.reduxkotlin.devtools.devTools
import org.reduxkotlin.concurrent.NotificationContext
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

/** Root-only transitions: global reset, back resolution, and content-to-navigation cleanup. */
fun rootReducer(state: AppState, action: Any): AppState = when (action) {
  is Back -> backAction(state)?.let { rootReducer(state, it) } ?: state
  is SignedOut -> signedOutState(state)
  is SessionExpired -> expiredSessionState(state)
  is CardsLoaded -> reduceContent(state, action).copy(navigation = state.navigation.copy(detailStack = state.navigation.detailStack.filter { id -> action.cards.any { it.id == id } }))
  is SyncStarted, is SyncSucceeded, is SyncStopped, is SyncFailed -> reduceContent(state, action)
  is MembershipsLoaded, is FamilyCreated -> reduceRoutedFeatureWithFamilyTransition(state, action)
  is NavToDetail, is NavBack, is RestoreDetailStack, is AuthRestoring, is SessionRestored, is SignInSucceeded, is RestoreFailed, is OpenAccount, is CloseAccount, is OpenProximity, is CloseProximity, is OpenJoinInvite, is RedeemRequested, is InviteRedeemed, is InviteRejected, is JoinDismissed -> reduceRoutedFeature(state, action)
  is OpenHubs, is OpenFeed, is HubsLoaded, is HubsFailed, is OpenHub, is HubTreeLoaded, is HubNotFound, is CloseHub, is CloseHubToFeed, is OpenTimelineDetail, is CloseTimelineDetail, is SetHubFilter, is HiddenLoaded, is SetShowHidden, is OpenAudienceSheet, is HubAudienceRequested, is HubAudienceLoaded, is CloseAudienceSheet, is AudienceFailed, is HubManageFailed -> reduceNavigation(reduceHubs(state, action), action)
  is NowContentLoaded, is SurfacingLoaded -> reduceNow(state, action)
  is NotifConfigLoaded, is LocationPermissionLoaded, is NotificationPermissionLoaded -> reduceNotifications(state, action)
  is SignInRequested, is SignInFailed, is SessionRotated, is CreateFamilyRequested, is AuthOpFailed, is SignOutRequested, is InviteLinkStashed, is InviteLinkConsumed -> reduceSession(state, action)
  is OpenMembers, is RosterLoaded, is MemberRemoved, is ApprovalsRequested, is ApprovalsLoaded, is OpenInvite, is InviteModeSelected, is MintRequested, is InviteMinted, is MintFailed, is InviteRevokeRequested, is InviteRevoked, is InviteRevokeFailed, is InviteDismissed, is MemberResolved, is ApprovalsFailed, is MemberOpRequested, is RosterRequested, is RosterFailed -> reduceNavigation(reduceFamilyAdmin(state, action), action)
  is OpenDevices, is DevicesLoaded, is DeviceRevoked, is DeviceOpRequested, is DevicesRequested, is DevicesFailed, is OpenEnterCode, is OpenScan, is ScanPermissionGranted, is ScanPermissionDenied, is DeviceLookupRequested, is DevicePendingLoaded, is DeviceLookupNotFound, is DeviceLookupFailed, is ApproveDeviceRequested, is DenyDeviceRequested, is DeviceApproved, is DeviceDenied, is DeviceApproveExpired, is DeviceOpFailed, is CloseDeviceFlow, is DeviceLinkStashed, is DeviceLinkConsumed -> reduceNavigation(reduceDevices(state, action), action)
  is ProfileLoaded, is AvatarOpRequested, is AvatarUpdated, is AvatarUpdateFailed, is NameOpRequested, is NameUpdated, is NameUpdateFailed -> reduceProfile(state, action)
  else -> state
}

private fun reduceRoutedFeature(state: AppState, action: Any): AppState =
  reduceNavigation(reduceSession(state, action), action)

/** A family switch invalidates projections scoped to the former family, but not device-local UI. */
private fun reduceRoutedFeatureWithFamilyTransition(state: AppState, action: Any): AppState {
  val updated = reduceSession(state, action)
  val familyChanged = state.session.activeFamilyId != updated.session.activeFamilyId
  val familyScoped = if (familyChanged) updated.copy(
    content = ContentState(),
    now = NowState(),
    hubs = HubState(),
    familyAdmin = FamilyAdminState(),
  ) else updated
  return reduceNavigation(familyScoped, action)
}

private fun signedOutState(state: AppState) = AppState(
  navigation = NavigationState(route = Route.SignIn),
  notifications = state.notifications,
)

private fun expiredSessionState(state: AppState) = signedOutState(state).copy(
  session = SessionState(authError = "Your session expired — please sign in again."),
)

// AGENT-readable text action log → stdout (desktop) / logcat tag System.out
// (Android: `adb logcat -s System.out`). Cheap text feedback on the redux loop
// for future sessions — no screenshot/vision needed. Pairs with the on-screen
// devtools drawer (ADR 0019).
private val actionLog = middleware<AppState> { store, next, action ->
  val r = next(action)
  val s = store.state
  Log.d("redux") { "${action::class.simpleName} → cards=${s.content.cards.size} syncing=${s.content.syncing} error=${s.content.error}" }
  r
}

// [F5] thread-safe store: the SyncClient effect dispatches from Dispatchers.IO
// while the Compose UI reads on main — needs synchronized dispatch.
// `debug=true` composes the redux-kotlin-devtools `devTools()` enhancer (records
// to DevToolsHub → in-app drawer) WITH the text action-log middleware. Release
// passes debug=false (neither).
// `extraEnhancer` (optional) composes RIGHTMOST = innermost — the slot debug
// tooling like the swip timeline recorder requires (sees every dispatch, wraps
// the raw store). Null → exactly the previous behavior. :client stays swip-free;
// the androidApp debug variant supplies the enhancer.
fun createAppStore(
  notificationContext: NotificationContext,
  initial: AppState = AppState(),
  debug: Boolean = true,
  extraEnhancer: StoreEnhancer<AppState>? = null,
): Store<AppState> =
  if (debug) createConcurrentStore(
    ::rootReducer, initial,
    notificationContext = notificationContext,
    enhancer = compose(listOfNotNull(
      devTools(DevToolsConfig(instanceId = "family-ai", name = "Family AI")),
      applyMiddleware(actionLog),
      extraEnhancer, // rightmost = innermost — the recorder's required slot
    )),
  )
  else createConcurrentStore(
    ::rootReducer,
    initial,
    notificationContext = notificationContext,
    enhancer = extraEnhancer,
  )
