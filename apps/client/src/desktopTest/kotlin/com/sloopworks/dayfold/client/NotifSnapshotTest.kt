package com.sloopworks.dayfold.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone

/**
 * ADR 0044 Phase B · S3 — the synchronous snapshot getters + end-to-end background pass over the REAL
 * ContentStore (in-memory SQLite). The headless worker reads notifSnapshot() from the single shared
 * connection (no Store, no 2nd connection) and runs planBackgroundNotifications — proving the sync
 * getters assemble the same content nowFeed needs, with no engine fork.
 */
class NotifSnapshotTest {
  private val zone = TimeZone.UTC
  private val noon = "2026-06-30T12:00:00Z"
  private fun store() = ContentStore.create(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))

  private fun seed(s: ContentStore) {
    s.applyDelta(
      changedCards = listOf(Card(id = "c1", title = "Soccer at 4pm — pack jackets", bodyMd = "Rain at kickoff", notBefore = noon, provenance = Provenance("claude"))),
      changedHubs = listOf(Hub(id = "h1", title = "Party")),
      changedSections = listOf(HubSection(id = "s1", hubId = "h1", title = "Shopping", ord = 0)),
      changedBlocks = listOf(HubBlock(id = "b1", sectionId = "s1", type = "checklist", ord = 0)),
      changedPlaces = listOf(Place(id = "store", kind = "store", label = "Lincoln Market", lat = 0.0, lng = 0.0, radiusM = 200)),
      tombstones = emptyList(),
      nextCursor = "cur1",
      nowIso = noon,
    )
  }

  @Test fun `notifSnapshot assembles all live content synchronously`() {
    val s = store()
    seed(s)
    s.setNotifConfig(NotifConfig(enabled = true))
    s.recordShown("hub:h1", noon)
    s.logNotification("hub:zzz", noon)

    val snap = s.notifSnapshot()
    assertEquals(listOf("c1"), snap.cards.map { it.id })
    assertEquals(listOf("h1"), snap.hubs.map { it.id })
    assertEquals(listOf("s1"), snap.sections.map { it.id })
    assertEquals(listOf("b1"), snap.blocks.map { it.id })
    assertEquals(listOf("store"), snap.places.map { it.id })
    assertTrue(snap.config.enabled)
    assertEquals("hub:h1", snap.surfacing.keys.single())
    assertEquals(listOf("hub:zzz"), snap.log.map { it.subjectKey })
  }

  @Test fun `the background pass runs end-to-end over the real store snapshot`() {
    val s = store()
    seed(s)
    s.setNotifConfig(NotifConfig(enabled = true))

    val plan = planBackgroundNotifications(s.notifSnapshot(), noon, null, zone)
    assertEquals(listOf("card:c1"), plan.toPost.map { it.subjectKey })
  }

  @Test fun `disabled config snapshot plans nothing`() {
    val s = store()
    seed(s)   // config never set → default-off
    val plan = planBackgroundNotifications(s.notifSnapshot(), noon, null, zone)
    assertTrue(plan.toPost.isEmpty())
  }
}
