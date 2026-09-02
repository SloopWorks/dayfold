package com.sloopworks.dayfold.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.sloopworks.dayfold.client.db.ContentDb
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class TemporalFactsTest {
  private val json = Json { ignoreUnknownKeys = true }
  private val id1 = "01K45ABCDEF0123456789GHJKM"
  private val id2 = "01K45ABCDEF0123456789GHJKN"

  private fun temporal(vararg occurrences: String): JsonObject =
    json.parseToJsonElement("""{"occurrences":[${occurrences.joinToString()}],"futureFacet":"kept"}""").jsonObject

  private fun occurrence(
    id: String,
    role: String = "event",
    status: String = "confirmed",
    start: String,
    end: String? = null,
    zone: String? = null,
    label: String = "The big night",
  ): String = buildString {
    append("""{"id":"$id","role":"$role","label":"$label","start":"$start"""")
    end?.let { append(",\"end\":\"$it\"") }
    zone?.let { append(",\"zone\":\"$it\"") }
    append(",\"status\":\"$status\"}")
  }

  @Test fun `shared fixture corpus stays aligned with mobile normalization`() {
    val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
      .map { File(it, "specs/domain-model/examples/temporal-v1") }
      .first { it.isDirectory }
    root.listFiles { file -> file.extension == "json" }!!.sortedBy { it.name }.forEach { file ->
      val fixture = json.parseToJsonElement(file.readText()).jsonObject
      val triggers = fixture["triggers"]?.let {
        json.decodeFromJsonElement(ListSerializer(BlockTrigger.serializer()), it)
      }
      val block = HubBlock("fixture", "section", "markdown", triggers = triggers,
        temporal = fixture["temporal"]?.jsonObject)
      val facts = temporalFacts(block, TimeZone.UTC)
      if (file.name.startsWith("valid-")) {
        assertTrue(facts.isNotEmpty(), file.name)
        assertTrue(facts.all { it.capabilities.calendar && it.capabilities.timeline }, file.name)
      } else if (!triggers.isNullOrEmpty()) {
        assertTrue(triggers.all { resolveWhenTrigger(facts, it.whenTrigger!!, TimeZone.UTC) == null }, file.name)
      } else {
        assertTrue(facts.isEmpty() || facts.all { it.capabilities == TemporalCapabilities() }, file.name)
      }
    }
  }

  @Test fun `date-only facts remain civil exclusive intervals`() {
    val card = Card("c1", title = "Night", temporal = temporal(
      occurrence(id1, start = "2026-08-28", end = "2026-08-30"),
    ))
    val extent = assertIs<TemporalExtent.AllDay>(temporalFacts(card).single().extent)
    assertEquals(LocalDate(2026, 8, 28), extent.start)
    assertEquals(LocalDate(2026, 8, 30), extent.endExclusive)
  }

  @Test fun `unknown semantic tokens survive but cannot activate a consumer`() {
    val card = Card("c1", title = "Night", temporal = temporal(
      occurrence(id1, role = "future-role", status = "future-status", start = "2026-08-28"),
    ))
    val fact = temporalFacts(card).single()
    assertEquals(TemporalRole.UNKNOWN, fact.role)
    assertEquals(TemporalStatus.UNKNOWN, fact.status)
    assertEquals(TemporalCapabilities(), fact.capabilities)
  }

  @Test fun `malformed identities ranges and unknown offsets remain noneligible`() {
    val duplicate = temporal(
      occurrence(id1, role = "window", start = "2026-08-29T04:00:00Z", zone = "UTC"),
      occurrence(id1, start = "2026-08-29T05:00:00Z", zone = "UTC"),
    )
    assertTrue(temporalFacts(Card("c1", title = "Duplicate", temporal = duplicate)).all {
      it.capabilities == TemporalCapabilities()
    })
    val unknownOffset = temporal(
      occurrence(id2, start = "2026-08-29T04:00:00-00:00", zone = "UTC"),
    )
    assertTrue(temporalFacts(Card("c2", title = "Offset", temporal = unknownOffset)).isEmpty())
  }

  @Test fun `multiple confirmed occurrences keep distinct identities even at one instant`() {
    val facet = temporal(
      occurrence(id1, start = "2026-08-29T04:00:00Z", zone = "UTC", label = "Warm-up"),
      occurrence(id2, start = "2026-08-29T04:00:00Z", zone = "UTC", label = "Show"),
    )
    val facts = temporalFacts(HubBlock("b1", "s1", "text", temporal = facet)).calendarEligible()
    assertEquals(2, facts.size)
    assertNotEquals(facts[0].factRef, facts[1].factRef)
  }

  @Test fun `facts alone never create Now behavior while a valid ref does`() {
    val facet = temporal(occurrence(id1, start = "2026-08-28T21:00:00Z", zone = "UTC"))
    val section = HubSection("s1", hubId = "h1")
    val passive = HubBlock("b1", "s1", "text", temporal = facet)
    assertTrue(deriveNow(emptyList(), listOf(section), listOf(passive), emptyList(),
      "2026-08-28T20:30:00Z", null, TimeZone.UTC).isEmpty())

    val active = passive.copy(triggers = listOf(BlockTrigger(whenTrigger = TriggerWhen(
      factRef = "temporal:$id1", alertOffset = "-PT30M",
    ))))
    val now = deriveNow(emptyList(), listOf(section), listOf(active), emptyList(),
      "2026-08-28T20:30:00Z", null, TimeZone.UTC)
    assertEquals("2026-08-28T20:30:00Z", now.single().triggerAtIso)
    assertEquals(localFactKey(EntityRef("block:b1"), FactRef("temporal:$id1")), now.single().localFactKey)
  }

  @Test fun `dangling all-day and malformed-offset refs are ignored`() {
    val allDay = temporal(occurrence(id1, start = "2026-08-28"))
    val facts = temporalFacts(HubBlock("b1", "s1", "text", temporal = allDay))
    assertNull(resolveWhenTrigger(facts, TriggerWhen(factRef = "temporal:$id1"), TimeZone.UTC))
    assertNull(resolveWhenTrigger(facts, TriggerWhen(factRef = "temporal:$id2"), TimeZone.UTC))

    val timed = temporalFacts(HubBlock("b1", "s1", "text", temporal = temporal(
      occurrence(id1, start = "2026-08-28T21:00:00Z", zone = "UTC"),
    )))
    assertNull(resolveWhenTrigger(timed, TriggerWhen(factRef = "temporal:$id1", alertOffset = "tomorrow"), TimeZone.UTC))
  }

  @Test fun `local fact key is reversible and collision safe`() {
    val pair = EntityRef("block:a:b") to FactRef("temporal:$id1")
    assertEquals(pair, parseLocalFactKey(localFactKey(pair.first, pair.second)))
    assertNotEquals(
      localFactKey(EntityRef("block:a"), FactRef("b:temporal:$id1")),
      localFactKey(EntityRef("block:a:b"), FactRef("temporal:$id1")),
    )
  }

  @Test fun `unknown trigger members survive typed decode and re-encode`() {
    val wire = """[{"futureOuter":{"x":1},"when":{"fact_ref":"temporal:$id1","futureWhen":"kept"}}]"""
    val serializer = ListSerializer(BlockTrigger.serializer())
    val decoded = json.decodeFromString(serializer, wire)
    val encoded = json.parseToJsonElement(json.encodeToString(serializer, decoded)).toString()
    assertTrue("futureOuter" in encoded)
    assertTrue("futureWhen" in encoded)
    assertEquals("temporal:$id1", decoded.single().whenTrigger?.factRef)
  }

  @Test fun `mixed or repeated fact reference behaviors fail closed`() {
    val facet = temporal(occurrence(id1, start = "2026-08-28T21:00:00Z", zone = "UTC"))
    val facts = temporalFacts(Card("c1", title = "Show", temporal = facet))
    val mixed = json.decodeFromString(
      TriggerWhen.serializer(),
      """{"fact_ref":"temporal:$id1","futureBehavior":true}""",
    )
    assertNull(resolveWhenTrigger(facts, mixed, TimeZone.UTC))
    val repeated = listOf(
      BlockTrigger(whenTrigger = TriggerWhen(factRef = "temporal:$id1")),
      BlockTrigger(whenTrigger = TriggerWhen(factRef = "temporal:$id1")),
    )
    assertNull(selectWhenTrigger(repeated, "2026-08-28T20:00:00Z", TimeZone.UTC, facts = facts))
  }

  @Test fun `cache round trip preserves raw temporal unknowns Card ACL and Block facts`() {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    ContentDb.Schema.create(driver)
    val store = ContentStore(driver)
    val facet = temporal(occurrence(id1, start = "2026-08-28T21:00:00Z", zone = "UTC"))
    store.applyDelta(
      changedCards = listOf(Card("c1", title = "Night", temporal = facet, version = 7,
        visibility = "restricted", audience = listOf("u1"))),
      changedHubs = emptyList(),
      changedSections = listOf(HubSection("s1", hubId = "h1")),
      changedBlocks = listOf(HubBlock("b1", "s1", "text", temporal = facet)),
      tombstones = emptyList(), nextCursor = "c1", nowIso = "2026-08-01T00:00:00Z",
    )
    val card = store.activeCards().single()
    assertEquals("kept", card.temporal?.get("futureFacet")?.jsonPrimitive?.content)
    assertEquals(7, card.version)
    assertEquals("restricted", card.visibility)
    assertEquals(listOf("u1"), card.audience)
    assertEquals("kept", store.allBlocks().single().temporal?.get("futureFacet")?.jsonPrimitive?.content)
    driver.close()
  }

  @Test fun `calendar emits one stable local identity per eligible fact`() {
    val facet = temporal(
      occurrence(id1, start = "2026-08-29T01:30:00Z", zone = "America/Los_Angeles", label = "Call"),
      occurrence(id2, start = "2026-08-29T04:00:00Z", zone = "America/Los_Angeles", label = "Show"),
    )
    val candidates = deriveEventCandidates(
      emptyList(), listOf(HubSection("s1", hubId = "h1")),
      listOf(HubBlock("b1", "s1", "text", temporal = facet)), emptyList(), TimeZone.UTC,
    )
    assertEquals(2, candidates.size)
    assertEquals(setOf("hub:h1/section:s1/block:b1"), candidates.map { it.subjectRef }.toSet())
    assertEquals(2, candidates.map { it.subjectKey }.toSet().size)
  }
}
