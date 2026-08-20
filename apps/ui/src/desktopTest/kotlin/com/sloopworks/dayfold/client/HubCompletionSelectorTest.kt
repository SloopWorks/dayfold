package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HubCompletionSelectorTest {
  private val hub = Hub(id = "h1", title = "Weekend", status = "active", visibility = "family")
  private val section = HubSection(id = "s1", hubId = "h1", title = "Plan")
  private val block = HubBlock(id = "b1", sectionId = "s1", type = "text", bodyMd = "Call the caterer")

  private fun done(subjectRef: String, id: String = "d1") = ContentResponse(
    id = id,
    kind = ResponseKind.DONE,
    subjectRef = subjectRef,
    matchScope = MatchScope.SUBJECT,
    audienceScope = AudienceScope.FAMILY,
    userId = null,
    createdBy = "u_mom",
    label = "Call the caterer",
    note = "Menu confirmed",
    createdAt = "2026-08-19T14:30:00Z",
  )

  @Test
  fun aDoneResponseMovesTheMatchingBlockIntoCompletedContent() {
    val state = AppState(
      hubs = HubState(currentHubId = "h1", currentHubTree = HubTree(hub, listOf(section), listOf(block))),
      responses = ResponseState(rules = listOf(done(SubjectRef.node("h1", "s1", "b1")))),
      familyAdmin = FamilyAdminState(members = listOf(FamilyMember("u_mom", displayName = "Morgan Lee"))),
      session = SessionState(session = Session("a", "r", "u_me")),
    )

    val view = hubDetailViewState(state)

    assertTrue(view.tree!!.blocks.isEmpty())
    assertEquals(1, view.completed.size)
    assertEquals("Morgan", view.completed.single().doneBy)
    assertEquals("Menu confirmed", view.completed.single().note)
    assertEquals("2026-08-19T14:30:00Z", view.completed.single().createdAt)
  }

  @Test
  fun aCompletionFromAnotherHubDoesNotHideOrLeakIntoThisHub() {
    val state = AppState(
      hubs = HubState(currentHubId = "h1", currentHubTree = HubTree(hub, listOf(section), listOf(block))),
      responses = ResponseState(rules = listOf(done(SubjectRef.node("h2", "s2", "b1"), "other"))),
    )

    val view = hubDetailViewState(state)

    assertEquals(listOf("b1"), view.tree!!.blocks.map { it.id })
    assertTrue(view.completed.isEmpty())
  }
}
