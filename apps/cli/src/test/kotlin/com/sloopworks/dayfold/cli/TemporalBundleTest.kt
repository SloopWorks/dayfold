package com.sloopworks.dayfold.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class TemporalBundleTest {
  private val occurrenceId = "01K45ABCDEF0123456789GHJKM"
  private val content = """{
    "kind":"info","title":"Big Night","provenance":{"source":"cli","at":"2026-09-01T12:00:00Z"},
    "temporal":{"occurrences":[{"id":"$occurrenceId","role":"event","label":"Show","start":"2026-08-28T21:00:00-07:00","zone":"America/Los_Angeles","status":"confirmed"}]}
  }"""
  private val bundleRaw = """{
    "schema":"dayfold.temporal-bundle.v1",
    "resources":[{
      "kind":"card","id":"card-1","base_version":2,
      "acl":{"visibility":"family","audience":[]},
      "content":$content,
      "ledger":[{
        "claim_id":"claim-1","source_ref":"source:1","classification":"event",
        "normalized":"2026-08-28T21:00:00-07:00","zone":"America/Los_Angeles",
        "certainty":"confirmed","carrier_path":"/temporal/occurrences/0/start","behavior":false
      }]
    }]
  }"""

  private fun cardRow(content: JsonObject, version: Long): JsonObject = JsonObject(content + mapOf(
    "id" to JsonPrimitive("card-1"),
    "version" to JsonPrimitive(version),
    "visibility" to JsonPrimitive("family"),
    "audience" to JsonArray(emptyList()),
  ))

  @Test fun `bundle requires exact ledger path value correspondence`() {
    val (bundle, decode) = decodeTemporalBundle(bundleRaw)
    assertEquals(emptyList(), decode)
    assertEquals(emptyList(), validateTemporalBundle(bundle!!))
    val bad = bundle.copy(resources = bundle.resources.map { resource ->
      resource.copy(ledger = resource.ledger.map { it.copy(normalized = kotlinx.serialization.json.JsonPrimitive("2026-08-28T22:00:00-07:00")) })
    })
    assertTrue(validateTemporalBundle(bad).any { it.code == "ledger.mapping-or-value-mismatch" })
  }

  @Test fun `relative claims require an offset-consistent source instant and IANA zone`() {
    val base = decodeTemporalBundle(bundleRaw).first!!
    val resource = base.resources.single()
    val missing = resource.copy(ledger = resource.ledger.map { it.copy(relative = true) })
    assertTrue(validateTemporalBundle(base.copy(resources = listOf(missing))).any {
      it.code == "ledger.relative-source-base-required"
    })
    val resolved = resource.copy(ledger = resource.ledger.map { it.copy(
      relative = true,
      sourceBaseInstant = "2026-08-27T12:00:00-07:00",
      sourceBaseZone = "America/Los_Angeles",
    ) })
    assertEquals(emptyList(), validateTemporalBundle(base.copy(resources = listOf(resolved))))
    val impossible = resolved.copy(ledger = resolved.ledger.map {
      it.copy(sourceBaseInstant = "2026-08-27T12:00:00+05:00")
    })
    assertTrue(validateTemporalBundle(base.copy(resources = listOf(impossible))).any {
      it.code == "ledger.invalid-source-base"
    })
  }

  @Test fun `one range claim maps start and exclusive end while every typed carrier requires coverage`() {
    val base = decodeTemporalBundle(bundleRaw).first!!
    val rangedContent = Json.parseToJsonElement(content.replace(
      "\"zone\":\"America/Los_Angeles\"",
      "\"end\":\"2026-08-28T23:00:00-07:00\",\"zone\":\"America/Los_Angeles\"",
    )).jsonObject
    val range = base.copy(resources = listOf(base.resources.single().copy(
      content = rangedContent,
      ledger = listOf(base.resources.single().ledger.single().copy(
        normalized = JsonObject(mapOf(
          "start" to JsonPrimitive("2026-08-28T21:00:00-07:00"),
          "end" to JsonPrimitive("2026-08-28T23:00:00-07:00"),
        )),
        carrierPath = "/temporal/occurrences/0",
      )),
    )))
    assertEquals(emptyList(), validateTemporalBundle(range))

    val splitRange = range.copy(resources = listOf(range.resources.single().copy(ledger = listOf(
      resourceLedger(base).copy(carrierPath = "/temporal/occurrences/0/start"),
      resourceLedger(base).copy(
        claimId = "claim-end",
        normalized = JsonPrimitive("2026-08-28T23:00:00-07:00"),
        carrierPath = "/temporal/occurrences/0/end",
      ),
    ))))
    assertTrue(validateTemporalBundle(splitRange).any { it.code == "ledger.range-must-be-one-claim" })

    val unmappedChecklist = base.copy(resources = listOf(base.resources.single().copy(
      content = Json.parseToJsonElement("""{
        "type":"checklist","payload":{"items":[{"id":"01K45ABCDEF0123456789GHJKM","text":"Pack","due":"2026-08-28T18:00:00Z"}]},
        "temporal":null
      }""").jsonObject,
      ledger = emptyList(),
    )))
    assertTrue(validateTemporalBundle(unmappedChecklist).any { it.code == "ledger.unmapped-temporal-carrier" })
  }

  private fun resourceLedger(bundle: TemporalBundle): TemporalClaim = bundle.resources.single().ledger.single()

  @Test fun `fact behavior requires a separate ledger approval for the exact selected fact`() {
    val base = decodeTemporalBundle(bundleRaw).first!!
    val resource = base.resources.single()
    val triggered = resource.copy(
      content = JsonObject(resource.content + ("triggers" to Json.parseToJsonElement(
        """[{"when":{"fact_ref":"temporal:$occurrenceId","alert_offset":"-PT30M"}}]""",
      ))),
      ledger = resource.ledger.map { it.copy(behavior = true) },
    )
    assertEquals(emptyList(), validateTemporalBundle(base.copy(resources = listOf(triggered))))
    val unapproved = triggered.copy(ledger = triggered.ledger.map { it.copy(behavior = false) })
    assertTrue(validateTemporalBundle(base.copy(resources = listOf(unapproved))).any {
      it.code == "ledger.behavior-approval-required"
    })
  }

  @Test fun `coverage compares all prose claim lines with all structured facts instead of stopping at the first carrier`() {
    val oneFact = """{"blocks":[{
      "id":"b1","type":"markdown","body_md":"Warm-up at 6:30 pm\nShow at 9pm",
      "temporal":{"occurrences":[{"id":"01K45ABCDEF0123456789GHJKM","role":"event","label":"Warm-up","start":"2026-08-28T18:30:00-07:00","zone":"America/Los_Angeles","status":"confirmed"}]}
    }]}"""
    val partial = auditContent(oneFact)
    assertEquals(1, partial.exitCode)
    assertTrue(partial.stderr.contains("coverage.possible-unstructured-temporal-claim"))
    assertTrue("b1" !in partial.stderr, partial.stderr)
    assertTrue("block:b1" in auditContent(oneFact, includeResourceIds = true).stderr)

    val twoFacts = """{"blocks":[{
      "id":"b1","type":"markdown","body_md":"Warm-up at 6:30 pm\nShow at 9pm",
      "temporal":{"occurrences":[
        {"id":"01K45ABCDEF0123456789GHJKM","role":"event","label":"Warm-up","start":"2026-08-28T18:30:00-07:00","zone":"America/Los_Angeles","status":"confirmed"},
        {"id":"01K45ABCDEF0123456789GHJKN","role":"event","label":"Show","start":"2026-08-28T21:00:00-07:00","zone":"America/Los_Angeles","status":"confirmed"}
      ]}
    }]}"""
    assertEquals(0, auditContent(twoFacts).exitCode, auditContent(twoFacts).stderr)
  }

  @Test fun `audit ignores lifecycle fields so stored expires_at text does not count as a prose claim`() {
    val covered = """{"cards":[{
      "id":"c1","type":"link","body_md":"Registration closes Sep 12 at 1pm",
      "payload":{"link":{"url":"https://example.org","closesAt":"2026-09-12T17:00:00Z"}},
      "expires_at":"2026-09-30 00:00:00+00","not_before":"2026-09-01 00:00:00+00"
    }]}"""
    val result = auditContent(covered)
    assertEquals(0, result.exitCode, result.stderr)
  }

  @Test fun `dry run performs current version and ACL checks but does not write`() {
    val bundle = decodeTemporalBundle(bundleRaw).first!!
    var writes = 0
    val current = """{"id":"card-1","version":2,"visibility":"family","audience":null,$content}"""
      .replace("{\"id\":\"card-1\",\"version\":2,\"visibility\":\"family\",\"audience\":null,{", "{\"id\":\"card-1\",\"version\":2,\"visibility\":\"family\",\"audience\":null,")
    val result = applyTemporalBundle(bundle, "family", ContentNetwork(
      ContentGet { 200 to "[$current]" },
      ContentPut { _, _, _ -> writes++; 500 to "" },
    ), dryRun = true)
    assertEquals(0, result.exitCode, result.stderr)
    assertEquals(0, writes)
  }

  @Test fun `apply skips an exact desired resource even after its version advanced`() {
    val bundle = decodeTemporalBundle(bundleRaw).first!!
    val current = cardRow(bundle.resources.single().content, version = 3)
    var writes = 0
    val result = applyTemporalBundle(bundle, "family", ContentNetwork(
      ContentGet { 200 to "[$current]" },
      ContentPut { _, _, _ -> writes++; error("exact desired resource was rewritten") },
    ), dryRun = false)
    assertEquals(0, result.exitCode, result.stderr)
    assertEquals(0, writes)
    assertTrue(result.stdout.contains("skipped=1"), result.stdout)
    assertTrue(result.stdout.contains("round_trip=verified"), result.stdout)
  }

  @Test fun `round trip compares occurrences by identity rather than array order`() {
    val base = decodeTemporalBundle(bundleRaw).first!!
    val resource = base.resources.single()
    val secondId = "01K45ABCDEF0123456789GHJKN"
    val secondOccurrence = Json.parseToJsonElement(
      """{"id":"$secondId","role":"event","label":"Rehearsal","start":"2026-08-28T18:30:00-07:00","zone":"America/Los_Angeles","status":"confirmed"}""",
    ).jsonObject
    val temporal = resource.content["temporal"]!!.jsonObject
    val expectedOccurrences = temporal["occurrences"] as JsonArray
    val desiredContent = JsonObject(resource.content + ("temporal" to JsonObject(
      temporal + ("occurrences" to JsonArray(expectedOccurrences + secondOccurrence)),
    )))
    val desired = resource.copy(
      content = desiredContent,
      ledger = resource.ledger + resource.ledger.single().copy(
        claimId = "claim-2",
        normalized = JsonPrimitive("2026-08-28T18:30:00-07:00"),
        carrierPath = "/temporal/occurrences/1/start",
      ),
    )
    val bundle = base.copy(resources = listOf(desired))
    val oldContent = JsonObject(desiredContent + ("title" to JsonPrimitive("Old Night")))
    val reversedContent = JsonObject(desiredContent + ("temporal" to JsonObject(
      temporal + ("occurrences" to JsonArray(listOf(secondOccurrence, expectedOccurrences.single()))),
    )))
    val response = cardRow(reversedContent, version = 3)
    var reads = 0
    val result = applyTemporalBundle(bundle, "family", ContentNetwork(
      ContentGet {
        reads++
        200 to if (reads == 1) "[${cardRow(oldContent, version = 2)}]" else "[$response]"
      },
      ContentPut { _, _, _ -> 200 to response.toString() },
    ), dryRun = false)
    assertEquals(0, result.exitCode, result.stderr)
    assertTrue(result.stdout.contains("writes=1"), result.stdout)
  }

  @Test fun `dry run permits a new Hub while deferring ACL round trip until creation`() {
    val bundle = TemporalBundle(
      schema = TEMPORAL_BUNDLE_SCHEMA,
      resources = listOf(TemporalBundleResource(
        kind = "hub", id = "new-hub", acl = BundleAcl("restricted", listOf("adult-1")),
        content = Json.parseToJsonElement("""{"type":"party-event","title":"Band","status":"planning"}""").jsonObject,
        ledger = emptyList(),
      )),
    )
    var reads = 0
    val result = applyTemporalBundle(bundle, "family", ContentNetwork(
      ContentGet { reads++; 404 to "" },
      ContentPut { _, _, _ -> error("dry-run wrote") },
    ), dryRun = true)
    assertEquals(0, result.exitCode, result.stderr)
    assertEquals(1, reads)
  }

  @Test fun `dry run permits a new Hub tree when child ACLs match the bundled parent`() {
    val acl = BundleAcl("restricted", listOf("adult-1"))
    val bundle = TemporalBundle(
      schema = TEMPORAL_BUNDLE_SCHEMA,
      resources = listOf(
        TemporalBundleResource(
          kind = "block", id = "new-block", hubId = "new-hub", acl = acl,
          content = Json.parseToJsonElement("""{"type":"markdown","body_md":"Rehearsal notes","temporal":null}""").jsonObject,
          ledger = emptyList(),
        ),
        TemporalBundleResource(
          kind = "section", id = "new-section", hubId = "new-hub", acl = acl,
          content = Json.parseToJsonElement("""{"title":"Schedule"}""").jsonObject,
          ledger = emptyList(),
        ),
        TemporalBundleResource(
          kind = "hub", id = "new-hub", acl = acl,
          content = Json.parseToJsonElement("""{"type":"party-event","title":"Band","status":"planning"}""").jsonObject,
          ledger = emptyList(),
        ),
      ),
    )
    var reads = 0
    val result = applyTemporalBundle(bundle, "family", ContentNetwork(
      ContentGet { reads++; 404 to "" },
      ContentPut { _, _, _ -> error("dry-run wrote") },
    ), dryRun = true)
    assertEquals(0, result.exitCode, result.stderr)
    assertEquals(3, reads)
  }

  @Test fun `apply fails if a 200 response drops temporal`() {
    val bundle = decodeTemporalBundle(bundleRaw).first!!
    val current = """{"id":"card-1","version":2,"visibility":"family","audience":null,"kind":"info","title":"Old Big Night","provenance":{"source":"cli","at":"2026-09-01T12:00:00Z"},"temporal":{"occurrences":[{"id":"$occurrenceId","role":"event","label":"Show","start":"2026-08-28T21:00:00-07:00","zone":"America/Los_Angeles","status":"confirmed"}]}}"""
    val dropped = """{"id":"card-1","version":3,"visibility":"family","audience":null,"kind":"info","title":"Big Night","provenance":{"source":"cli","at":"2026-09-01T12:00:00Z"}}"""
    var reads = 0
    val result = applyTemporalBundle(bundle, "family", ContentNetwork(
      ContentGet { reads++; 200 to "[$current]" },
      ContentPut { _, _, headers ->
        assertEquals("temporal-v1", headers["x-dayfold-content-capability"])
        assertEquals("2", headers["if-match"])
        200 to dropped
      },
    ), dryRun = false)
    assertEquals(1, result.exitCode)
    assertTrue(result.stderr.contains("response-field-mismatch"), result.stderr)
  }

  @Test fun `round trip compares stored timestamptz text and typed carriers as instants`() {
    assertEquals(parseStoredInstant("2026-09-30T00:00:00+00:00"), parseStoredInstant("2026-09-30 00:00:00+00"))
    assertEquals(parseStoredInstant("2026-08-27T14:34:41.121209Z"), parseStoredInstant("2026-08-27 14:34:41.121209+00"))
    assertEquals(parseStoredInstant("2026-09-12T17:00:00Z"), parseStoredInstant("2026-09-12 13:00:00-04"))
    assertTrue(parseStoredInstant("Big Night") == null)
    val base = decodeTemporalBundle(bundleRaw).first!!
    val resource = base.resources.single()
    val desired = resource.copy(content = JsonObject(resource.content + mapOf(
      "expires_at" to JsonPrimitive("2026-09-30T00:00:00+00:00"),
      "type" to JsonPrimitive("link"),
      "payload" to Json.parseToJsonElement("""{"link":{"url":"https://example.org","closesAt":"2026-09-12T17:00:00Z"}}"""),
    )), ledger = resource.ledger + resource.ledger.single().copy(
      claimId = "claim-closes", classification = "deadline",
      normalized = JsonPrimitive("2026-09-12T17:00:00Z"), zone = "UTC", carrierPath = "/payload/link/closesAt",
    ))
    val bundle = base.copy(resources = listOf(desired))
    fun row(version: Long, expires: String, closes: String, extra: Map<String, JsonElement> = emptyMap()) = JsonObject(desired.content + mapOf(
      "id" to JsonPrimitive("card-1"), "version" to JsonPrimitive(version),
      "visibility" to JsonPrimitive("family"), "audience" to JsonArray(emptyList()),
      "expires_at" to JsonPrimitive(expires),
      "payload" to Json.parseToJsonElement("""{"link":{"url":"https://example.org","closesAt":"$closes"}}"""),
    ) + extra)
    var reads = 0
    val ok = applyTemporalBundle(bundle, "family", ContentNetwork(
      ContentGet { reads++; 200 to "[${if (reads == 1) row(2, "2026-09-29 00:00:00+00", "2026-09-12 13:00:00-04") else row(3, "2026-09-30 00:00:00+00", "2026-09-12 13:00:00-04")}]" },
      ContentPut { _, _, _ -> 200 to row(3, "2026-09-30 00:00:00+00", "2026-09-12 13:00:00-04").toString() },
    ), dryRun = false)
    assertEquals(0, ok.exitCode, ok.stderr)
    assertTrue(ok.stdout.contains("writes=1"), ok.stdout)
    val drifted = applyTemporalBundle(bundle, "family", ContentNetwork(
      ContentGet { 200 to "[${row(2, "2026-09-29 00:00:00+00", "2026-09-12 13:00:00-04")}]" },
      ContentPut { _, _, _ -> 200 to row(3, "2026-10-01 00:00:00+00", "2026-09-12 13:00:00-04").toString() },
    ), dryRun = false)
    assertEquals(1, drifted.exitCode)
    assertTrue(drifted.stderr.contains("/content/expires_at apply.response-field-mismatch"), drifted.stderr)
  }
}
