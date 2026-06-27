package com.sloopworks.dayfold.client.fake

import com.sloopworks.dayfold.client.BlockPayload
import com.sloopworks.dayfold.client.Card
import com.sloopworks.dayfold.client.Changes
import com.sloopworks.dayfold.client.ChecklistItem
import com.sloopworks.dayfold.client.DeviceCredential
import com.sloopworks.dayfold.client.FamilyMember
import com.sloopworks.dayfold.client.FamilyMembership
import com.sloopworks.dayfold.client.Hub
import com.sloopworks.dayfold.client.HubAudience
import com.sloopworks.dayfold.client.HubAudienceMember
import com.sloopworks.dayfold.client.HubBlock
import com.sloopworks.dayfold.client.HubMedia
import com.sloopworks.dayfold.client.HubSection
import com.sloopworks.dayfold.client.PendingDevice
import com.sloopworks.dayfold.client.PendingMember
import com.sloopworks.dayfold.client.Provenance
import com.sloopworks.dayfold.client.SampleData
import com.sloopworks.dayfold.client.SyncResponse
import com.sloopworks.dayfold.client.WhoamiResponse

// Registry of selectable fake backends. Each models a different shape of the world
// so the UI can be exercised in debug builds with zero live server. Selected via the
// debug-drawer Backend switcher (`fake://<id>`) on Android, or DAYFOLD_API=fake://<id>
// on desktop. Pure data — the FakeBackend router serializes these into wire JSON.
object FakeScenarios {
  // Reuse the canonical demo cards (SampleData "mirrors the CLI templates") rather
  // than re-authoring them — one source of truth for "the typed-card showcase".
  private val showcaseCards: List<Card> = SampleData.cards

  private const val FAM = "fam_fake"

  private fun membership(status: String = "active", name: String = "The Fake Family") =
    FamilyMembership(familyId = FAM, name = name, role = "owner", status = status)

  private val owner = FamilyMember(uid = "u_owner", displayName = "Pat (you)", role = "owner",
    status = "active", joinedAt = "2026-01-04T12:00:00Z")
  private val partner = FamilyMember(uid = "u_partner", displayName = "Sam", role = "adult",
    status = "active", joinedAt = "2026-02-10T12:00:00Z")

  // ── Hubs for the busy family (detail content travels in /sync changes) ──
  private val partyHub = Hub(id = "h_party", type = "party-event", title = "Maya's Birthday",
    status = "active", startAt = "2026-06-21T15:00:00Z", countdownTo = "2026-06-21T15:00:00Z",
    visibility = "family", createdBy = "u_owner")
  private val vacationHub = Hub(id = "h_vacation", type = "vacation", title = "Summer trip — Cape Cod",
    status = "planning", startAt = "2026-08-02T00:00:00Z", endAt = "2026-08-09T00:00:00Z",
    visibility = "family", createdBy = "u_partner")
  private val medicalHub = Hub(id = "h_medical", type = "medical", title = "Maya — pediatric follow-up",
    status = "active", startAt = "2026-06-30T09:30:00Z", visibility = "restricted", createdBy = "u_owner")

