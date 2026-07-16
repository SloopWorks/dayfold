package com.sloopworks.dayfold.client

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReducerTest {
  private val json = Json { ignoreUnknownKeys = true }
  private val hubRequest = HubRequestKey(HubTenantGeneration(1L, 1L), 1L)

  @Test fun `CardsLoaded replaces the card list (DB is truth)`() {
    var s = AppState(content = ContentState(cards = listOf(Card("old", title = "Old"))))
    s = rootReducer(s, CardsLoaded(listOf(Card("a", title = "A"), Card("b", title = "B"))))
    assertEquals(listOf("a", "b"), s.content.cards.map { it.id })
  }

  // Slice 5b (ADR 0038 §W5) — hidden ids are DB-fed (bridge) into state; "Show hidden" is a
  // per-view toggle that OpenHub/CloseHub reset so it never leaks across hubs.
  @Test fun `HiddenLoaded replaces the hidden id set`() {
    var s = AppState(hubs = HubState(hiddenIds = setOf("old")))
    s = rootReducer(s, HiddenLoaded(setOf("b1", "b2")))
    assertEquals(setOf("b1", "b2"), s.hubs.hiddenIds)
  }

  @Test fun `SetShowHidden toggles the view flag`() {
    val on = rootReducer(AppState(), SetShowHidden(true))
    assertTrue(on.hubs.showHidden)
    assertFalse(rootReducer(on, SetShowHidden(false)).hubs.showHidden)
  }

  @Test fun `opening or closing a hub resets Show hidden`() {
    assertFalse(rootReducer(AppState(hubs = HubState(showHidden = true)), OpenHub("h1", hubRequest)).hubs.showHidden)
    assertFalse(rootReducer(AppState(hubs = HubState(showHidden = true)), CloseHub).hubs.showHidden)
  }

  @Test fun `sync status lifecycle`() {
    val started = rootReducer(AppState(), SyncStarted)
    assertTrue(started.content.syncing); assertNull(started.content.error)
    val ok = rootReducer(started.copy(content = started.content.copy(error = "stale")), SyncSucceeded)
    assertFalse(ok.content.syncing); assertNull(ok.content.error)
    val failed = rootReducer(started, SyncFailed("boom"))
    assertFalse(failed.content.syncing); assertEquals("boom", failed.content.error)
  }

  @Test fun `parses the real API sync envelope`() {
    val body = """{"changes":{"cards":[{"id":"welcome","kind":"info","title":"Hello","body_md":null}]},
      "tombstones":[],"next_cursor":"abc","has_more":false}"""
    val resp = json.decodeFromString(SyncResponse.serializer(), body)
    assertEquals("welcome", resp.changes.cards[0].id)
    assertEquals("abc", resp.nextCursor)
  }

  @Test fun `decodes a full SELECT-star sync row without losing feed fields (F3 contract)`() {
    val body = """{"changes":{"cards":[{"id":"c1","family_id":"fam1","kind":"info","title":"T",
      "body_md":null,"target_hub_id":"h1","target_section_id":null,"target_block_id":null,
      "provenance":{"credential_id":"hc","source":"claude"},"triggers":null,"actions":null,
      "not_before":"2026-06-18T16:00:00Z","expires_at":null,"version":"1",
      "created_at":"2026-06-18T10:00:00Z","updated_at":"2026-06-18T10:00:00Z","deleted_at":null}]},
      "tombstones":[{"type":"card","id":"old"}],"next_cursor":"abc","has_more":false}"""
    val resp = json.decodeFromString(SyncResponse.serializer(), body)
    val c = resp.changes.cards[0]
    assertEquals("c1", c.id)
    assertEquals("h1", c.targetHubId)
    assertEquals("2026-06-18T16:00:00Z", c.notBefore)
    assertEquals("old", resp.tombstones[0].id)
  }

  @Test fun `store wires reducer end to end`() {
    val store = createTestAppStore()
    store.dispatch(CardsLoaded(listOf(Card("x", title = "X"))))
    assertEquals(1, store.state.content.cards.size)
  }

  // ── CL-6 nav ────────────────────────────────────────────────────────────────

  @Test fun `NavToDetail pushes, dedups a re-tap of the top, NavBack pops`() {
    var s = AppState(content = ContentState(cards = listOf(Card("a", title = "A"), Card("b", title = "B"))))
    s = rootReducer(s, NavToDetail("a")); assertEquals(listOf("a"), s.navigation.detailStack)
    s = rootReducer(s, NavToDetail("a")); assertEquals(listOf("a"), s.navigation.detailStack)   // dedup top
    s = rootReducer(s, NavToDetail("b")); assertEquals(listOf("a", "b"), s.navigation.detailStack)
    s = rootReducer(s, NavBack); assertEquals(listOf("a"), s.navigation.detailStack)
    s = rootReducer(s, NavBack); assertEquals(emptyList(), s.navigation.detailStack)
    s = rootReducer(s, NavBack); assertEquals(emptyList(), s.navigation.detailStack)             // empty-safe
  }

  @Test fun `RestoreDetailStack sets the stack verbatim, before cards load, then self-cleans on CardsLoaded`() {
    // fresh store after an Activity recreation: no cards yet, restore the saved ids
    var s = AppState()
    s = rootReducer(s, RestoreDetailStack(listOf("firestone", "gone")))
    assertEquals(listOf("firestone", "gone"), s.navigation.detailStack)   // set verbatim — NOT gated on card presence
    // once the DB→store bridge repopulates, an id whose card didn't come back is pruned
    s = rootReducer(s, CardsLoaded(listOf(Card("firestone", title = "Firestone"))))
    assertEquals(listOf("firestone"), s.navigation.detailStack)           // "gone" dropped; the real detail survives
  }

  @Test fun `NavToDetail to a card not in cache is a no-op (dangling related ref)`() {
    val s = AppState(content = ContentState(cards = listOf(Card("a", title = "A"))), navigation = NavigationState(detailStack = listOf("a")))
    val after = rootReducer(s, NavToDetail("ghost")) // target not cached
    assertEquals(listOf("a"), after.navigation.detailStack)      // unchanged — stays on current detail
  }

  @Test fun `CardsLoaded prunes nav-stack ids that synced away`() {
    var s = AppState(content = ContentState(cards = listOf(Card("a", title = "A"))), navigation = NavigationState(detailStack = listOf("a")))
    s = rootReducer(s, CardsLoaded(listOf(Card("b", title = "B")))) // 'a' gone
    assertEquals(emptyList(), s.navigation.detailStack)
  }

  @Test fun `currentDetailCard resolves the top id, null when absent`() {
    val cards = listOf(Card("a", title = "A"), Card("b", title = "B"))
    assertNull(currentDetailCard(AppState(content = ContentState(cards = cards))))
    assertEquals("b", currentDetailCard(AppState(content = ContentState(cards = cards), navigation = NavigationState(detailStack = listOf("a", "b"))))?.id)
    assertNull(currentDetailCard(AppState(content = ContentState(cards = cards), navigation = NavigationState(detailStack = listOf("gone")))))
  }

  // ── Hubs reducer (ADR 0006/0030) ──
  private fun hub(id: String) = Hub(id = id, title = id, status = "active", visibility = "family")

  @Test fun `HubsLoaded closes the open hub when it is revoked (no longer in the DB list)`() {
    // viewing hub "h1" with its tree loaded; a sync drops it (revocation tombstone)
    val open = AppState(hubs = HubState(currentHubId = "h1", currentHubTree = HubTree(hub = hub("h1")), busy = true))
    val s = rootReducer(open, HubsLoaded(listOf(hub("h2"))))   // h1 gone
    assertNull(s.hubs.currentHubId)        // kicked back to the list — can't keep viewing revoked content
    assertNull(s.hubs.currentHubTree)
    assertFalse(s.hubs.busy)
  }

  @Test fun `HubsLoaded keeps the open hub + tree when it is still present`() {
    val tree = HubTree(hub = hub("h1"))
    val open = AppState(hubs = HubState(currentHubId = "h1", currentHubTree = tree))
    val s = rootReducer(open, HubsLoaded(listOf(hub("h1"), hub("h2"))))
    assertEquals("h1", s.hubs.currentHubId)
    assertEquals(tree, s.hubs.currentHubTree)
  }

  @Test fun `HubsFailed stops the spinner + sets the error, but keeps the open hub`() {
    // Unlike HubsLoaded's revocation path, a FAILED list refresh is non-destructive: it must
    // not evict the hub you're reading (transient network blip) — just clear busy + say why.
    val tree = HubTree(hub = hub("h1"))
    val open = AppState(hubs = HubState(currentHubId = "h1", currentHubTree = tree, busy = true))
    val s = rootReducer(open, HubsFailed("network down"))
    assertFalse(s.hubs.busy)                  // no stuck spinner on the error path
    assertEquals("network down", s.hubs.error)
    assertEquals("h1", s.hubs.currentHubId)        // still reading h1
    assertEquals(tree, s.hubs.currentHubTree)      // open hub preserved through the failure
  }

  @Test fun `OpenHub enters a hub busy and clears any stale arrival focus`() {
    val s = rootReducer(AppState(hubs = HubState(focusBlockId = "old-blk")), OpenHub("h9", hubRequest))
    assertEquals("h9", s.hubs.currentHubId)
    assertTrue(s.hubs.busy)
    assertNull(s.hubs.focusBlockId)      // a fresh manual open must not carry a prior deep-link's focus
  }

  @Test fun `OpenHub sets the arrival block and CloseHub clears the whole hub substate`() {
    val focused = rootReducer(AppState(), OpenHub("h1", hubRequest, focusBlockId = "blk-7"))
    assertEquals("blk-7", focused.hubs.focusBlockId)
    val closed = rootReducer(focused.copy(hubs = focused.hubs.copy(currentHubTree = HubTree(hub = hub("h1")))), CloseHub)
    assertNull(closed.hubs.currentHubId); assertNull(closed.hubs.currentHubTree); assertNull(closed.hubs.focusBlockId)
  }

  @Test fun `cross-surface hub deep-link return — CloseHubToFeed routes to Feed, keeps the detail, clears the flag`() {
    val s = AppState(navigation = NavigationState(route = Route.Hubs, detailStack = listOf("c1")), hubs = HubState(currentHubId = "h1", fromFeedDetail = true))
    val after = rootReducer(s, CloseHubToFeed)
    assertEquals(Route.Feed, after.navigation.route)      // back on Feed → the detailStack card detail re-renders
    assertNull(after.hubs.currentHubId)
    assertFalse(after.hubs.fromFeedDetail)
    assertEquals(listOf("c1"), after.navigation.detailStack)   // the originating detail is preserved
  }

  @Test fun `OpenHubs atomically carries and clears the return destination`() {
    assertTrue(rootReducer(AppState(), OpenHubs(HubReturnDestination.FEED_DETAIL)).hubs.fromFeedDetail)
    assertFalse(rootReducer(AppState(hubs = HubState(fromFeedDetail = true)), OpenHubs()).hubs.fromFeedDetail)
    assertFalse(rootReducer(AppState(hubs = HubState(fromFeedDetail = true)), CloseHub).hubs.fromFeedDetail)
  }

  // ADR 0030 audience sheet (who-can-see-this-hub). The non-obvious property: open AND
  // close both CLEAR currentAudience, so a previously-loaded audience can't flash while
  // a different hub's sheet loads. (No test referenced these transitions before.)
  @Test fun `audience sheet lifecycle — open clears stale, load populates, close clears`() {
    val stale = HubAudience(visibility = "just_me", members = listOf(HubAudienceMember(uid = "u1")))
    // open from a state carrying a stale audience → sheet open, audience cleared (no flash)
    val opened = rootReducer(
      AppState(hubs = HubState(currentHubId = "h1", currentHubRequest = hubRequest, currentAudience = stale)),
      OpenAudienceSheet,
    )
    assertTrue(opened.hubs.audienceSheetOpen); assertNull(opened.hubs.currentAudience)
    val requested = rootReducer(opened, HubAudienceRequested("h1", hubRequest))
    // load populates the audience; the sheet stays open
    val loaded = rootReducer(requested, HubAudienceLoaded("h1", hubRequest, HubAudience(visibility = "family",
      members = listOf(HubAudienceMember(uid = "u1", permitted = true), HubAudienceMember(uid = "u2")))))
    assertTrue(loaded.hubs.audienceSheetOpen)
    assertEquals("family", loaded.hubs.currentAudience?.visibility)
    assertEquals(2, loaded.hubs.currentAudience?.members?.size)
    // close clears both the open flag and the audience
    val closed = rootReducer(loaded, CloseAudienceSheet)
    assertFalse(closed.hubs.audienceSheetOpen); assertNull(closed.hubs.currentAudience)
  }

  @Test fun `hub results require the current tenant generation hub and request`() {
    val generation = HubTenantGeneration(identityEpoch = 7L, familyRevision = 11L)
    val current = HubRequestKey(generation, requestId = 3L)
    val staleRequest = HubRequestKey(generation, requestId = 2L)
    val staleGeneration = HubRequestKey(HubTenantGeneration(6L, 11L), requestId = 3L)
    val originalTree = HubTree(hub("h2"))
    val state = AppState(hubs = HubState(
      currentHubId = "h2", currentHubTree = originalTree, currentHubRequest = current, busy = true,
    ))

    listOf(
      HubTreeLoaded("h1", current, HubTree(hub("h1"))),
      HubTreeLoaded("h2", staleRequest, HubTree(hub("h2"))),
      HubTreeLoaded("h2", staleGeneration, HubTree(hub("h2"))),
    ).forEach { action ->
      assertEquals(state, rootReducer(state, action))
    }
  }

  @Test fun `audience results and failures require the latest admitted request`() {
    val generation = HubTenantGeneration(identityEpoch = 2L, familyRevision = 4L)
    val treeRequest = HubRequestKey(generation, requestId = 1L)
    val currentAudienceRequest = HubRequestKey(generation, requestId = 3L)
    val staleAudienceRequest = HubRequestKey(generation, requestId = 2L)
    val state = AppState(hubs = HubState(
      currentHubId = "h1", currentHubRequest = treeRequest, audienceSheetOpen = true,
      currentAudienceRequest = currentAudienceRequest,
    ))

    assertEquals(
      state,
      rootReducer(state, HubAudienceLoaded("h1", staleAudienceRequest, HubAudience("family"))),
    )
    assertEquals(
      state,
      rootReducer(state, AudienceFailed("h1", staleAudienceRequest, "late")),
    )
    assertEquals(
      state,
      rootReducer(state, HubManageFailed("h2", currentAudienceRequest, "wrong hub")),
    )
  }

  @Test fun `session terminals clear tenant state but preserve device-owned state`() {
    val deviceConfig = NotifConfig(enabled = true, dailyCap = 2)
    val state = AppState(
      content = ContentState(cards = listOf(Card("tenant", title = "Tenant content"))),
      session = SessionState(session = Session("access", "refresh"), activeFamilyId = "family"),
      notifications = NotificationState(deviceConfig, LocationPermission.Always, NotificationPermission.Granted),
    )

    listOf(rootReducer(state, SignedOut), rootReducer(state, SessionExpired)).forEach { terminal ->
      assertEquals(Route.SignIn, terminal.navigation.route)
      assertTrue(terminal.content.cards.isEmpty())
      assertNull(terminal.session.session)
      assertNull(terminal.session.activeFamilyId)
      assertEquals(deviceConfig, terminal.notifications.config)
      assertEquals(LocationPermission.Always, terminal.notifications.locationPermission)
      assertEquals(NotificationPermission.Granted, terminal.notifications.notificationPermission)
    }
  }
}
