package com.sloopworks.dayfold.client

// Dev-only sample feed — a rich daily-briefing markdown card + the 6 typed cards
// (mirrors the CLI templates) + a related-edge email. Authored to EXERCISE every
// render path the feed/detail surfaces support: the full markdown renderer
// (headings, tables, checkboxes, vetted links, `![]()`→🖼 image degrade) on the
// untyped briefing card, plus the detail-screen meta rows that only light up when
// the typed payloads carry their richer fields (file modified/owner, link
// og-description/closes-at, invite rsvp-by/notes, contact hours, geo leave-by/
// parking, email attachments). Shells seed this into the DB behind a debug flag
// when no live API is reachable, so the on-device UI can be exercised without the
// server. NOT used in release paths.
object SampleData {
  val cards: List<Card> = listOf(
    // Untyped (type == null) → the full markdown renderer (renderBlockMarkdown):
    // ## heading, **bold**/_italic_, a | table |, - [x]/- [ ] checkboxes, a vetted
    // [link](https), and an ![image](https) that degrades to a tappable 🖼 label
    // (images are never inline-loaded at M0). This is the "single sleek daily
    // briefing" surface — the one card that shows the rich-text capability.
    Card(
      id = "s_briefing", kind = "info", title = "Today at a glance",
      provenance = Provenance("claude"), notBefore = "2026-06-20T08:00:00Z",
      bodyMd = """
        **3 things need you today** — and Maya's party is _Saturday_.

        | When | What |
        |---|---|
        | 1:00 PM | Jake's Rentals delivers |
        | 3:00 PM | Party starts |

        - [x] Cake ordered
        - [ ] Buy balloons
        - [ ] Pack jackets — [rain at soccer 4pm](https://weather.example/riverside)

        ![Radar — rain clears by 6pm](https://weather.example/radar.png)
      """.trimIndent(),
    ),
    Card(
      id = "s_invite", kind = "action", title = "Maya's party — reply by Thursday",
      provenance = Provenance("email"), notBefore = "2026-06-20T09:00:00Z",
      type = "invite", privacy = CardPrivacy("on_device"),
      payload = Payload(invite = InvitePayload(eventName = "Maya's Birthday", host = "The Garcias",
        startAt = "2026-06-21T15:00:00Z", place = "Home — backyard", rsvpBy = "2026-06-19T17:00:00Z",
        rsvpState = "none", guestCount = 12, confirmedCount = 8,
        notes = "Gluten-free cake — two guests have allergies. Backyard if it's dry.")),
    ),
    Card(
      id = "s_email", kind = "action", title = "School RSVP needs a reply by Thursday",
      provenance = Provenance("email"), notBefore = "2026-06-20T10:00:00Z",
      type = "email",
      payload = Payload(email = EmailPayload(from = "Lincoln Elementary", fromAddr = "office@lincoln.edu",
        subject = "Field trip permission", date = "2026-06-18T08:00:00Z", threadLen = 2,
        bodyExcerpt = "Please reply yes/no for Maya's field trip by Thursday.",
        labels = listOf("School", "Action needed"),
        attachments = listOf(
          EmailAttachment(name = "permission.pdf", mime = "application/pdf", size = 240000),
          EmailAttachment(name = "itinerary.pdf", mime = "application/pdf", size = 88000),
        ))),
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
        size = 240000, pages = 2, source = "Gmail · Lincoln Elementary", owner = "office@lincoln.edu",
        modified = "2026-06-18T08:00:00Z", docRef = "https://drive.example/permission.pdf")),
    ),
    Card(
      id = "s_contact", kind = "action", title = "Jake's Rentals delivers at 1pm",
      provenance = Provenance("email"), notBefore = "2026-06-20T12:00:00Z",
      type = "contact",
      payload = Payload(contact = ContactPayload(name = "Jake's Rentals", company = "Jake's Rentals",
        role = "Party equipment", phone = "+15555550142", email = "hello@jakes.example",
        address = "14 Mill St, Riverside", hours = "Mon–Sat · 8 AM–6 PM",
        deliveryWindow = "Today 1:00–1:30 PM")),
    ),
    Card(
      id = "s_geo", kind = "info", title = "Riverside Park — 8 min away",
      provenance = Provenance("user"), notBefore = "2026-06-20T13:00:00Z",
      type = "geo", privacy = CardPrivacy("on_device"),
      payload = Payload(geo = GeoPayload(label = "Riverside Park — Shelter B", address = "200 Riverside Dr",
        lat = 37.42, lng = -122.08, etaMin = 8, distance = "2.4 mi", travelMode = "driving",
        leaveBy = "2026-06-21T14:35:00Z", parking = "Lot C — free on weekends")),
    ),
    Card(
      id = "s_link", kind = "action", title = "Soccer registration closes Friday",
      provenance = Provenance("user"), notBefore = "2026-06-20T14:00:00Z",
      type = "link",
      payload = Payload(link = LinkPayload(url = "https://riversideyouth.org/register",
        domain = "riversideyouth.org", title = "Fall 2026 Youth Soccer", kind = "form", fieldCount = 8,
        ogDesc = "Register your player for the fall recreational season. Games Saturdays, Sept–Nov.",
        favicon = "https://riversideyouth.org/favicon.ico",
        closesAt = "2026-06-26T23:59:00Z", savedAt = "2026-06-20T13:45:00Z")),
    ),
  )
}
