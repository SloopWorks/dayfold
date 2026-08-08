package com.sloopworks.dayfold.cli

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RoutineDiffTest {
  private val fixtures = File("../../specs/domain-model/examples/routines/valid")
  private val json = Json { ignoreUnknownKeys = true }

  private data class Parts(val manifest: String, val changeset: String, val current: String, val expected: String)

  private fun parts(name: String): Parts {
    val root = json.parseToJsonElement(File(fixtures, name).readText()).jsonObject
    val expected = root["expectedDiff"]!!.jsonObject
    return Parts(
      manifest = root["manifest"].toString(),
      changeset = root["changeset"].toString(),
      current = root["current"].toString(),
      expected = "changeset diff: create=${expected["create"]!!.jsonPrimitive.int} " +
        "no_change=${expected["no_change"]!!.jsonPrimitive.int} conflict=${expected["conflict"]!!.jsonPrimitive.int}",
    )
  }

  @Test fun `shared fixtures classify create no-change and conflict deterministically`() {
    listOf("create-card.json", "no-change-server-metadata.json", "hostile-text-is-inert.json").forEach { name ->
      val part = parts(name)
      val result = diffChangesetCommand(part.manifest, part.changeset, currentRaw = part.current)
      assertEquals(0, result.exitCode, "$name: ${result.stderr}")
      assertEquals(part.expected, result.stdout)
    }
  }

  @Test fun `offline diff never constructs or calls a network reader`() {
    val part = parts("create-card.json")
    var constructed = 0
    val result = diffChangesetCommand(part.manifest, part.changeset, currentRaw = part.current) {
      constructed += 1
      RoutineCardReader { error("must not be called") }
    }
    assertEquals(0, result.exitCode)
    assertEquals(0, constructed)
  }

  @Test fun `invalid diff never constructs a network reader`() {
    val part = parts("create-card.json")
    var constructed = 0
    val invalid = part.changeset.replace("\"create_card\"", "\"delete_card\"")
    val result = diffChangesetCommand(part.manifest, invalid, readerFactory = {
      constructed += 1
      RoutineCardReader { error("must not be called") }
    })
    assertEquals(1, result.exitCode)
    assertEquals(0, constructed)
  }

  @Test fun `network diff calls the GET-only seam once`() {
    val part = parts("create-card.json")
    var reads = 0
    val result = diffChangesetCommand(part.manifest, part.changeset, readerFactory = {
      RoutineCardReader {
        reads += 1
        RoutineReadResponse(200, "[]")
      }
    })
    assertEquals(0, result.exitCode)
    assertEquals(1, reads)
    assertEquals(part.expected, result.stdout)
    val methods = RoutineCardReader::class.java.declaredMethods.map { it.name }
    assertTrue("readCards" in methods)
    assertFalse(methods.any { it.contains("put", ignoreCase = true) || it.contains("delete", ignoreCase = true) || it.contains("apply", ignoreCase = true) })
  }

  @Test fun `production adapter performs one cards GET and cannot refresh on 401`() {
    val requests = mutableListOf<Pair<String, String?>>()
    val reader = getOnlyRoutineCardReader(
      api = "https://synthetic.invalid",
      family = "family_demo",
      token = "synthetic_access",
      get = { url, token ->
        requests += url to token
        401 to "SYNTHETIC_PRIVATE_RESPONSE"
      },
    )

    val response = reader.readCards()

    assertEquals(401, response.statusCode)
    assertEquals(
      listOf<Pair<String, String?>>("https://synthetic.invalid/families/family_demo/cards" to "synthetic_access"),
      requests,
    )
  }

  @Test fun `network diff fails closed on 401 without response content`() {
    val part = parts("create-card.json")
    var reads = 0
    val result = diffChangesetCommand(part.manifest, part.changeset, readerFactory = {
      RoutineCardReader {
        reads += 1
        RoutineReadResponse(401, "SYNTHETIC_PRIVATE_RESPONSE")
      }
    })
    assertEquals(1, result.exitCode)
    assertEquals(1, reads)
    assertTrue(result.stderr.contains("dayfold login"))
    assertFalse(result.stderr.contains("SYNTHETIC_PRIVATE_RESPONSE"))
  }

  @Test fun `hub-only denial is owned guidance and never includes response body`() {
    val part = parts("create-card.json")
    val result = diffChangesetCommand(part.manifest, part.changeset, readerFactory = {
      RoutineCardReader { RoutineReadResponse(403, "SYNTHETIC_SERVER_PRIVATE_MARKER") }
    })
    assertEquals(1, result.exitCode)
    assertTrue(result.stderr.contains("hub-only credential"))
    assertTrue(result.stderr.contains("content:read"))
    assertFalse(result.stderr.contains("SYNTHETIC_SERVER_PRIVATE_MARKER"))
  }

  @Test fun `hostile text and source refs never reach summaries`() {
    val part = parts("hostile-text-is-inert.json")
    val result = diffChangesetCommand(part.manifest, part.changeset, currentRaw = part.current)
    assertEquals(0, result.exitCode)
    assertFalse(result.stdout.contains("SYNTHETIC_HOSTILE_MARKER"))
    assertFalse(result.stdout.contains("untrusted_demo_ref"))
    assertFalse(result.stderr.contains("SYNTHETIC_HOSTILE_MARKER"))
  }

  @Test fun `existing extra business content is a conflict while server metadata is ignored`() {
    val part = parts("no-change-server-metadata.json")
    val withPayload = part.current.replace(
      "\"version\":4",
      "\"payload\":{\"email\":{\"subject\":\"SYNTHETIC_EXISTING_CONTENT\"}},\"version\":4",
    )
    val result = diffChangesetCommand(part.manifest, part.changeset, currentRaw = withPayload)
    assertEquals(0, result.exitCode)
    assertEquals("changeset diff: create=0 no_change=0 conflict=1", result.stdout)
    assertFalse(result.stdout.contains("SYNTHETIC_EXISTING_CONTENT"))
  }

  @Test fun `malformed current data returns path code without raw JSON`() {
    val part = parts("create-card.json")
    val result = diffChangesetCommand(part.manifest, part.changeset, currentRaw = "{SYNTHETIC_RAW_MARKER")
    assertEquals(1, result.exitCode)
    assertTrue(result.stderr.contains("/current json.syntax"))
    assertFalse(result.stderr.contains("SYNTHETIC_RAW_MARKER"))
  }

  @Test fun `unknown property names and values are not echoed`() {
    val part = parts("create-card.json")
    val injected = part.changeset.replace(
      "\"sourceRefs\":[\"source_demo_01\"]",
      "\"private@example.test\":\"SYNTHETIC_UNKNOWN_VALUE\",\"sourceRefs\":[\"source_demo_01\"]",
    )
    val result = validateChangesetCommand(part.manifest, injected)
    assertEquals(1, result.exitCode)
    assertTrue(result.stderr.contains("schema.additionalProperties"))
    assertFalse(result.stderr.contains("private@example.test"))
    assertFalse(result.stderr.contains("SYNTHETIC_UNKNOWN_VALUE"))
  }

  @Test fun `validate is local and reports operation count only`() {
    val part = parts("create-card.json")
    val result = validateChangesetCommand(part.manifest, part.changeset)
    assertEquals(0, result.exitCode)
    assertEquals("changeset valid: operations=1 execution_mode=shadow authority=create_card", result.stdout)
    assertFalse(result.stdout.contains("card_alpha"))
  }

  @Test fun `command parser exposes only validate and diff with optional current`() {
    assertIs<ChangesetInvocation.Validate>(parseChangesetInvocation(arrayOf("changeset", "validate", "m.json", "c.json")))
    assertIs<ChangesetInvocation.Diff>(parseChangesetInvocation(arrayOf("changeset", "diff", "m.json", "c.json")))
    val offline = assertIs<ChangesetInvocation.Diff>(parseChangesetInvocation(arrayOf("changeset", "diff", "m.json", "c.json", "--current", "p.json")))
    assertEquals("p.json", offline.currentFile)
    assertIs<ChangesetInvocation.Invalid>(parseChangesetInvocation(arrayOf("changeset", "apply", "m.json", "c.json")))
    assertIs<ChangesetInvocation.Invalid>(parseChangesetInvocation(arrayOf("changeset", "diff", "m.json", "c.json", "--write")))
  }
}