  // h_party stays at EXACTLY two sections (a fake-backend test pins the tree shape);
  // richness is added as MORE blocks inside them, not more sections.
  private val partySections = listOf(
    HubSection(id = "s_party_plan", hubId = "h_party", title = "Plan", ord = 0),
    HubSection(id = "s_party_logistics", hubId = "h_party", title = "Logistics", ord = 1),
  )
  private val partyBlocks = listOf(
    // markdown block → the full renderBlockMarkdown path: heading, bold/italic, a
    // table, a vetted link, and an ![image](https) that degrades to a 🖼 label.
    HubBlock(id = "b_party_brief", sectionId = "s_party_plan", type = "markdown",
      bodyMd = """
        ## Party day plan

        Doors at **3:00 PM** — backyard if it's _dry_.

        | Time | What |
        |---|---|
        | 1:00 PM | Rentals arrive |
        | 2:30 PM | Set up tables |
        | 3:00 PM | Guests arrive |

        See the [shared playlist](https://open.spotify.com/playlist/party).

        ![Backyard layout sketch](https://files.example/backyard-layout.png)
      """.trimIndent(), provenance = Provenance("claude"), ord = 0),
    HubBlock(id = "b_party_checklist", sectionId = "s_party_plan", type = "checklist",
      payload = BlockPayload(items = listOf(
        ChecklistItem(text = "Order cake", done = true),
        ChecklistItem(text = "Send invites", done = true),
        ChecklistItem(text = "Buy balloons", done = false),
        ChecklistItem(text = "Pick up rentals", done = false, due = "2026-06-21T13:00:00Z", assignee = "Sam"),
      )), provenance = Provenance("claude"), ord = 1),
    HubBlock(id = "b_party_milestone", sectionId = "s_party_plan", type = "milestone",
      bodyMd = "Party starts", payload = BlockPayload(date = "2026-06-21T15:00:00Z"), ord = 2),
    HubBlock(id = "b_party_contact", sectionId = "s_party_logistics", type = "contact",
      payload = BlockPayload(name = "Jake's Rentals", role = "Party equipment",
        phone = "+15555550142", email = "hello@jakes.example"), provenance = Provenance("email"), ord = 0),
    HubBlock(id = "b_party_location", sectionId = "s_party_logistics", type = "location",
      payload = BlockPayload(label = "Home — backyard", address = "200 Riverside Dr",
        lat = 37.42, lng = -122.08), ord = 1),
    // link block → 🔗 icon tile + label/domain; document block → 📄 icon tile.
    HubBlock(id = "b_party_link", sectionId = "s_party_logistics", type = "link",
      payload = BlockPayload(url = "https://open.spotify.com/playlist/party",
        label = "Party playlist", domain = "open.spotify.com"), provenance = Provenance("user"), ord = 2),
    HubBlock(id = "b_party_doc", sectionId = "s_party_logistics", type = "document",
      payload = BlockPayload(label = "Catering menu.pdf", docRef = "https://drive.example/menu.pdf"),
      provenance = Provenance("email"), ord = 3),
  )
  private val vacationSections = listOf(
    HubSection(id = "s_vac_itin", hubId = "h_vacation", title = "Itinerary", ord = 0),
  )
  private val vacationBlocks = listOf(
    HubBlock(id = "b_vac_note", sectionId = "s_vac_itin", type = "markdown",
      bodyMd = """
        ## Cape Cod — Aug 2–9

        Drive up **Saturday** morning. Beach house check-in _3 PM_.

        - [x] Book house
        - [x] Reserve ferry
        - [ ] Pack beach gear

        | Day | Plan |
        |---|---|
        | Sat | Drive up + check in |
        | Sun | Beach + lobster shack |

        Listing: [Seaside cottage](https://rentals.example/cape-cod-cottage).

        ![Cottage photo](https://rentals.example/cottage.jpg)
      """.trimIndent(), provenance = Provenance("claude"), ord = 0),
    HubBlock(id = "b_vac_loc", sectionId = "s_vac_itin", type = "location",
      payload = BlockPayload(label = "Seaside cottage", address = "8 Dune Rd, Truro MA",
        lat = 41.99, lng = -70.05), provenance = Provenance("user"), ord = 1),
    HubBlock(id = "b_vac_link", sectionId = "s_vac_itin", type = "link",
      payload = BlockPayload(url = "https://rentals.example/cape-cod-cottage",
        label = "Rental listing", domain = "rentals.example"), provenance = Provenance("user"), ord = 2),
    // itemized budget (canonical schema shape) → BudgetBar derives total/spent from
    // items[{amount, paid}] rather than the flat total/spent summary.
    HubBlock(id = "b_vac_budget", sectionId = "s_vac_itin", type = "budget",
      payload = BlockPayload(items = listOf(
        ChecklistItem(label = "House", amount = 1500.0, paid = true),
        ChecklistItem(label = "Ferry", amount = 180.0, paid = true),
        ChecklistItem(label = "Groceries", amount = 300.0, paid = false),
        ChecklistItem(label = "Activities", amount = 420.0, paid = false),
      )), provenance = Provenance("claude"), ord = 3),
  )
  private val medicalSections = listOf(
    HubSection(id = "s_med_notes", hubId = "h_medical", title = "Notes", ord = 0),
  )
  private val medicalBlocks = listOf(
    HubBlock(id = "b_med_note", sectionId = "s_med_notes", type = "text",
      bodyMd = "Bring the referral letter and insurance card.", ord = 0),
    HubBlock(id = "b_med_contact", sectionId = "s_med_notes", type = "contact",
      payload = BlockPayload(name = "Dr. Alice Nguyen", role = "Pediatrics — Riverside Clinic",
        phone = "+15555550188"), provenance = Provenance("claude"), ord = 1),
    HubBlock(id = "b_med_doc", sectionId = "s_med_notes", type = "document",
      payload = BlockPayload(label = "Referral letter.pdf", docRef = "https://drive.example/referral.pdf"),
      provenance = Provenance("email"), ord = 2),
  )

