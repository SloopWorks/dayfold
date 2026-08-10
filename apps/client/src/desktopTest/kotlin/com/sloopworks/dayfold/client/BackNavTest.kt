package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Pure back-resolution: what does system-back do from each state?
class BackNavTest {
  private fun st(route: Route, detail: List<String> = emptyList(), hub: String? = null,
                 sheet: Boolean = false, resuming: Boolean = false, fromDetail: Boolean = false) =
    AppState(navigation = NavigationState(route = route, detailStack = detail), devices = DeviceState(resuming = resuming),
             hubs = HubState(currentHubId = hub, audienceSheetOpen = sheet, fromFeedDetail = fromDetail))

  @Test fun `back from a hub deep-linked from a card detail returns to the detail, not the list`() {
    // deep-linked: route=Hubs, hub open, flagged fromDetail (detailStack still holds the card)
    assertEquals(CloseHubToFeed, backAction(st(Route.Hubs, hub = "h1", detail = listOf("c1"), fromDetail = true)))
    // normally opened (from the Hubs list): back → CloseHub → the list
    assertEquals(CloseHub, backAction(st(Route.Hubs, hub = "h1", fromDetail = false)))
  }

  @Test fun `feed with a detail open resolves to NavBack`() {
    assertEquals(NavBack, backAction(st(Route.Feed, detail = listOf("c1"))))
  }

  @Test fun `an open audience sheet closes FIRST, before any nav`() {
    // even on a hub detail (where back would otherwise CloseHub) the overlay wins
    assertEquals(CloseAudienceSheet, backAction(st(Route.Hubs, hub = "h1", sheet = true)))
    // and on the hub LIST (where back would otherwise exit) the sheet still closes
    assertEquals(CloseAudienceSheet, backAction(st(Route.Hubs, sheet = true)))
    assertTrue(appHandlesBack(st(Route.Hubs, sheet = true)))
  }

  @Test fun `the device-resume beat lets the OS handle back`() {
    assertNull(backAction(st(Route.Feed, detail = listOf("c1"), resuming = true)))
    assertFalse(appHandlesBack(st(Route.Account, resuming = true)))
  }

  @Test fun `appHandlesBack is true for a handled route`() {
    assertTrue(appHandlesBack(st(Route.Account)))
  }

  @Test fun `feed with no detail is not handled (system exits)`() {
    assertNull(backAction(st(Route.Feed)))
    assertFalse(appHandlesBack(st(Route.Feed)))
  }

  @Test fun `hub detail resolves to CloseHub, hub list does not`() {
    assertEquals(CloseHub, backAction(st(Route.Hubs, hub = "h1")))
    assertNull(backAction(st(Route.Hubs)))
  }

  @Test fun `account resolves to CloseAccount`() {
    assertEquals(CloseAccount, backAction(st(Route.Account)))
  }

  @Test fun `smart briefings closes overlays then steps then returns to Account`() {
    val base = st(Route.SmartBriefings).copy(routines = RoutineState.preview().copy(
      destination = RoutineDestination.PRIVACY,
      detailsOpen = true,
      revokeSheetOpen = true,
    ))
    assertEquals(CloseRoutineDetails, backAction(base))
    assertEquals(CloseRoutineRevokeSheet, backAction(base.copy(routines = base.routines.copy(detailsOpen = false))))
    assertEquals(RoutineNavigateBack, backAction(base.copy(routines = base.routines.copy(detailsOpen = false, revokeSheetOpen = false))))

    for (destination in listOf(RoutineDestination.ENTRY, RoutineDestination.ACTIVE)) {
      assertEquals(
        CloseSmartBriefings,
        backAction(base.copy(routines = base.routines.copy(destination = destination, detailsOpen = false, revokeSheetOpen = false))),
      )
    }
  }

  @Test fun `Back applies the smart briefings overlay and route precedence`() {
    var current = st(Route.SmartBriefings).copy(routines = RoutineState.preview().copy(
      destination = RoutineDestination.PROVIDER,
      detailsOpen = true,
    ))
    current = rootReducer(current, Back)
    assertFalse(current.routines.detailsOpen)
    assertEquals(Route.SmartBriefings, current.navigation.route)
    current = rootReducer(current, Back)
    assertEquals(RoutineDestination.ENTRY, current.routines.destination)
    current = rootReducer(current, Back)
    assertEquals(Route.Account, current.navigation.route)
  }

  @Test fun `members and devices resolve to OpenAccount`() {
    assertEquals(OpenAccount, backAction(st(Route.Members)))
    assertEquals(OpenAccount, backAction(st(Route.Devices)))
  }

  @Test fun `calendar settings resolves to OpenAccount`() {
    assertEquals(OpenAccount, backAction(st(Route.Calendar)))
  }

  @Test fun `every device-flow screen resolves to CloseDeviceFlow`() {
    for (r in listOf(Route.AuthorizeDevice, Route.EnterCode, Route.ScanPrimer, Route.ScanDevice, Route.ScanDenied))
      assertEquals(CloseDeviceFlow, backAction(st(r)), "back from $r")
  }

  @Test fun `join invite resolves to JoinDismissed`() {
    assertEquals(JoinDismissed, backAction(st(Route.JoinInvite)))
  }

  @Test fun `auth gate routes are not handled`() {
    for (r in listOf(Route.SignIn, Route.Loading, Route.CreateFamily, Route.AuthError)) {
      assertNull(backAction(st(r)), "back from $r")
      assertFalse(appHandlesBack(st(r)))
    }
  }

  @Test fun `Back action delegates through the reducer to the resolved action`() {
    val s = st(Route.Hubs, hub = "h1")
    assertEquals(rootReducer(s, CloseHub), rootReducer(s, Back))
  }

  @Test fun `Back is a no-op where the app does not handle it`() {
    val s = st(Route.SignIn)
    assertEquals(s, rootReducer(s, Back))
  }
}
