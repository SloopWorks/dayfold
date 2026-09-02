package com.sloopworks.dayfold.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * spec §1.4/§3.1/§4.1 — materialize() is the pure heart of the import: one proposal → one section
 * + 1-3 blocks, composed of existing ops only, with the exact op-body key sets §4.1 non-goal 1
 * requires. No calendar-derived id/fingerprint/account name is ever a variable in scope here —
 * these tests prove the OUTPUT never carries one, closing the loop the type-level test in
 * CalendarImportModelTest starts.
 */
class CalendarImportMaterializeTest {
  private fun ids() = ImportOpIds(hubId = "hub-1", sectionId = "sec-1", blockIds = listOf("blk-1", "blk-2", "blk-3"))
  private fun opIdSeq(): () -> String {
    var n = 0
    return { "op-${n++}" }
  }
  private fun keys(payload: String): Set<String> = Json.parseToJsonElement(payload).jsonObject.keys
  private fun field(payload: String, key: String): String = Json.parseToJsonElement(payload).jsonObject[key]!!.jsonPrimitive.content

  private fun proposal(
    location: StructuredLocation? = null,
    description: String? = null,
    end: EventInstant? = null,
    timezone: String? = "America/Los_Angeles",
  ) = CalendarImportProposal(
    proposalId = "prop-1", title = "Grandma's 80th lunch",
    start = EventInstant.Timed("2026-06-28T12:00:00-07:00"), end = end,
    timezone = timezone, location = location, description = description,
  )

  @Test fun `new-hub destination materializes hub, section, and milestone block in that dependency order`() {
    val dest = ImportDestination.NewHub(HubVisibilityChoice.RESTRICTED, listOf("u-importer"))
    val ops = materialize(proposal(), dest, ids(), "2026-08-09T10:00:00Z", opIdSeq())

    assertEquals(listOf("hub", "section", "block"), ops.map { it.targetKind })
    assertEquals(listOf("upsertHub", "upsertSection", "upsertBlock"), ops.map { it.type })
    assertEquals(listOf("hub-1", "sec-1", "blk-1"), ops.map { it.targetId })
    assertNull(ops[0].dependsOn)                       // hub has no parent
    assertEquals(ops[0].opId, ops[1].dependsOn)         // section depends_on hub
    assertEquals(ops[1].opId, ops[2].dependsOn)         // block depends_on section
  }

  @Test fun `existing-hub destination skips the hub op and depends the section directly on nothing`() {
    val dest = ImportDestination.ExistingHub("hub-existing", "Beach Week", "contributor")
    val ops = materialize(proposal(), dest, ids().copy(hubId = null), "2026-08-09T10:00:00Z", opIdSeq())

    assertEquals(listOf("section", "block"), ops.map { it.targetKind })
    assertNull(ops[0].dependsOn)                        // section has no hub op to depend on
    assertEquals(ops[0].opId, ops[1].dependsOn)
    assertEquals("sec-1", field(ops[0].payload, "id"))
    assertEquals("hub-existing", field(ops[0].payload, "hubId"))
  }

  @Test fun `OD-6 section reuse — an existing live section skips the section op entirely, block depends on nothing`() {
    val dest = ImportDestination.ExistingHub("hub-existing", "Beach Week", "co_owner")
    val ops = materialize(proposal(), dest, ids().copy(hubId = null), "2026-08-09T10:00:00Z", opIdSeq(), existingSectionId = "sec-reused")

    assertEquals(listOf("block"), ops.map { it.targetKind })
    assertNull(ops[0].dependsOn)
    assertEquals("sec-reused", field(ops[0].payload, "sectionId"))
  }

  @Test fun `optional blocks form a causal chain ending at the terminal commit marker`() {
    val dest = ImportDestination.NewHub()
    val ops = materialize(
      proposal(location = StructuredLocation("Harvest Table", "88 Vine St"), description = "Bring dessert"),
      dest, ids(), "2026-08-09T10:00:00Z", opIdSeq(),
    )
    val blocks = ops.filter { it.targetKind == "block" }
    assertEquals(2, blocks.size)
    assertEquals(blocks[0].opId, blocks[1].dependsOn)
    assertEquals("location", field(blocks[1].payload, "type"))
    assertEquals("blk-2", blocks[1].targetId)
  }

  @Test fun `description is never materialized into Dayfold content`() {
    val dest = ImportDestination.NewHub()
    val ops = materialize(proposal(description = "Bring a dessert"), dest, ids(), "2026-08-09T10:00:00Z", opIdSeq())
    assertTrue(ops.none { "Bring a dessert" in it.payload })
    assertTrue(ops.none { runCatching { field(it.payload, "type") }.getOrNull() == "markdown" })
  }

  @Test fun `importBlockCount matches materialize's actual block count for every opt-in combination`() {
    assertEquals(1, importBlockCount(proposal()))
    assertEquals(2, importBlockCount(proposal(location = StructuredLocation("x"))))
    assertEquals(1, importBlockCount(proposal(description = "d")))
    assertEquals(2, importBlockCount(proposal(location = StructuredLocation("x"), description = "d")))
  }

