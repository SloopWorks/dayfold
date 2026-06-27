package com.sloopworks.dayfold.client

import com.sloopworks.dayfold.client.fake.FakeBackend
import com.sloopworks.dayfold.client.fake.FakeScenarios
import com.sloopworks.dayfold.client.fake.fakeHttpClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Verifies each fake scenario serves wire-correct responses the real transport
// clients can decode, and that the critical invariants hold (drain loop terminates,
// hub detail rides in /sync, error scenario surfaces a non-2xx). ktor-client-mock is
// already a desktopTest dep. runBlocking<Unit> per the documented JUnit gotcha (a
// test whose last expression isn't Unit is silently skipped).
class FakeBackendTest {
  private val api = "http://fake.local"
  private fun http(id: String): HttpClient = fakeHttpClient(FakeBackend(FakeScenarios.byId(id)!!.data))
  private fun auth(id: String) = AuthClient(api, http(id))
  private fun sync(id: String, h: HttpClient = http(id)) = SyncClient(api, { "fam_fake" }, { "t" }, h)

  @Test fun `scenario ids are unique and resolvable`() {
    val ids = FakeScenarios.all.map { it.id }
    assertEquals(ids.toSet().size, ids.size, "duplicate scenario id")
    ids.forEach { assertNotNull(FakeScenarios.byId(it)) }
    assertTrue(FakeScenarios.all.size >= 5)
  }

  @Test fun `busy-family whoami has an active membership`() = runBlocking<Unit> {
    val who = auth("busy-family").whoami("t")
    assertEquals("fam_fake", who.familyId)
    assertTrue(who.families.any { it.status == "active" })
  }

  @Test fun `busy-family sync terminates the drain loop and carries hub tree content`() = runBlocking<Unit> {
    val page = sync("busy-family").fetchPage(null)
    assertFalse(page.hasMore, "fake /sync must end the drain loop")
    assertTrue(page.changes.cards.isNotEmpty())
    assertTrue(page.changes.hubs.any { it.id == "h_party" })
    // Hub DETAIL is fed from /sync changes (sections+blocks), NOT /tree.
    assertTrue(page.changes.sections.any { it.hubId == "h_party" })
    assertTrue(page.changes.blocks.any { it.sectionId == "s_party_plan" })
  }

  @Test fun `busy-family serves members, hubs list, and a restricted audience`() = runBlocking<Unit> {
    val h = http("busy-family")
    assertEquals(2, AuthClient(api, h).familyMembers("t", "fam_fake").size)
    assertEquals(4, HubClient(api, h).familyHubs("t", "fam_fake").size)   // college + party + vacation + medical
    val aud = HubClient(api, h).audience("t", "fam_fake", "h_medical")
    assertEquals("restricted", aud.visibility)
    assertTrue(aud.members.any { !it.permitted })   // partner is NOT permitted on the restricted hub
  }

  @Test fun `tree endpoint is derived from the same sync changes`() = runBlocking<Unit> {
    val res = HubClient(api, http("busy-family")).hubTree("t", "fam_fake", "h_party")
    assertTrue(res is HubTreeResult.Loaded)
    val tree = (res as HubTreeResult.Loaded).tree
    assertEquals(2, tree.sections.size)
    assertTrue(tree.blocks.isNotEmpty())
  }

  @Test fun `empty-new serves an empty feed`() = runBlocking<Unit> {
    val page = sync("empty-new").fetchPage(null)
    assertTrue(page.changes.cards.isEmpty())
    assertTrue(page.changes.hubs.isEmpty())
    assertFalse(page.hasMore)
  }

  @Test fun `needs-family has no active membership so the gate routes to CreateFamily`() = runBlocking<Unit> {
    assertTrue(auth("needs-family").whoami("t").families.none { it.status == "active" })
  }

  @Test fun `dev-token and create-family return canned values`() = runBlocking<Unit> {
    val a = auth("needs-family")
    assertEquals("fake-access", a.devToken("dev", "dev-user", "fake").access)
    assertEquals("fam_fake_new", a.createFamily("t", "New Fam"))
  }

  @Test fun `owner-approvals serves pending members and a pending device grant`() = runBlocking<Unit> {
    val a = auth("owner-approvals")
    assertEquals(2, a.familyApprovals("t", "fam_fake").size)
    assertTrue(a.devicePending("t", "WXYZ-1234") is DeviceLookupResult.Found)
  }

  @Test fun `sync-error surfaces HTTP 500`() = runBlocking<Unit> {
    val e = assertFailsWith<SyncHttpException> { sync("sync-error").fetchPage(null) }
    assertEquals(500, e.status)
  }

  @Test fun `the pure router 404s an unknown path`() {
    val backend = FakeBackend(FakeScenarios.byId("busy-family")!!.data)
    assertEquals(404, backend.handle("GET", "/no/such/route", null).status)
    // a served route returns 200
    assertEquals(200, backend.handle("GET", "/auth/whoami", null).status)
  }
}
