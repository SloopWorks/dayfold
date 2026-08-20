package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchCorpusTest {
  @Test fun `cards and representative typed payload fields are searchable`() {
    val cards = listOf(
      Card(
        id = "email",
        title = "School update",
        bodyMd = "**Return** the forms this week",
        kind = "action",
        type = "email",
        provenance = Provenance("Lincoln Elementary"),
        payload = Payload(email = EmailPayload(
          from = "Principal Garcia",
          fromAddr = "office@lincoln.edu",
          subject = "Immunization records",
          bodyExcerpt = "Upload the latest certificate",
          attachments = listOf(EmailAttachment("health-form.pdf", "application/pdf")),
          labels = listOf("School"),
        )),
        related = listOf(RelatedRef("follow-up", "other", title = "Pediatrician visit", sub = "Thursday")),
      ),
      Card(
        id = "contact",
        title = "Party vendor",
        payload = Payload(contact = ContactPayload(
          name = "Jake's Rentals",
          company = "Celebration Co",
          role = "Tent supplier",
          phone = "+1 555 0199",
          email = "hello@example.test",
          address = "14 Mill Street",
          deliveryWindow = "Friday afternoon",
        )),
      ),
    )
    val corpus = buildSearchCorpus(SearchContentSnapshot(1, SearchReadiness.READY, cards = cards))
    listOf(
      "immunization" to "email",
      "health form" to "email",
      "pediatrician Thursday" to "email",
      "Lincoln Elementary" to "email",
      "latest certificate" to "email",
      "Jake Rentals" to "contact",
      "tent supplier" to "contact",
      "Friday afternoon" to "contact",
    ).forEach { (query, expectedId) ->
      assertEquals(expectedId, searchCorpus(corpus, query).results.single().handle.destination.cardId, query)
    }
  }

  @Test fun `Hub timeline section and structured block fields get canonical destinations`() {
    val hub = Hub(
      id = "h",
      title = "Maya's birthday",
      timeline = Timeline(
        title = "Party day",
        tz = "America/Los_Angeles",
        stops = listOf(Stop(
          at = "2026-09-12T10:00:00-07:00",
          title = "Pick up balloons",
          sub = "Riverside shop",
          assignee = "Patrick",
          attachments = listOf(Attachment("nav", "Directions", query = "Riverside Balloons")),
        )),
      ),
    )
    val section = HubSection("s", "h", "Before the party")
    val checklist = HubBlock(
      id = "b",
      sectionId = "s",
      type = "checklist",
      payload = BlockPayload(items = listOf(
        ChecklistItem(id = "i1", text = "Sign permission slip", due = "Thursday", assignee = "Dad"),
        ChecklistItem(id = "i2", label = "Cake deposit", amount = 45.0),
      )),
    )
    val corpus = buildSearchCorpus(SearchContentSnapshot(
      4,
      SearchReadiness.READY,
      hubs = listOf(hub),
      sections = listOf(section),
      blocks = listOf(checklist),
    ))

    val timeline = searchCorpus(corpus, "pick balloons").results.single()
    assertEquals(SearchDestination.hub("h"), timeline.handle.destination)
    val sectionResult = searchCorpus(corpus, "before party").results.first()
    assertEquals(SearchDestination.section("h", "s"), sectionResult.handle.destination)
    val block = searchCorpus(corpus, "permission Thursday").results.single()
    assertEquals(SearchDestination.block("h", "s", "b"), block.handle.destination)
    assertEquals("Maya's birthday · Before the party", block.breadcrumb)
    assertEquals(SearchDestination.block("h", "s", "b"), searchCorpus(corpus, "cake 45").results.single().handle.destination)
  }

  @Test fun `hidden completed and orphan entities never enter the corpus`() {
    val active = Hub("h1", title = "Active hub")
    val archived = Hub("h2", title = "Archived hub", status = "archived")
    val completedHub = Hub("h3", title = "Completed hub")
    val sections = listOf(
      HubSection("s1", "h1", "Active section"),
      HubSection("s2", "h2", "Archived section"),
      HubSection("hidden-section", "h1", "Hidden section"),
      HubSection("orphan-section", "missing", "Orphan section"),
    )
    val blocks = listOf(
      HubBlock("visible", "s1", "text", bodyMd = "Visible block"),
      HubBlock("hidden", "s1", "text", bodyMd = "Hidden block"),
      HubBlock("done", "s1", "text", bodyMd = "Done block"),
      HubBlock("archived-block", "s2", "text", bodyMd = "Old passport"),
      HubBlock("orphan-block", "orphan-section", "text", bodyMd = "Orphan block"),
    )
    val snapshot = SearchContentSnapshot(
      revision = 5,
      readiness = SearchReadiness.READY,
      cards = listOf(Card("card-visible", title = "Visible card"), Card("card-done", title = "Done card")),
      hubs = listOf(active, archived, completedHub),
      sections = sections,
      blocks = blocks,
      hiddenIds = setOf("hidden", "hidden-section"),
      completedSubjectRefs = setOf(
        SubjectRef.card("card-done"),
        SubjectRef.node("h3"),
        SubjectRef.node("h1", "s1", "done"),
      ),
    )
    val corpus = buildSearchCorpus(snapshot)

    assertEquals(7, corpus.size)
    assertTrue(corpus.contains(SearchDestination.card("card-visible")))
    assertFalse(corpus.contains(SearchDestination.card("card-done")))
    assertFalse(corpus.contains(SearchDestination.hub("h3")))
    assertFalse(corpus.contains(SearchDestination.section("h1", "hidden-section")))
    assertFalse(corpus.contains(SearchDestination.block("h1", "s1", "hidden")))
    assertFalse(corpus.contains(SearchDestination.block("h1", "s1", "done")))
    assertFalse(corpus.documents.any { it.destination.sectionId == "orphan-section" })

    val archivedResult = searchCorpus(corpus, "old passport").results.single()
    assertEquals(SearchDestination.block("h2", "s2", "archived-block"), archivedResult.handle.destination)
    assertTrue(archivedResult.archived)
  }

  @Test fun `a personally hidden timeline contributes no searchable fields`() {
    val hub = Hub(
      id = "h",
      title = "Maya's birthday",
      timeline = Timeline(
        title = "Party day",
        tz = "America/Los_Angeles",
        stops = listOf(Stop(
          at = "2026-09-12T10:00:00-07:00",
          title = "Pick up balloons",
          attachments = listOf(Attachment("nav", "Directions", query = "Riverside Balloons")),
        )),
      ),
    )
    val corpus = buildSearchCorpus(SearchContentSnapshot(
      revision = 6,
      readiness = SearchReadiness.READY,
      hubs = listOf(hub),
      hiddenIds = setOf("timeline:h"),
    ))

    assertTrue(searchCorpus(corpus, "pick balloons").results.isEmpty())
    assertTrue(searchCorpus(corpus, "riverside").results.isEmpty())
    assertEquals(SearchDestination.hub("h"), searchCorpus(corpus, "maya").results.single().handle.destination)
  }

  @Test fun `readiness distinguishes first sync from a known empty cache`() {
    val waiting = buildSearchCorpus(SearchContentSnapshot(1, SearchReadiness.WAITING_FOR_SYNC))
    val empty = buildSearchCorpus(SearchContentSnapshot(2, SearchReadiness.EMPTY))
    assertEquals(SearchReadiness.WAITING_FOR_SYNC, waiting.readiness)
    assertEquals(SearchReadiness.EMPTY, empty.readiness)
    assertEquals(SearchReadiness.WAITING_FOR_SYNC, searchCorpus(waiting, "anything").readiness)
    assertEquals(SearchReadiness.EMPTY, searchCorpus(empty, "anything").readiness)
  }

  @Test fun `one result is emitted per entity even when many fields match`() {
    val card = Card(
      "one",
      title = "Soccer packing",
      bodyMd = "Soccer bag",
      payload = Payload(email = EmailPayload(subject = "Soccer", bodyExcerpt = "Soccer")),
    )
    val results = searchCorpus(
      buildSearchCorpus(SearchContentSnapshot(1, SearchReadiness.READY, cards = listOf(card))),
      "soccer",
    ).results
    assertEquals(1, results.size)
  }
}
