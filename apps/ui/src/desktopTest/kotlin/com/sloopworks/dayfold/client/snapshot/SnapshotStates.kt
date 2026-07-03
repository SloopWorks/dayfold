package com.sloopworks.dayfold.client.snapshot

import com.sloopworks.dayfold.client.*

// Single source of the states rendered by both the scene registry (Task 4) and the
// golden tests (Task 6/7). Literals lifted verbatim from FeedSnapshotTest / HubSnapshotTest.
object SnapshotStates {

  // Lift verbatim from FeedSnapshotTest.kt:87-107 (the `typedFeed` val body).
  val TYPED_FEED: AppState = AppState(cards = listOf(
    Card("file", kind = "action", title = "Permission slip — sign by Thursday",
      provenance = Provenance("email"), type = "file", privacy = CardPrivacy("on_device"),
      payload = Payload(file = FilePayload(filename = "permission.pdf", mime = "application/pdf", size = 240000, pages = 2,
        docRef = "https://drive.example/abc"))),
    Card("link", kind = "action", title = "Soccer registration closes Friday",
      provenance = Provenance("user"), type = "link",
      payload = Payload(link = LinkPayload(url = "https://riversideyouth.org/reg", domain = "riversideyouth.org", kind = "form", fieldCount = 8))),
    Card("invite", kind = "action", title = "Maya's party — reply by Thursday",
      provenance = Provenance("email"), type = "invite", privacy = CardPrivacy("on_device"),
      payload = Payload(invite = InvitePayload(eventName = "Maya's party", host = "The Garcias", rsvpState = "none", guestCount = 12, confirmedCount = 8))),
    Card("contact", kind = "action", title = "Jake's Rentals delivers at 1pm",
      provenance = Provenance("email"), type = "contact",
      payload = Payload(contact = ContactPayload(name = "Jake's Rentals", company = "Jake's Rentals", role = "Bouncy castle", phone = "+15551234567"))),
    Card("geo", kind = "action", title = "Riverside Park — 8 min away",
      provenance = Provenance("user"), type = "geo",
      payload = Payload(geo = GeoPayload(label = "Riverside Park", address = "Shelter B", etaMin = 8, travelMode = "driving"))),
    Card("email", kind = "action", title = "School RSVP needs a reply by Thursday",
      provenance = Provenance("email"), type = "email",
      payload = Payload(email = EmailPayload(from = "Lincoln Elementary", fromAddr = "office@lincoln.edu", subject = "Field trip permission", threadLen = 2))),
  ))

  fun feed(preset: String): AppState = when (preset) {
    // Lift verbatim from FeedSnapshotTest.kt:44-55 (populatedFeedSnapshot body).
    "busy" -> AppState(cards = listOf(
      Card("a", kind = "action", title = "Party Saturday — order groceries?",
        bodyMd = "Tap [the list](https://instacart.com) to reorder.",
        provenance = Provenance("claude"), notBefore = "2026-06-18T09:00:00Z"),
      Card("b", kind = "weather", title = "Rain at soccer 4pm — pack jackets",
        provenance = Provenance("claude"), notBefore = "2026-06-18T15:00:00Z"),
      Card("c", kind = "countdown", title = "Maya starts college",
        bodyMd = "12 days", provenance = Provenance("claude")),
    ))
    "empty" -> AppState()                                           // FeedSnapshotTest.kt:58
    "caught-up" -> AppState(hubs = listOf(                          // FeedSnapshotTest.kt:63
      Hub(id = "h1", title = "Starting College", status = "active", visibility = "family")))
    "syncing" -> AppState(syncing = true)                          // FeedSnapshotTest.kt:66
    "offline" -> AppState(error = "No internet connection")        // FeedSnapshotTest.kt:68
    "typed" -> TYPED_FEED
    // Lift verbatim from FeedSnapshotTest.kt:116-121 (`enrichedFeed` val body).
    "enriched" -> AppState(cards = listOf(
      Card("enr", kind = "action", title = "Maya's party Saturday — order the groceries?",
        provenance = Provenance("claude"),
        media = CardMedia(icon = "party", accentColor = "#C0381E",
          thumbnailUrl = "https://upload.wikimedia.org/wikipedia/commons/0/0c/Logo.jpg")),
    ))
    else -> error("unknown feed preset '$preset'")
  }

  // Lift verbatim from HubSnapshotTest.kt:24-53 (`canonicalHub()` body).
  fun hubTree(preset: String): HubTree = when (preset) {
    "canonical" -> HubTree(
      hub = Hub(id = "sample", type = "starting-college", title = "Sample → Starting College", status = "active", visibility = "family"),
      sections = listOf(
        HubSection(id = "dates", hubId = "sample", title = "Dates & Deadlines", ord = 0),
        HubSection(id = "money", hubId = "sample", title = "Money & Forms", ord = 1),
      ),
      blocks = listOf(
        HubBlock(id = "b1", sectionId = "dates", type = "milestone", ord = 0,
          payload = BlockPayload(date = "Aug 1", label = "E-Bill due in full")),
        HubBlock(id = "b2", sectionId = "dates", type = "checklist", ord = 1,
          payload = BlockPayload(items = listOf(
            ChecklistItem(text = "Submit FAFSA", done = true),
            ChecklistItem(text = "Upload immunization records", done = false)))),
        HubBlock(id = "b3", sectionId = "money", type = "contact", ord = 0,
          payload = BlockPayload(name = "Financial Aid Office", role = "Billing & aid", phone = "888-555-0100")),
        HubBlock(id = "b4", sectionId = "money", type = "document", ord = 1,
          payload = BlockPayload(ref = "https://example.edu/immunization.pdf", label = "Immunization Requirements")),
        HubBlock(id = "b5", sectionId = "money", type = "location", ord = 2,
          payload = BlockPayload(label = "Butler University", address = "4600 Sunset Ave", mapUrl = "https://maps.example/butler")),
        HubBlock(id = "b6", sectionId = "money", type = "budget", ord = 3,
          payload = BlockPayload(items = listOf(
            ChecklistItem(label = "Tuition", amount = 12000.0, paid = true),
            ChecklistItem(label = "Housing", amount = 6000.0, paid = false)))),
        HubBlock(id = "b7", sectionId = "money", type = "markdown", ord = 4,
          bodyMd = "## Timing traps\n- **Meningitis B** = 2 doses\n- Check the [aid portal](https://example.edu/aid)"),
      ),
    )
    "enriched" -> hubTree("canonical").let { base ->               // HubSnapshotTest.kt:78-79
      base.copy(hub = base.hub.copy(media = HubMedia(icon = "school", accentColor = "#3B5BDB")))
    }
    else -> error("unknown hub preset '$preset'")
  }

  // The 6 detail cards are the same objects as TYPED_FEED's cards, addressed by id.
  fun detailCard(preset: String): Card =
    TYPED_FEED.cards.firstOrNull { it.id == preset }
      ?: error("unknown detail preset '$preset' (ids: ${TYPED_FEED.cards.map { it.id }})")
}
