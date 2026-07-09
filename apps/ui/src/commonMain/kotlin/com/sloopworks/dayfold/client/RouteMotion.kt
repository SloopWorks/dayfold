package com.sloopworks.dayfold.client

// Pure navigation-motion taxonomy — NO Compose. Maps each Route to a motion class +
// hierarchy tier, and resolves a (from,to) route pair to a NavAnim. See ADR 0051.
// The `when` in routeSpec is exhaustive over Route → adding a Route without a spec is a
// compile error. That is the consistency guarantee: new nav must pick a NavKind.

enum class NavKind { Tab, Push, Modal, Wizard, Gate }

/** tier = hierarchy depth (drives push/pop + wizard/modal direction). tabIndex only for Tab. */
data class RouteSpec(val tier: Int, val kind: NavKind, val tabIndex: Int = -1)

fun routeSpec(route: Route): RouteSpec = when (route) {
  Route.Loading -> RouteSpec(0, NavKind.Gate)
  Route.SignIn -> RouteSpec(0, NavKind.Gate)
  Route.AuthError -> RouteSpec(0, NavKind.Gate)
  Route.CreateFamily -> RouteSpec(1, NavKind.Wizard)
  Route.JoinInvite -> RouteSpec(2, NavKind.Modal)
  Route.Feed -> RouteSpec(0, NavKind.Tab, tabIndex = 0)
  Route.Hubs -> RouteSpec(0, NavKind.Tab, tabIndex = 1)
  Route.Account -> RouteSpec(1, NavKind.Modal)
  Route.Proximity -> RouteSpec(2, NavKind.Push)
  Route.Devices -> RouteSpec(2, NavKind.Push)
  Route.Members -> RouteSpec(2, NavKind.Push)
  Route.Invite -> RouteSpec(3, NavKind.Push)
  Route.EnterCode -> RouteSpec(1, NavKind.Wizard)
  Route.ScanPrimer -> RouteSpec(2, NavKind.Wizard)
  Route.ScanDevice -> RouteSpec(3, NavKind.Wizard)
  Route.ScanDenied -> RouteSpec(3, NavKind.Wizard)
  Route.AuthorizeDevice -> RouteSpec(4, NavKind.Wizard)
}

enum class NavAnim {
  SharedXForward, SharedXBackward,
  SharedZForward, SharedZBackward,
  ModalEnter, ModalExit,
  FadeThrough, Snap,
}

/** Resolve the motion for a route change. Pure — unit-tested. First match wins. */
fun navAnimFor(from: Route, to: Route, reduceMotion: Boolean): NavAnim {
  if (reduceMotion) return NavAnim.Snap
  if (from == to) return NavAnim.Snap
  val f = routeSpec(from)
  val t = routeSpec(to)
  // 1. Tab peers → horizontal by index.
  if (f.kind == NavKind.Tab && t.kind == NavKind.Tab)
    return if (t.tabIndex > f.tabIndex) NavAnim.SharedXForward else NavAnim.SharedXBackward
  // 2. Modal open/close — tier-gated so a push *deeper from* a modal is NOT an exit.
  if (t.kind == NavKind.Modal && t.tier > f.tier) return NavAnim.ModalEnter
  if (f.kind == NavKind.Modal && t.tier < f.tier) return NavAnim.ModalExit
  // 3. Gate (boot / auth error) — unrelated content.
  if (f.kind == NavKind.Gate || t.kind == NavKind.Gate) return NavAnim.FadeThrough
  // 4. Wizard (either endpoint) — linear horizontal.
  if (f.kind == NavKind.Wizard || t.kind == NavKind.Wizard)
    return if (t.tier >= f.tier) NavAnim.SharedXForward else NavAnim.SharedXBackward
  // 5. Push / Pop by depth.
  return if (t.tier > f.tier) NavAnim.SharedZForward else NavAnim.SharedZBackward
}
