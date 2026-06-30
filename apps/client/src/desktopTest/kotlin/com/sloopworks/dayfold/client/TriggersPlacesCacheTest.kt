package com.sloopworks.dayfold.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * ADR 0043 Phase A — Slice 1: the client decodes block `triggers[]` + `Place`, and
 * `places` flow through /sync into the local cache (done-criterion #1). Server stays
 * content-blind — these are already-present wire fields the client previously dropped.
 */
class TriggersPlacesCacheTest {

  private val json = Json { ignoreUnknownKeys = true }

  @Test fun `client decodes a sync envelope carrying block triggers and places`() {
    // The server serves DB-shaped snake_case rows (like target_*/created_by). A block
    // carries top-level `triggers[]` (geo/when), and Changes carries `places[]`.
    val wire = """
      {
        "changes": {
          "blocks": [
            { "id": "b1", "section_id": "s1", "type": "location",
              "triggers": [
                { "geo": { "label": "Safeway", "lat": 37.77, "lng": -122.41, "place_ref": "p1", "radius_m": 180 } },
                { "when": { "at": "2026-07-01T15:00:00Z", "alert_offset": "PT30M" } }
              ] }
          ],
          "places": [
            { "id": "p1", "kind": "store", "label": "Safeway", "lat": 37.77, "lng": -122.41, "radius_m": 180, "version": 1 }
          ]
        },
        "tombstones": [],
        "next_cursor": "c1",
        "has_more": false
      }
    """.trimIndent()

    val resp = json.decodeFromString(SyncResponse.serializer(), wire)

    val block = resp.changes.blocks.single()
    assertEquals(2, block.triggers?.size)
    assertEquals("Safeway", block.triggers!![0].geo?.label)
    assertEquals(180L, block.triggers[0].geo?.radiusM)
    assertEquals("p1", block.triggers[0].geo?.placeRef)
    assertEquals("2026-07-01T15:00:00Z", block.triggers[1].whenTrigger?.at)

    val place = resp.changes.places.single()
    assertEquals("p1", place.id)
    assertEquals("store", place.kind)
    assertEquals("Safeway", place.label)
    assertEquals(37.77, place.lat)
    assertEquals(180L, place.radiusM)
  }

  @Test fun `block triggers round-trip through the SQLDelight cache`() = runBlocking<Unit> {
    val store = ContentStore.create(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
    val triggers = listOf(
      BlockTrigger(geo = TriggerGeo(label = "Safeway", lat = 37.77, lng = -122.41, placeRef = "p1", radiusM = 180)),
      BlockTrigger(whenTrigger = TriggerWhen(at = "2026-07-01T15:00:00Z")),
    )
    store.applyDelta(
      changedCards = emptyList(),
      changedHubs = listOf(Hub("h1", title = "Errand")),
      changedSections = listOf(HubSection("s1", hubId = "h1", title = "Plan")),
      changedBlocks = listOf(HubBlock("b1", sectionId = "s1", type = "location", triggers = triggers)),
      tombstones = emptyList(), nextCursor = "c1", nowIso = "2026-06-30T00:00:00Z",
    )
    val tree = store.hubTreeFlow("h1").first()
    assertNotNull(tree)
    val block = tree!!.blocks.single()
    assertEquals(2, block.triggers?.size)
    assertEquals("Safeway", block.triggers!![0].geo?.label)
    assertEquals("2026-07-01T15:00:00Z", block.triggers[1].whenTrigger?.at)
  }

  @Test fun `places flow through sync to the cache and tombstone removes them`() = runBlocking<Unit> {
    val store = ContentStore.create(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
    store.applyDelta(
      changedCards = emptyList(), changedHubs = emptyList(),
      changedSections = emptyList(), changedBlocks = emptyList(),
      tombstones = emptyList(), nextCursor = "c1", nowIso = "t1",
      changedPlaces = listOf(
        Place("p1", kind = "store", label = "Safeway", lat = 37.77, lng = -122.41, radiusM = 180),
        Place("p2", kind = "school", label = "Lincoln High", lat = 37.78, lng = -122.42),
      ),
    )
    val places = store.activePlacesFlow().first()
    assertEquals(setOf("p1", "p2"), places.map { it.id }.toSet())
    assertEquals(180L, places.first { it.id == "p1" }.radiusM)

    store.applyDelta(
      changedCards = emptyList(), changedHubs = emptyList(),
      changedSections = emptyList(), changedBlocks = emptyList(),
      tombstones = listOf(Tombstone("place", "p1")), nextCursor = "c2", nowIso = "t2",
    )
    val after = store.activePlacesFlow().first()
    assertEquals(listOf("p2"), after.map { it.id })
  }

  @Test fun `wipeForResync drops places (a staleness reset rebuilds them)`() = runBlocking<Unit> {
    val store = ContentStore.create(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
    store.applyDelta(
      changedCards = emptyList(), changedHubs = emptyList(),
      changedSections = emptyList(), changedBlocks = emptyList(),
      tombstones = emptyList(), nextCursor = "c1", nowIso = "t1",
      changedPlaces = listOf(Place("p1", label = "Home", lat = 1.0, lng = 2.0)),
    )
    assertTrue(store.activePlacesFlow().first().isNotEmpty())
    store.wipeForResync()
    assertTrue(store.activePlacesFlow().first().isEmpty())
  }
}