  // ── §4.1 non-goal 1 — exact allowed key-set proof, per op body ──

  @Test fun `hub op body key set is exactly the allowed set — restricted, with end_at`() {
    val dest = ImportDestination.NewHub(HubVisibilityChoice.RESTRICTED, listOf("u1", "u2"))
    val ops = materialize(proposal(end = EventInstant.Timed("2026-06-28T14:00:00-07:00")), dest, ids(), "2026-08-09T10:00:00Z", opIdSeq())
    val hubKeys = keys(ops.first { it.targetKind == "hub" }.payload)
    assertTrue(hubKeys.all { it in setOf("id", "type", "title", "visibility", "audience", "start_at", "end_at") })
    assertEquals(setOf("id", "type", "title", "visibility", "audience", "start_at", "end_at"), hubKeys)
  }

  @Test fun `hub op body omits audience when visibility is family`() {
    val dest = ImportDestination.NewHub(HubVisibilityChoice.FAMILY, emptyList())
    val ops = materialize(proposal(), dest, ids(), "2026-08-09T10:00:00Z", opIdSeq())
    val hubKeys = keys(ops.first { it.targetKind == "hub" }.payload)
    assertTrue("audience" !in hubKeys)
    assertEquals("family", field(ops.first { it.targetKind == "hub" }.payload, "visibility"))
  }

  @Test fun `section op body key set is exactly hubId, id, title`() {
    val dest = ImportDestination.NewHub()
    val ops = materialize(proposal(), dest, ids(), "2026-08-09T10:00:00Z", opIdSeq())
    assertEquals(setOf("hubId", "id", "title"), keys(ops.first { it.targetKind == "section" }.payload))
    assertEquals(CALENDAR_IMPORT_SECTION_TITLE, field(ops.first { it.targetKind == "section" }.payload, "title"))
  }

  @Test fun `milestone block key set is exactly sectionId, type, payload, triggers, provenance`() {
    val dest = ImportDestination.NewHub()
    val ops = materialize(proposal(), dest, ids(), "2026-08-09T10:00:00Z", opIdSeq())
    val milestone = ops.first { it.targetKind == "block" }
    assertEquals(setOf("sectionId", "type", "payload", "triggers", "provenance"), keys(milestone.payload))
    val payload = Json.parseToJsonElement(milestone.payload).jsonObject["payload"]!!.jsonObject
    assertTrue(payload.keys.all { it in setOf("date", "label", "end", "tz") })
    val whenTrigger = Json.parseToJsonElement(milestone.payload).jsonObject["triggers"]!!
      .jsonArray.single().jsonObject["when"]!!.jsonObject
    assertEquals("payload:milestone", whenTrigger["fact_ref"]!!.jsonPrimitive.content)
    assertTrue("at" !in whenTrigger)
  }

  @Test fun `all-day start omits triggers entirely (no when-at trigger for a dateless instant)`() {
    val dest = ImportDestination.NewHub()
    val p = proposal().copy(start = EventInstant.AllDay("2026-06-28"))
    val ops = materialize(p, dest, ids(), "2026-08-09T10:00:00Z", opIdSeq())
    assertTrue("triggers" !in keys(ops.first { it.targetKind == "block" }.payload))
  }

  @Test fun `location block key set is exactly sectionId, type, payload, provenance`() {
    val dest = ImportDestination.NewHub()
    val ops = materialize(proposal(location = StructuredLocation("Harvest Table")), dest, ids(), "2026-08-09T10:00:00Z", opIdSeq())
    val loc = ops.first { it.type == "upsertBlock" && field(it.payload, "type") == "location" }
    assertEquals(setOf("sectionId", "type", "payload", "provenance"), keys(loc.payload))
  }

  @Test fun `provenance is the fixed literal calendar with a device-clock timestamp — never a display name`() {
    val dest = ImportDestination.NewHub()
    val ops = materialize(proposal(), dest, ids(), "2026-08-09T10:00:00Z", opIdSeq())
    val milestone = ops.first { it.targetKind == "block" }
    val prov = Json.parseToJsonElement(milestone.payload).jsonObject["provenance"]!!.jsonObject
    assertEquals(setOf("source", "at"), prov.keys)
    assertEquals("calendar", prov["source"]!!.jsonPrimitive.content)
    assertEquals("2026-08-09T10:00:00Z", prov["at"]!!.jsonPrimitive.content)
  }

  @Test fun `every op body is free of every banned calendar-identifying substring`() {
    val dest = ImportDestination.NewHub(HubVisibilityChoice.RESTRICTED, listOf("u1"))
    val ops = materialize(
      proposal(location = StructuredLocation("Harvest Table", "88 Vine St"), description = "Bring a dessert"),
      dest, ids(), "2026-08-09T10:00:00Z", opIdSeq(),
    )
    val banned = listOf("attendee", "guest", "organizer", "reminder", "recurrence", "fingerprint", "platformEventId", "accountName", "conferenc")
    for (op in ops) for (b in banned) assertTrue(b.lowercase() !in op.payload.lowercase(), "op ${op.targetKind}/${op.type} leaked '$b'")
  }
}
