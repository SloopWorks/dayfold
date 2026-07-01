package com.sloopworks.dayfold.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// DB-as-source-of-truth round-trip (ADR 0020), against an in-memory SQLDelight DB.
class ContentStoreTest {
  private val lenientJson = Json { ignoreUnknownKeys = true }
  private fun store() = ContentStore.create(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
  private fun card(id: String, title: String, nb: String? = null) =
    Card(id = id, kind = "info", title = title, provenance = Provenance("claude"), notBefore = nb)

  @Test fun `upsert + activeCards round-trip, ordered not_before-nulls-last then id`() {
    val s = store()
    s.applyDelta(
      changedCards = listOf(
        card("b", "Soccer", nb = "2026-06-18T16:00:00Z"),
        card("z", "No time"),                                  // null not_before → last
        card("a", "Leave by 3:30", nb = "2026-06-18T15:30:00Z"),
      ),
      changedHubs = emptyList(), changedSections = emptyList(), changedBlocks = emptyList(), tombstones = emptyList(), nextCursor = "cur1", nowIso = "2026-06-18T10:00:00Z",
    )
    assertEquals(listOf("a", "b", "z"), s.activeCards().map { it.id })
    assertEquals("cur1", s.cursor())
    assertEquals("claude", s.activeCards().first().provenance?.source)
  }

  @Test fun `upsert updates in place and tombstone removes`() {
    val s = store()
    s.applyDelta(listOf(card("a", "v1")), emptyList(), emptyList(), emptyList(), emptyList(), "c1", "2026-06-18T10:00:00Z")
    s.applyDelta(listOf(card("a", "v2"), card("b", "B")), emptyList(), emptyList(), emptyList(), emptyList(), "c2", "2026-06-18T10:01:00Z")
    assertEquals("v2", s.activeCards().first { it.id == "a" }.title)
    assertEquals(2, s.activeCards().size)
    s.applyDelta(emptyList(), emptyList(), emptyList(), emptyList(), tombstones = listOf(Tombstone("card", "a")), nextCursor = "c3", nowIso = "2026-06-18T10:02:00Z")
    assertEquals(listOf("b"), s.activeCards().map { it.id })
    assertEquals("c3", s.cursor())
  }

  @Test fun `fresh db has no cursor`() {
    assertNull(store().cursor())
  }

  @Test fun `activeCardsFlow emits current rows then re-emits on write`() = runBlocking {
    val s = store()
    s.activeCardsFlow().test {
      assertEquals(emptyList(), awaitItem().map { it.id })           // initial: empty DB
      s.applyDelta(listOf(card("a", "A")), emptyList(), emptyList(), emptyList(), emptyList(), "c1", "2026-06-18T10:00:00Z")
      assertEquals(listOf("a"), awaitItem().map { it.id })           // re-emits after write
      cancelAndIgnoreRemainingEvents()
    }
  }

  // ── CL-4: typed payload sync→DB→store→projection ──────────────────────────

  @Test fun `deep-link target (hub-section-block) survives applyDelta to activeCards`() {
    val s = store()
    s.applyDelta(
      changedCards = listOf(Card(
        id = "d", kind = "action", title = "Party shopping", provenance = Provenance("claude"),
        targetHubId = "h_party", targetSectionId = "sec_shop", targetBlockId = "blk_chk",
      )),
      changedHubs = emptyList(), tombstones = emptyList(), nextCursor = "c1", nowIso = "2026-06-20T10:00:00Z",
    )
    val d = assertNotNull(s.activeCards().firstOrNull { it.id == "d" })
    assertEquals("h_party", d.targetHubId)      // was dropped by the cache before this fix
    assertEquals("sec_shop", d.targetSectionId)
    assertEquals("blk_chk", d.targetBlockId)
  }

  @Test fun `typed payload + type + privacy + hubRef survive applyDelta to activeCards`() {
    val s = store()
    s.applyDelta(
      changedCards = listOf(
        Card(id = "f", kind = "action", title = "Permission slip", provenance = Provenance("email"),
          type = "file", hubRef = "hub1", privacy = CardPrivacy("on_device"),
          payload = Payload(file = FilePayload(filename = "p.pdf", mime = "application/pdf", size = 240000, pages = 2))),
        Card(id = "i", kind = "action", title = "Party", provenance = Provenance("email"),
          type = "invite",
          payload = Payload(invite = InvitePayload(eventName = "Maya's party", rsvpState = "none", guestCount = 12))),
        Card(id = "g", kind = "info", title = "Soccer", provenance = Provenance("user"),
          type = "geo",
          payload = Payload(geo = GeoPayload(label = "Field", lat = 37.42, lng = -122.08, etaMin = 14))),
      ),
      changedHubs = emptyList(), changedSections = emptyList(), changedBlocks = emptyList(), tombstones = emptyList(), nextCursor = "c1", nowIso = "2026-06-20T10:00:00Z",
    )
    val byId = s.activeCards().associateBy { it.id }

    val f = assertNotNull(byId["f"])
    assertEquals("file", f.type)
    assertEquals("hub1", f.hubRef)
    assertEquals("on_device", f.privacy?.storage)
    assertEquals("p.pdf", f.payload?.file?.filename)
    assertEquals(2L, f.payload?.file?.pages)
    assertNull(f.payload?.invite)                       // wrapper: only the one variant set

    val i = assertNotNull(byId["i"])
    assertEquals("Maya's party", i.payload?.invite?.eventName)
    assertEquals("none", i.payload?.invite?.rsvpState)
    assertEquals(12L, i.payload?.invite?.guestCount)

    val g = assertNotNull(byId["g"])
    assertEquals(37.42, g.payload?.geo?.lat)
    assertEquals(14L, g.payload?.geo?.etaMin)
  }

  @Test fun `kind-only card round-trips with null type and payload (back-compat)`() {
    val s = store()
    s.applyDelta(listOf(card("p", "plain")), emptyList(), emptyList(), emptyList(), emptyList(), "c1", "2026-06-20T10:00:00Z")
    val p = assertNotNull(s.activeCards().firstOrNull { it.id == "p" })
    assertNull(p.type); assertNull(p.payload); assertNull(p.privacy); assertNull(p.hubRef)
  }

  @Test fun `related edges + relatedKicker survive applyDelta to activeCards`() {
    val s = store()
    s.applyDelta(
      changedCards = listOf(
        Card(id = "e", kind = "action", title = "School email", provenance = Provenance("email"),
          type = "email", payload = Payload(email = EmailPayload(from = "Lincoln")),
          relatedKicker = "FROM THE SAME EMAIL",
          related = listOf(
            RelatedRef(relation = "attachment", targetId = "f1", targetType = "file", title = "permission.pdf", sub = "240 KB"),
            RelatedRef(relation = "same-hub", targetId = "i1", targetType = "invite", title = "Maya's party"),
          )),
      ),
      changedHubs = emptyList(), changedSections = emptyList(), changedBlocks = emptyList(), tombstones = emptyList(), nextCursor = "c1", nowIso = "2026-06-20T10:00:00Z",
    )
    val e = assertNotNull(s.activeCards().firstOrNull { it.id == "e" })
    assertEquals("FROM THE SAME EMAIL", e.relatedKicker)
    assertEquals(2, e.related?.size)
    assertEquals("f1", e.related?.first()?.targetId)
    assertEquals("attachment", e.related?.first()?.relation)
  }

  // Slice 5b (ADR 0038 §W4) — the author-gate substrate: /sync emits created_by on each
  // block (set-once server-side, 5a). The client must persist it + project it onto
  // HubBlock.createdBy so the delete sheet can compare it to the signed-in user id.
  @Test fun `block created_by survives applyDelta and projects onto the tree`() = runBlocking {
    val s = store()
    s.applyDelta(
      changedCards = emptyList(),
      changedHubs = listOf(Hub(id = "h1", title = "Party", visibility = "family")),
      changedSections = listOf(HubSection(id = "s1", hubId = "h1", title = "Plan", ord = 0)),
      changedBlocks = listOf(HubBlock(id = "b1", sectionId = "s1", type = "text",
        bodyMd = "by mom", createdBy = "usr_mom")),
      tombstones = emptyList(), nextCursor = "c1", nowIso = "2026-06-29T10:00:00Z",
    )
    s.hubTreeFlow("h1").test {
      val tree = awaitItem()
      assertEquals("usr_mom", tree?.blocks?.firstOrNull { it.id == "b1" }?.createdBy)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun `corrupt cached related JSON yields null related, card still renders`() {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    val s = ContentStore.create(driver)
    driver.execute(null,
      "INSERT INTO card(id,kind,title,type,related,updated_at,deleted) " +
      "VALUES('z','info','Z','email','not-json[',  '2026-06-20T10:00:00Z',0)", 0)
    val z = assertNotNull(s.activeCards().firstOrNull { it.id == "z" })
    assertEquals("Z", z.title)
    assertNull(z.related)
  }

  @Test fun `corrupt cached payload JSON yields null payload, card still renders, no crash`() {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    val s = ContentStore.create(driver)
    // inject a row with structurally-broken payload + valid privacy directly
    driver.execute(null,
      "INSERT INTO card(id,kind,title,type,payload,privacy,updated_at,deleted) " +
      "VALUES('x','info','Broken','file','not-json{','{\"storage\":\"on_device\"}','2026-06-20T10:00:00Z',0)", 0)
    val x = assertNotNull(s.activeCards().firstOrNull { it.id == "x" })
    assertEquals("Broken", x.title)        // card survives
    assertNull(x.payload)                   // bad JSON → guarded null
    assertEquals("on_device", x.privacy?.storage)  // independent field still decodes
  }

  @Test fun `wire SyncResponse with typed payload decodes into Card`() {
    val wire = """
      {"changes":{"cards":[
        {"id":"w","kind":"action","title":"RSVP","type":"invite",
         "payload":{"invite":{"eventName":"Picnic","guestCount":5}},
         "privacy":{"storage":"on_device"},"hub_ref":"h1",
         "family_id":"famA","version":"3","triggers":null}
      ]},"tombstones":[],"next_cursor":"c1","has_more":false}
    """.trimIndent()
    val resp = lenientJson.decodeFromString(SyncResponse.serializer(), wire)
    val c = resp.changes.cards.single()
    assertEquals("invite", c.type)
    assertEquals("Picnic", c.payload?.invite?.eventName)
    assertEquals(5L, c.payload?.invite?.guestCount)
    assertEquals("on_device", c.privacy?.storage)
    assertEquals("h1", c.hubRef)               // @SerialName("hub_ref")
    assertTrue(resp.tombstones.isEmpty())
  }

  // ADR 0045 — Hub.timeline DB round-trip (the bug: timeline was always null on-device
  // because the hub table had no timeline column and rowToHub had no mapping).
  @Test fun `hub timeline survives applyDelta through DB to activeHubs`() {
    val s = store()
    val tl = Timeline(
      title = "Vacation Roadmap",
      tz = "America/New_York",
      stops = listOf(
        Stop(at = "2026-07-04T12:00:00Z", title = "Depart", major = true),
        Stop(at = "2026-07-04T18:00:00Z", title = "Arrive hotel", sub = "Check-in 3pm"),
      ),
    )
    s.applyDelta(
      changedCards = emptyList(),
      changedHubs = listOf(Hub(id = "h_vac", title = "Summer Vacation", timeline = tl)),
      tombstones = emptyList(), nextCursor = "c1", nowIso = "2026-07-01T10:00:00Z",
    )
    val hub = assertNotNull(s.activeHubs().firstOrNull { it.id == "h_vac" })
    val got = assertNotNull(hub.timeline)           // was null before this fix
    assertEquals("Vacation Roadmap", got.title)
    assertEquals("America/New_York", got.tz)
    assertEquals(2, got.stops.size)
    assertEquals("Depart", got.stops[0].title)
    assertTrue(got.stops[0].major)
    assertEquals("Arrive hotel", got.stops[1].title)
    assertEquals("Check-in 3pm", got.stops[1].sub)
  }

  @Test fun `hub without timeline has null timeline after round-trip`() {
    val s = store()
    s.applyDelta(
      changedCards = emptyList(),
      changedHubs = listOf(Hub(id = "h_plain", title = "Plain Hub")),
      tombstones = emptyList(), nextCursor = "c1", nowIso = "2026-07-01T10:00:00Z",
    )
    val hub = assertNotNull(s.activeHubs().firstOrNull { it.id == "h_plain" })
    assertNull(hub.timeline)
  }
}
