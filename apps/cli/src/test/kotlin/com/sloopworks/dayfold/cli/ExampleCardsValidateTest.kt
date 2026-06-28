package com.sloopworks.dayfold.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The committed example feed cards (apps/cli/examples/feed/) must stay valid against
// the generated card schema — they're a ready-to-push reference for the Now feed, and
// one (c2) carries a target deep-link to the example hub (sample-college). Locks both
// the examples and validateCard. Gradle runs CLI tests with workdir = apps/cli.
class ExampleCardsValidateTest {
  private val dir = File("examples/feed")
  private fun read(name: String) = File(dir, name).readText()
  private fun ok(name: String, type: String) {
    val errs = validateCard(type, read(name))
    assertTrue(errs.isEmpty(), "$name failed validateCard: $errs")
  }

  @Test fun `example feed cards validate against the generated schema`() {
    assertTrue(dir.isDirectory, "examples/feed not found at ${dir.absolutePath}")
    ok("c1-rsvp.json", "invite")
    ok("c2-finaid.json", "contact")
  }

  @Test fun `c2 deep-link target resolves to ids the hub example actually creates`() {
    // The feed example's SIGNATURE is the card→hub deep-link (see its README). The schema
    // check above can't catch a target that points at a hub id the example never creates —
    // so if the hub example's ids drift and c2 isn't updated, the deep-link silently breaks.
    // Parse the hub example's push.sh for the REAL ids and assert c2's target matches.
    val hubPush = File("examples/hub-college/push.sh").readText()
    fun pushedIds(flag: String) =
      Regex("""dayfold push (\S+)\s+\S+\s+$flag""").findAll(hubPush).map { it.groupValues[1] }.toList()
    val hubId = pushedIds("--hub").single()
    val sectionIds = pushedIds("--section").toSet()

    val target = Json.parseToJsonElement(read("c2-finaid.json")).jsonObject["target"]!!.jsonObject
    assertEquals(hubId, target["hubId"]!!.jsonPrimitive.content, "c2 deep-link hubId must match the hub example's hub")
    assertTrue(
      target["sectionId"]!!.jsonPrimitive.content in sectionIds,
      "c2 deep-link sectionId must be a real section the hub example creates (have: $sectionIds)",
    )
  }
}
