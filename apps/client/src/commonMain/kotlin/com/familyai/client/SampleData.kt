package com.familyai.client

// Dev-only sample feed — the 6 typed cards (mirrors the CLI templates) + a
// related-edge email. Shells seed this into the DB behind a debug flag when no
// live API is reachable, so the on-device UI (cards / detail / transition) can be
// exercised without the server. NOT used in release paths.
object SampleData {
  val cards: List<Card> = listOf(
    Card(
      id = "s_invite", kind = "action", title = "Maya's party — reply by Thursday",
      provenance = Provenance("email"), notBefore = "2026-06-20T09:00:00Z",
      type = "invite", privacy = CardPrivacy("on_device"),
      payload = Payload(invite = InvitePayload(eventName = "Maya's Birthday", host = "The Garcias",
        startAt = "2026-06-21T15:00:00Z", place = "Home — backyard", rsvpState = "none",
        guestCount = 12, confirmedCount = 8)),
    ),
    Card(
      id = "s_email", kind = "action", title = "School RSVP needs a reply by Thursday",
      provenance = Provenance("email"), notBefore = "2026-06-20T10:00:00Z",
      type = "email",
      payload = Payload(email = EmailPayload(from = "Lincoln Elementary", fromAddr = "office@lincoln.edu",
        subject = "Field trip permission", date = "2026-06-18T08:00:00Z", threadLen = 2,
        bodyExcerpt = "Please reply yes/no for Maya's field trip by Thursday.")),
      relatedKicker = "FROM THE SAME EMAIL",
      related = listOf(
        RelatedRef("attachment", "s_file", "file", "permission.pdf", "240 KB · attachment"),
        RelatedRef("same-hub", "s_invite", "invite", "Maya's party", "Sat · 3:00 PM"),
      ),
    ),
    Card(
      id = "s_file", kind = "action", title = "Permission slip — sign by Thursday",
      provenance = Provenance("email"), notBefore = "2026-06-20T11:00:00Z",
      type = "file", privacy = CardPrivacy("on_device"),
      payload = Payload(file = FilePayload(filename = "permission.pdf", mime = "application/pdf",
        size = 240000, pages = 2, source = "email", docRef = "https://drive.example/permission.pdf")),
    ),
    Card(
      id = "s_contact", kind = "action", title = "Jake's Rentals delivers at 1pm",
      provenance = Provenance("email"), notBefore = "2026-06-20T12:00:00Z",
      type = "contact",
      payload = Payload(contact = ContactPayload(name = "Jake's Rentals", company = "Jake's Rentals",
        role = "Party equipment", phone = "+15555550142", email = "hello@jakes.example",
        address = "14 Mill St, Riverside", deliveryWindow = "Today 1:00–1:30 PM")),
    ),
    Card(
      id = "s_geo", kind = "info", title = "Riverside Park — 8 min away",
      provenance = Provenance("user"), notBefore = "2026-06-20T13:00:00Z",
      type = "geo", privacy = CardPrivacy("on_device"),
      payload = Payload(geo = GeoPayload(label = "Riverside Park — Shelter B", address = "200 Riverside Dr",
        lat = 37.42, lng = -122.08, etaMin = 8, distance = "2.4 mi", travelMode = "driving")),
    ),
    Card(
      id = "s_link", kind = "action", title = "Soccer registration closes Friday",
      provenance = Provenance("user"), notBefore = "2026-06-20T14:00:00Z",
      type = "link",
      payload = Payload(link = LinkPayload(url = "https://riversideyouth.org/register",
        domain = "riversideyouth.org", title = "Fall 2026 Youth Soccer", kind = "form", fieldCount = 8)),
    ),
  )
}
