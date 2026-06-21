package com.sloopworks.dayfold.client

import org.reduxkotlin.Store
import org.reduxkotlin.applyMiddleware
import org.reduxkotlin.compose
import org.reduxkotlin.middleware
import org.reduxkotlin.devtools.DevToolsConfig
import org.reduxkotlin.devtools.devTools
import org.reduxkotlin.threadsafe.createThreadSafeStore

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

  // ── auth / session (S5) ──
  is AuthRestoring -> state.copy(route = Route.Loading)
  is SessionRestored -> state.copy(
    session = action.session,
    route = if (action.session == null) Route.SignIn else Route.Loading, // whoami next
  )
  is SignInRequested -> state.copy(authBusy = true, authError = null)
  is SignInSucceeded -> state.copy(
    session = action.session, authBusy = false, authError = null,
    route = Route.Loading,                              // await MembershipsLoaded
  )
  is SignInFailed -> state.copy(authBusy = false, authError = action.message)
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
  is OpenAccount -> state.copy(route = Route.Account)    // overlay on the signed-in Feed
  is CloseAccount -> state.copy(route = routeFor(state.session, state.families))  // back to the gate
  is SignedOut -> AppState(route = Route.SignIn)        // clear session + feed
  // SignOutRequested is an effect trigger (AuthEngine); no state change until SignedOut.

  // ── invitee-join (S5 slice-2) ──
  is OpenJoinInvite -> state.copy(route = Route.JoinInvite, joinBusy = false, joinOutcome = null, joinFamilyName = null)
  is RedeemRequested -> state.copy(joinBusy = true, joinOutcome = null)
  is InviteRedeemed -> state.copy(joinBusy = false, joinOutcome = "waiting", joinFamilyName = action.familyName)
  is InviteRejected -> state.copy(joinBusy = false, joinOutcome = action.reason)
  is JoinDismissed -> state.copy(
    joinBusy = false, joinOutcome = null, joinFamilyName = null,
    route = routeFor(state.session, state.families),    // exit the join flow → gate (CreateFamily/Feed)
  )

  // ── owner-side approvals (S6) ──
  is OpenMembers -> state.copy(route = Route.Members)
  is RosterLoaded -> state.copy(members = action.members)
  is MemberRemoved -> state.copy(members = state.members.filterNot { it.uid == action.uid })
  is OpenDevices -> state.copy(route = Route.Devices)
  is DevicesLoaded -> state.copy(devices = action.devices)
  is DeviceRevoked -> state.copy(devices = state.devices.filterNot { it.id == action.id })
  is ApprovalsRequested -> state.copy(approvalsBusy = true)
  is ApprovalsLoaded -> state.copy(approvalsBusy = false, pendingApprovals = action.pending)
  is MemberResolved -> state.copy(pendingApprovals = state.pendingApprovals.filterNot { it.uid == action.uid })
  is ApprovalsFailed -> state.copy(approvalsBusy = false)

  else -> state
}

// AGENT-readable text action log → stdout (desktop) / logcat tag System.out
// (Android: `adb logcat -s System.out`). Cheap text feedback on the redux loop
// for future sessions — no screenshot/vision needed. Pairs with the on-screen
// devtools drawer (ADR 0019).
private val actionLog = middleware<AppState> { store, next, action ->
  val r = next(action)
  val s = store.state
  println("[redux] ${action::class.simpleName} → cards=${s.cards.size} syncing=${s.syncing} error=${s.error}")
  r
}

// [F5] thread-safe store: the SyncClient effect dispatches from Dispatchers.IO
// while the Compose UI reads on main — needs synchronized dispatch.
// `debug=true` composes the redux-kotlin-devtools `devTools()` enhancer (records
// to DevToolsHub → in-app drawer) WITH the text action-log middleware. Release
// passes debug=false (neither).
fun createAppStore(initial: AppState = AppState(), debug: Boolean = true): Store<AppState> =
  if (debug) createThreadSafeStore(
    ::rootReducer, initial,
    compose(devTools(DevToolsConfig(instanceId = "family-ai", name = "Family AI")), applyMiddleware(actionLog)),
  )
  else createThreadSafeStore(::rootReducer, initial)
