package com.sloopworks.dayfold.android

import com.sloopworks.dayfold.client.AppState
import com.sloopworks.dayfold.client.NavigationState
import com.sloopworks.dayfold.client.Route
import com.sloopworks.dayfold.client.SearchOrigin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPrivacyDebugTest {
  @Test fun `bug report screenshots are disabled only while local Search is visible`() {
    assertFalse(bugReportScreenshotAllowed(Route.Search))
    assertTrue(bugReportScreenshotAllowed(Route.Feed))
    assertTrue(bugReportScreenshotAllowed(Route.Hubs))
  }

  @Test fun `local Search reuses its origin for screen-view analytics`() {
    assertTrue(
      analyticsRouteName(AppState(navigation = NavigationState(
        route = Route.Search,
        searchOrigin = SearchOrigin.NOW,
      ))) == Route.Feed.name,
    )
    assertTrue(
      analyticsRouteName(AppState(navigation = NavigationState(
        route = Route.Search,
        searchOrigin = SearchOrigin.HUBS,
      ))) == Route.Hubs.name,
    )
  }
}
