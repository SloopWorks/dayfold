package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SearchNavigationReducerTest {
  private val request = HubRequestKey(HubTenantGeneration(7L, 11L), 3L)
  private val cards = listOf(Card("card-a", title = "A"), Card("card-b", title = "B"))

  @Test fun `search closes to the exact Now or Hubs origin without storing a query`() {
    val now = reduceNavigation(
      AppState(navigation = NavigationState(route = Route.Feed)),
      OpenSearch(SearchOrigin.NOW),
    )
    assertEquals(Route.Search, now.navigation.route)
    assertEquals(SearchOrigin.NOW, now.navigation.searchOrigin)
    assertEquals(Route.Feed, reduceNavigation(now, CloseSearch).navigation.route)

    val hubs = reduceNavigation(
      AppState(navigation = NavigationState(route = Route.Hubs)),
      OpenSearch(SearchOrigin.HUBS),
    )
    assertEquals(Route.Search, hubs.navigation.route)
    assertEquals(SearchOrigin.HUBS, hubs.navigation.searchOrigin)
    assertEquals(Route.Hubs, reduceNavigation(hubs, CloseSearch).navigation.route)
  }

  @Test fun `search only opens from its matching tier-one surface`() {
    val detail = AppState(
      navigation = NavigationState(route = Route.Feed, detailStack = listOf("card-a")),
      content = ContentState(cards = cards),
    )
    assertEquals(detail, reduceNavigation(detail, OpenSearch(SearchOrigin.NOW)))

    val openHub = AppState(
      navigation = NavigationState(route = Route.Hubs),
      hubs = HubState(currentHubId = "hub-a"),
    )
    assertEquals(openHub, reduceNavigation(openHub, OpenSearch(SearchOrigin.HUBS)))
    assertEquals(
      Route.Feed,
      reduceNavigation(AppState(navigation = NavigationState(route = Route.Feed)), OpenSearch(SearchOrigin.HUBS))
        .navigation.route,
    )
  }

  @Test fun `search card detail and related pushes preserve Search until the final pop`() {
    var state = AppState(
      navigation = NavigationState(route = Route.Search, searchOrigin = SearchOrigin.NOW),
      content = ContentState(cards = cards),
    )
    state = reduceNavigation(state, OpenDetailFromSearch("card-a"))
    assertEquals(Route.Feed, state.navigation.route)
    assertEquals(listOf("card-a"), state.navigation.detailStack)
    assertEquals(DetailReturnDestination.SEARCH, state.navigation.detailReturnDestination)

    state = reduceNavigation(state, NavToDetail("card-b"))
    assertEquals(listOf("card-a", "card-b"), state.navigation.detailStack)
    assertEquals(DetailReturnDestination.SEARCH, state.navigation.detailReturnDestination)

    state = reduceNavigation(state, NavBack)
    assertEquals(Route.Feed, state.navigation.route)
    assertEquals(listOf("card-a"), state.navigation.detailStack)
    assertEquals(DetailReturnDestination.SEARCH, state.navigation.detailReturnDestination)

    state = reduceNavigation(state, NavBack)
    assertEquals(Route.Search, state.navigation.route)
    assertEquals(emptyList(), state.navigation.detailStack)
    assertEquals(DetailReturnDestination.FEED, state.navigation.detailReturnDestination)
  }

  @Test fun `a dangling search card stays on Search`() {
    val state = AppState(
      navigation = NavigationState(route = Route.Search),
      content = ContentState(cards = cards),
    )
    assertEquals(state, reduceNavigation(state, OpenDetailFromSearch("missing")))
  }

  @Test fun `hub section and block search arrivals all return to Search`() {
    for (level in HubArrivalLevel.entries) {
      val arrival = HubArrival(level, "target-${level.name.lowercase()}", HubArrivalSource.SEARCH)
      val action = OpenHub(
        hubId = "hub-a",
        request = request,
        returnDestination = HubReturnDestination.SEARCH,
        arrival = arrival,
      )
      val open = reduceNavigation(reduceHubs(
        AppState(navigation = NavigationState(route = Route.Search, searchOrigin = SearchOrigin.HUBS)),
        action,
      ), action)
      assertEquals(Route.Hubs, open.navigation.route)
      assertEquals(arrival, open.hubs.arrival)
      assertEquals(HubReturnDestination.SEARCH, open.hubs.returnDestination)
      assertEquals(CloseHubToSearch, backAction(open))

      val closed = reduceNavigation(reduceHubs(open, CloseHubToSearch), CloseHubToSearch)
      assertEquals(Route.Search, closed.navigation.route)
      assertNull(closed.hubs.currentHubId)
      assertNull(closed.hubs.arrival)
      assertEquals(HubReturnDestination.HUB_LIST, closed.hubs.returnDestination)
    }
  }

  @Test fun `briefing block arrival keeps Feed return`() {
    val action = OpenHub(
      hubId = "hub-a",
      request = request,
      returnDestination = HubReturnDestination.FEED_DETAIL,
      arrival = HubArrival(HubArrivalLevel.BLOCK, "block-a", HubArrivalSource.BRIEFING),
    )
    val state = reduceHubs(AppState(), action)
    assertEquals(
      HubArrival(HubArrivalLevel.BLOCK, "block-a", HubArrivalSource.BRIEFING),
      state.hubs.arrival,
    )
    assertEquals(CloseHubToFeed, backAction(state.copy(
      navigation = NavigationState(route = Route.Hubs, detailStack = listOf("card-a")),
    )))
  }
}