  // ── College hub — exercises ADR 0036 enrichment end-to-end (mascot hero, doc
  //    thumbnail, contact avatar/email, bare-email + bare-URL autolinking). Mirrors
  //    the real "Lillian → Butler" hub so the emulator confirms the render path. ──
  private val mascotHero =
    "https://upload.wikimedia.org/wikipedia/commons/thumb/1/17/Blue-40_%2849450467212%29.jpg/960px-Blue-40_%2849450467212%29.jpg"
  private val butlerWordmark =
    "https://upload.wikimedia.org/wikipedia/commons/9/9a/Butler_Bulldogs_script_Logo.png"
  private val collegeHub = Hub(id = "h_college", type = "starting-college",
    title = "Lillian → Butler · Fall 2026", status = "active",
    countdownTo = "2026-08-01T00:00:00Z", visibility = "family", createdBy = "u_owner",
    media = HubMedia(heroUrl = mascotHero, heroFit = "cover", icon = "school",
      accentColor = "#13294b", imageAlt = "Butler Blue, the Butler University bulldog mascot"))
  private val collegeSections = listOf(
    HubSection(id = "s_college_health", hubId = "h_college", title = "Health & Forms", ord = 0),
    HubSection(id = "s_college_contacts", hubId = "h_college", title = "Contacts", ord = 1),
  )
  private val collegeBlocks = listOf(
    HubBlock(id = "b_college_doc", sectionId = "s_college_health", type = "document",
      payload = BlockPayload(ref = "https://cdn.butler.edu/immunization-2026-27.pdf",
        label = "2026-27 Immunization Requirements (PDF)",
        thumbnailUrl = butlerWordmark, thumbnailAlt = "Butler University"),
      provenance = Provenance("claude"), ord = 0),
    HubBlock(id = "b_college_note", sectionId = "s_college_health", type = "markdown",
      bodyMd = "Email the waiver to healthinsurance@butler.edu and see https://www.butler.edu/health for details.",
      provenance = Provenance("claude"), ord = 1),
    HubBlock(id = "b_college_contact", sectionId = "s_college_contacts", type = "contact",
      payload = BlockPayload(name = "Butler Financial Aid", role = "Outside scholarships",
        email = "finaid@butler.edu", phone = "888-940-8100",
        avatarUrl = butlerWordmark, accentColor = "#13294b"),
      provenance = Provenance("claude"), ord = 0),
  )

  private val busyHubs = listOf(collegeHub, partyHub, vacationHub, medicalHub)
  private val busySections = collegeSections + partySections + vacationSections + medicalSections
  private val busyBlocks = collegeBlocks + partyBlocks + vacationBlocks + medicalBlocks

