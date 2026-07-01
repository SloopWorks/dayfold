package com.sloopworks.dayfold.client
import kotlin.test.Test
import kotlin.test.assertEquals

class TimelineNavTest {
  @Test fun `open sets the detail scale`() {
    val s = rootReducer(AppState(route = Route.Hubs, currentHubId = "h1"), OpenTimelineDetail(TimelineScale.Day))
    assertEquals(TimelineScale.Day, s.timelineDetail)
  }
  @Test fun `close clears it`() {
    val s = rootReducer(AppState(route = Route.Hubs, currentHubId = "h1", timelineDetail = TimelineScale.Hub), CloseTimelineDetail)
    assertEquals(null, s.timelineDetail)
  }
  @Test fun `back closes the timeline detail before the hub`() {
    val open = AppState(route = Route.Hubs, currentHubId = "h1", timelineDetail = TimelineScale.Day)
    assertEquals(CloseTimelineDetail, backAction(open))
    val closed = AppState(route = Route.Hubs, currentHubId = "h1")
    assertEquals(CloseHub, backAction(closed))
  }
  @Test fun `HubNotFound clears timelineDetail`() {
    val s = rootReducer(AppState(route = Route.Hubs, currentHubId = "h1", timelineDetail = TimelineScale.Day), HubNotFound)
    assertEquals(null, s.timelineDetail)
  }
  @Test fun `OpenHub clears timelineDetail from previous hub`() {
    val s = rootReducer(AppState(route = Route.Hubs, currentHubId = "hubA", timelineDetail = TimelineScale.Hub), OpenHub("hubB"))
    assertEquals(null, s.timelineDetail)
  }
  @Test fun `HubsLoaded evicting open hub clears timelineDetail`() {
    val otherHub = Hub(id = "other", title = "Other")
    val s = rootReducer(AppState(route = Route.Hubs, currentHubId = "h1", timelineDetail = TimelineScale.Day), HubsLoaded(listOf(otherHub)))
    assertEquals(null, s.timelineDetail)
  }
  @Test fun `HubsLoaded retaining open hub preserves timelineDetail`() {
    val hub = Hub(id = "h1", title = "Hub One")
    val s = rootReducer(AppState(route = Route.Hubs, currentHubId = "h1", timelineDetail = TimelineScale.Day), HubsLoaded(listOf(hub)))
    assertEquals(TimelineScale.Day, s.timelineDetail)
  }
}
