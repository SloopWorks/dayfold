package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals

class RouteMotionTest {
  private fun anim(from: Route, to: Route, reduce: Boolean = false) = navAnimFor(from, to, reduce)

  @Test fun tab_switch_is_shared_axis_x_by_index() {
    assertEquals(NavAnim.SharedXForward, anim(Route.Feed, Route.Hubs))
    assertEquals(NavAnim.SharedXBackward, anim(Route.Hubs, Route.Feed))
  }

  @Test fun opening_account_is_modal_enter_closing_is_modal_exit() {
    assertEquals(NavAnim.ModalEnter, anim(Route.Feed, Route.Account))
    assertEquals(NavAnim.ModalExit, anim(Route.Account, Route.Feed))
  }

  @Test fun push_deeper_from_modal_is_not_a_modal_exit() {
    // Account (modal, tier 1) -> Members (push, tier 2) must be a forward push, not exit.
    assertEquals(NavAnim.SharedZForward, anim(Route.Account, Route.Members))
    assertEquals(NavAnim.SharedZBackward, anim(Route.Members, Route.Account))
  }

  @Test fun settings_push_and_pop() {
    assertEquals(NavAnim.SharedZForward, anim(Route.Members, Route.Invite))
    assertEquals(NavAnim.SharedZBackward, anim(Route.Invite, Route.Members))
    assertEquals(NavAnim.SharedZForward, anim(Route.Account, Route.Devices))
    assertEquals(NavAnim.SharedZForward, anim(Route.Account, Route.Proximity))
  }

  @Test fun device_wizard_is_shared_axis_x() {
    assertEquals(NavAnim.SharedXForward, anim(Route.EnterCode, Route.ScanPrimer))
    assertEquals(NavAnim.SharedXBackward, anim(Route.ScanPrimer, Route.EnterCode))
    // entering / leaving the wizard from a non-wizard base
    assertEquals(NavAnim.SharedXForward, anim(Route.Feed, Route.EnterCode))
    assertEquals(NavAnim.SharedXBackward, anim(Route.EnterCode, Route.Feed))
  }

  @Test fun gate_edges_fade_through() {
    assertEquals(NavAnim.FadeThrough, anim(Route.Loading, Route.Feed))
    assertEquals(NavAnim.FadeThrough, anim(Route.SignIn, Route.CreateFamily))
  }

  @Test fun reduced_motion_and_self_transition_snap() {
    assertEquals(NavAnim.Snap, anim(Route.Feed, Route.Account, reduce = true))
    assertEquals(NavAnim.Snap, anim(Route.Feed, Route.Feed))
  }

  @Test fun routeSpec_is_total() {
    // Every Route must have a spec (compile-time exhaustiveness + no throw at runtime).
    Route.entries.forEach { routeSpec(it) }
  }
}
