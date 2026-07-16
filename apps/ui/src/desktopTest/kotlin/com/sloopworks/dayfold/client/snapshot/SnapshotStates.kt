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
    "caught-up" -> AppState(hubs = HubState(hubs = listOf(          // FeedSnapshotTest.kt:63
      Hub(id = "h1", title = "Starting College", status = "active", visibility = "family"))))
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

  // ── Feed extras ─────────────────────────────────────────────────────────────
  // Lift verbatim from FeedSnapshotTest.kt (inviteWith) — RSVP tri-state on the invite slice.
  fun inviteFeed(rsvp: String): AppState = AppState(cards = listOf(
    Card("inv", kind = "action", title = "Maya's party", provenance = Provenance("email"),
      type = "invite", payload = Payload(invite = InvitePayload(eventName = "Maya's party", rsvpState = rsvp))),
  ))

  // Lift verbatim from EnrichmentSnapshotTest.kt (`feed` val) — thumb tile + accent-only pair.
  private const val HERO = "https://upload.wikimedia.org/wikipedia/commons/0/0c/Logo.png"
  val ENRICHED_PAIR_FEED: AppState = AppState(cards = listOf(
    Card("trip", kind = "action", title = "Lisbon check-in opens today", bodyMd = "Window seats still free.",
      provenance = Provenance("claude"), media = CardMedia(icon = "travel", accentColor = "#1C6E8C", thumbnailUrl = HERO, imageAlt = "trip")),
    Card("school", kind = "action", title = "Dorm forms due Thursday", bodyMd = "Sign the housing waiver.",
      provenance = Provenance("claude"), media = CardMedia(icon = "school", accentColor = "#2C3E73")),
  ))

  // ── Enrichment hubs (EnrichmentSnapshotTest.kt `hubs` val, verbatim) ────────
  val ENRICHED_HUBS: List<Hub> = listOf(
    Hub(id = "trip", type = "vacation", title = "Summer trip — Lisbon", status = "planning", countdownTo = "2026-08-03T00:00:00Z",
      media = HubMedia(heroUrl = HERO, thumbnailUrl = HERO, heroFit = "cover", imageAlt = "Lisbon coast", icon = "travel", accentColor = "#1C6E8C")),
    Hub(id = "college", type = "starting-college", title = "Maya starts college", status = "planning", countdownTo = "2026-08-24T00:00:00Z",
      media = HubMedia(heroUrl = HERO, heroFit = "contain", imageAlt = "University logo", icon = "school", accentColor = "#2C3E73")),
    Hub(id = "med", type = "medical", title = "Dad's knee surgery", status = "active",
      media = HubMedia(icon = "medical", accentColor = "#1F8A6D")),
    Hub(id = "plain", type = "move", title = "House move (unenriched)", status = "planning"),
  )
  fun enrichedHubDetail(hub: Hub): AppState =
    AppState(hubs = HubState(currentHubTree = HubTree(hub = hub, sections = emptyList(), blocks = emptyList())))

  // ── Checklist hub (HubChecklistSnapshotTest.kt `tree()`, verbatim) ──────────
  val CHECKLIST_HUB: AppState = AppState(hubs = HubState(currentHubId = "h1", currentHubTree = HubTree(
    hub = Hub(id = "h1", type = "party-event", title = "Maya's birthday", status = "active", visibility = "family"),
    sections = listOf(HubSection(id = "s1", hubId = "h1", title = "Packing", ord = 0)),
    blocks = listOf(HubBlock(id = "b_chk", sectionId = "s1", type = "checklist", ord = 0, version = 3,
      payload = BlockPayload(items = listOf(
        ChecklistItem(id = "i1", text = "Cooler + ice", done = false, assignee = "Sam"),
        ChecklistItem(id = "i2", text = "Beach umbrella", done = false),
        ChecklistItem(id = "i3", text = "Sunscreen", done = true, doneBy = "Mom", doneAt = "2026-06-29T10:00:00Z"))))),
  )))

  // ── Timelines (TimelineCard/TimelineDetail/HubTimelineIntegration tests, verbatim) ──
  // All pinned to move-in day 10:40 ET so done/next markers are stable.
  const val TIMELINE_NOW = "2026-08-24T14:40:00Z"

  fun dayTimeline() = Timeline(
    title = "Move-in day", tz = "America/New_York",
    stops = listOf(
      Stop(at = "2026-08-24T07:30:00-04:00", title = "Car loaded", sub = "Boxes, mini-fridge, bedding", assignee = "Pat", done = true),
      Stop(at = "2026-08-24T08:00:00-04:00", title = "Left home", done = true),
      Stop(at = "2026-08-24T09:50:00-04:00", title = "Checked in", done = true,
        attachments = listOf(Attachment(kind = "nav", label = "Map", query = "Henderson Hall"))),
      Stop(at = "2026-08-24T11:00:00-04:00", title = "Elevator slot", sub = "20-min window — grab the loading cart",
        attachments = listOf(Attachment(kind = "link", label = "Booklist", url = "https://example.com"))),
      Stop(at = "2026-08-24T12:30:00-04:00", title = "Lunch break", sub = "Campus dining hall"),
      Stop(at = "2026-08-24T14:00:00-04:00", title = "Bookstore run"),
    ),
  )

  fun hubTimeline() = Timeline(
    title = "College Roadmap", tz = "America/New_York",
    stops = listOf(
      Stop(at = "2026-05-01", title = "Enrollment deposit paid", assignee = "Pat", done = true),
      Stop(at = "2026-06-12", title = "Housing application submitted", assignee = "Maya", done = true),
      Stop(at = "2026-07-20", title = "Orientation completed", done = true),
      Stop(at = "2026-08-24", title = "Move-in day", major = true, sub = "Henderson Hall, room 214", assignee = "Pat + Maya",
        // 3 attachments + wide assignee → exercises the FlowRow wrap (was clipped/ballooned).
        attachments = listOf(
          Attachment(kind = "nav", label = "Map", query = "Henderson Hall"),
          Attachment(kind = "open", label = "Move-in checklist", ref = AttachmentRef(hubId = "college", sectionId = "sec-move")),
          Attachment(kind = "call", label = "Residence Life", tel = "+18005551234"),
        )),
      Stop(at = "2026-09-15", title = "Family weekend"),
      Stop(at = "2026-10-01", title = "Graduation open house", major = true),
    ),
  )

  fun bothScalesTimeline() = Timeline(
    title = "Move-in day", tz = "America/New_York",
    stops = listOf(
      Stop(at = "2026-05-01", title = "Enrollment deposit", done = true),
      Stop(at = "2026-07-01", title = "Housing assigned", done = true),
      Stop(at = "2026-08-24T08:00:00-04:00", title = "Car loaded", done = true),
      Stop(at = "2026-08-24T11:00:00-04:00", title = "Elevator slot"),
      Stop(at = "2026-09-19", title = "Orientation"),
    ),
  )

  // TimelineCardSnapshotTest hub/hub-collapsed models (date-only stop sets).
  fun hubCardTimeline() = Timeline(
    tz = "America/New_York",
    stops = listOf(
      Stop("2026-05-01", "Planning phase"), Stop("2026-06-01", "Design complete"),
      Stop("2026-07-01", "Dev alpha"), Stop("2026-08-25", "Move-in day"), Stop("2026-09-15", "Launch"),
    ),
  )
  fun hubCollapsedCardTimeline() = Timeline(
    tz = "America/New_York",
    stops = listOf(
      Stop("2026-01-15", "Kickoff"), Stop("2026-02-15", "Research"),
      Stop("2026-03-15", "Design"), Stop("2026-04-15", "Alpha"),
      Stop("2026-09-15", "Move-in day"), Stop("2026-10-15", "Midterms"),
      Stop("2026-11-15", "Break"), Stop("2026-12-15", "Finals"),
    ),
  )

  // Derived-timeline hub (DerivedTimelineSnapshotTest.kt `tree()`, verbatim).
  fun derivedTimelineTree() = HubTree(
    hub = Hub(id = "h", title = "Maya starts college", countdownTo = "2026-08-24", startAt = "2026-05-01"),
    blocks = listOf(
      HubBlock(id = "m1", sectionId = "s", type = "milestone", payload = BlockPayload(date = "2026-06-12", label = "Housing confirmed")),
      HubBlock(id = "c1", sectionId = "s", type = "checklist", payload = BlockPayload(items = listOf(
        ChecklistItem(id = "i1", text = "Car loaded", due = "2026-08-24T07:30:00-04:00", done = true, assignee = "Pat"),
        ChecklistItem(id = "i2", text = "Bookstore & student ID", due = "2026-08-24T14:00:00-04:00"),
      ))),
      HubBlock(id = "loc", sectionId = "s", type = "location", payload = BlockPayload(label = "Checked in"),
        triggers = listOf(BlockTrigger(whenTrigger = TriggerWhen(at = "2026-08-24T09:50:00-04:00")))),
    ),
  )

  // Hub-with-authored-timeline states (HubTimelineIntegrationSnapshotTest.kt, verbatim).
  private fun integrationTimeline() = Timeline(
    title = "Move-in day", tz = "America/New_York",
    stops = listOf(
      Stop("2026-08-24T07:30:00-04:00", "Car loaded", done = true),
      Stop("2026-08-24T08:00:00-04:00", "Keys pickup", done = true),
      Stop("2026-08-24T09:50:00-04:00", "Checked in", done = true),
      Stop("2026-08-24T11:00:00-04:00", "Elevator slot"),
      Stop("2026-08-24T12:30:00-04:00", "Lunch break"),
      Stop("2026-08-24T13:00:00-04:00", "Bookstore run"),
      Stop("2026-08-24T14:00:00-04:00", "Final walkthrough"),
    ),
  )
  fun timelineHubCardState(): AppState = AppState(
    navigation = NavigationState(route = Route.Hubs), hubs = HubState(currentHubId = "h1",
    currentHubTree = HubTree(hub = Hub(id = "h1", type = "starting-college", title = "Move-in Day Hub",
      status = "active", visibility = "family", timeline = integrationTimeline())),
  ))
  fun timelineHubOverlayState(): AppState = timelineHubCardState().let { it.copy(hubs = it.hubs.copy(timelineDetail = TimelineScale.Day)) }
  fun timelineHubHiddenState(): AppState = timelineHubCardState().let { it.copy(hubs = it.hubs.copy(hiddenIds = setOf("timeline:h1"), showHidden = true)) }
  fun timelineNudgeState(): AppState = AppState(navigation = NavigationState(route = Route.Hubs), hubs = HubState(currentHubId = "h3",
    currentHubTree = HubTree(hub = Hub(id = "h3", type = "vacation", title = "Cape Cod", status = "active",
      visibility = "family", countdownTo = "2026-09-01"))))
  fun derivedTimelineHubState(): AppState =
    AppState(navigation = NavigationState(route = Route.Hubs), hubs = HubState(currentHubId = "h", currentHubTree = derivedTimelineTree()))

  // ── Auth / account / join (AuthScreensSnapshotTest.kt, verbatim) ────────────
  val ACCOUNT_STATE: AppState = AppState(
    session = SessionState(session = Session("a", "r"), families = listOf(FamilyMembership("fam1", "The Jacksons", role = "owner", status = "active")), activeFamilyId = "fam1"),
    navigation = NavigationState(route = Route.Account),
  )
  fun joinState(outcome: String? = null, familyName: String? = null): AppState =
    AppState(session = SessionState(joinOutcome = outcome, joinFamilyName = familyName), navigation = NavigationState(route = Route.JoinInvite))

  // ── Members (AuthScreensSnapshotTest.kt members* fixtures, verbatim) ────────
  private val FAM = listOf(FamilyMembership("fam1", "The Jacksons", role = "owner", status = "active"))
  fun membersState(preset: String): AppState = when (preset) {
    "roster" -> AppState(session = SessionState(families = FAM, activeFamilyId = "fam1"), familyAdmin = FamilyAdminState(
      pendingApprovals = listOf(PendingMember("u9", "Sam Rivera")),
      members = listOf(
        FamilyMember("u1", "Pat Jackson", role = "owner", status = "active"),
        FamilyMember("u2", "Maya Jackson", role = "adult", status = "active"),
      )))
    "loading" -> AppState(session = SessionState(families = FAM, activeFamilyId = "fam1"), familyAdmin = FamilyAdminState(rosterBusy = true))
    "error" -> AppState(session = SessionState(families = FAM, activeFamilyId = "fam1"), familyAdmin = FamilyAdminState(rosterError = "Couldn't load members. Try again."))
    "row-busy" -> AppState(session = SessionState(families = FAM, activeFamilyId = "fam1"), familyAdmin = FamilyAdminState(
      pendingApprovals = listOf(PendingMember("u9", "Sam Rivera")),
      members = listOf(FamilyMember("u1", "Pat Jackson", role = "owner", status = "active")),
      memberOpId = "u9"))
    else -> error("unknown members preset '$preset'")
  }

  // ── Devices (AuthScreensSnapshotTest.kt devices* fixtures, verbatim) ────────
  fun devicesState(preset: String): AppState = when (preset) {
    "list" -> AppState(devices = DeviceState(devices = listOf(
      DeviceCredential("c1", kind = "app", label = "iPhone 15 Pro", current = true),
      DeviceCredential("c2", kind = "cli", label = "claude-code · CI", lastUsedAt = "2026-06-19T09:00:00Z", lastUsedIp = "San Jose"),
    )))
    "loading" -> AppState(devices = DeviceState(listBusy = true))
    "error" -> AppState(devices = DeviceState(listError = "Couldn't load devices. Try again."))
    "row-busy" -> AppState(devices = DeviceState(devices = listOf(
      DeviceCredential("c1", kind = "app", label = "iPhone 15 Pro", current = true),
      DeviceCredential("c2", kind = "cli", label = "claude-code · CI"),
    ), operationId = "c2"))
    else -> error("unknown devices preset '$preset'")
  }

  // ── Device approval (AuthScreensSnapshotTest.kt authState/oneOwner/twoOwner) ─
  private val TWO_FAM = FAM + FamilyMembership("fam2", "Lake House", role = "owner", status = "active")
  fun authorizeState(originKind: String, multiOwner: Boolean = false): AppState {
    val fams = if (multiOwner) TWO_FAM else FAM
    return AppState(
      session = SessionState(session = Session("a", "r"), families = fams, activeFamilyId = fams.firstOrNull()?.familyId),
      navigation = NavigationState(route = Route.AuthorizeDevice),
      devices = DeviceState(pendingDevice = PendingDevice("WDJF-7K2P", client = "Dayfold CLI", originIp = "San Jose, CA · US",
        originUa = "dayfold-cli/1.0 · macOS", originKind = originKind)),
    )
  }

  // ── Places (PlacesListTest.kt, verbatim) ────────────────────────────────────
  val PLACES: List<Place> = listOf(
    Place(id = "home", kind = "home", label = "Home", lat = 0.0, lng = 0.0, radiusM = 150),
    Place(id = "school", kind = "school", label = "Maya's school", lat = 0.01, lng = 0.0, radiusM = 250),
    Place(id = "store", kind = "store", label = "Lincoln Market", lat = 0.02, lng = 0.0, radiusM = 200),
  )
}