  private val busyAudiences = mapOf(
    "h_party" to HubAudience("family", listOf(
      HubAudienceMember("u_owner", "Pat (you)", "owner", permitted = true),
      HubAudienceMember("u_partner", "Sam", "adult", permitted = true),
    )),
    // Restricted: only the author sees it (ADR 0030 — the 🔒 treatment).
    "h_medical" to HubAudience("restricted", listOf(
      HubAudienceMember("u_owner", "Pat (you)", "owner", permitted = true),
      HubAudienceMember("u_partner", "Sam", "adult", permitted = false),
    )),
  )

  private fun sync(
    cards: List<Card> = emptyList(),
    hubs: List<Hub> = emptyList(),
    sections: List<HubSection> = emptyList(),
    blocks: List<HubBlock> = emptyList(),
  ) = SyncResponse(
    changes = Changes(cards = cards, hubs = hubs, sections = sections, blocks = blocks),
    nextCursor = "fake-cursor-1", hasMore = false,
  )

  // ── Scenario 1: busy family (the flagship populated state) ──
  private val busyFamily = FakeScenario("busy-family", "Fake · Busy family", FakeBackendData(
    whoami = WhoamiResponse(familyId = FAM, families = listOf(membership())),
    sync = sync(showcaseCards, busyHubs, busySections, busyBlocks),
    members = listOf(owner, partner),
    devices = listOf(
      DeviceCredential(id = "d_phone", kind = "app", label = "Pat's Pixel", current = true,
        lastUsedAt = "2026-06-25T08:00:00Z"),
      DeviceCredential(id = "d_cli", kind = "cli", label = "dayfold-cli @ macbook",
        scopes = listOf("content:write"), lastUsedAt = "2026-06-24T22:10:00Z"),
    ),
    audiences = busyAudiences,
  ))

  // ── Scenario 2: empty family (signed in, family exists, nothing in it) ──
  private val emptyNew = FakeScenario("empty-new", "Fake · Empty family", FakeBackendData(
    whoami = WhoamiResponse(familyId = FAM, families = listOf(membership(name = "Fresh Start"))),
    sync = sync(),                                   // no cards, no hubs → empty states
    members = listOf(owner),
  ))

  // ── Scenario 3: no family yet → CreateFamily route ──
  private val needsFamily = FakeScenario("needs-family", "Fake · No family yet", FakeBackendData(
    whoami = WhoamiResponse(familyId = null, families = emptyList()),
    sync = sync(),
  ))

  // ── Scenario 4: owner with pending approvals + a pending device grant ──
  private val ownerApprovals = FakeScenario("owner-approvals", "Fake · Pending approvals", FakeBackendData(
    whoami = WhoamiResponse(familyId = FAM, families = listOf(membership())),
    sync = sync(showcaseCards.take(2)),
    members = listOf(owner, partner),
    approvals = listOf(
      PendingMember(uid = "u_grandma", displayName = "Grandma Jo", role = "adult",
        provider = "google", requestedAt = "2026-06-24T18:00:00Z"),
      PendingMember(uid = "u_uncle", displayName = "Uncle Ray", role = "adult",
        provider = "apple", requestedAt = "2026-06-25T07:30:00Z"),
    ),
    pendingDevice = PendingDevice(userCode = "WXYZ-1234", client = "dayfold-cli",
      originIp = "73.12.4.9", originUa = "dayfold-cli/0.1", originKind = "residential",
      createdAt = "2026-06-25T08:55:00Z", expiresAt = "2026-06-25T09:10:00Z"),
  ))

  // ── Scenario 5: signed in, but /sync errors (feed error state) ──
  private val syncError = FakeScenario("sync-error", "Fake · Sync error", FakeBackendData(
    whoami = WhoamiResponse(familyId = FAM, families = listOf(membership())),
    sync = sync(),
    members = listOf(owner),
    syncStatus = 500,
  ))

  val all: List<FakeScenario> = listOf(busyFamily, emptyNew, needsFamily, ownerApprovals, syncError)

  fun byId(id: String): FakeScenario? = all.firstOrNull { it.id == id }
}
