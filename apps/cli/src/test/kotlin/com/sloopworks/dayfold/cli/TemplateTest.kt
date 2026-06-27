package com.sloopworks.dayfold.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertTrue

// `dayfold template hub|section|block` ships starter bodies for the hub tree
// (push --hub/--section/--block). They must load, parse, and carry the field the
// matching PUT route requires.
class TemplateTest {
  private fun load(name: String): String =
    javaClass.getResourceAsStream("/templates/$name.json")!!.readBytes().decodeToString()

  @Test fun `hub-tree templates are registered, valid JSON, with the required keys`() {
    listOf("hub", "section", "block").forEach { assertTrue(it in TEMPLATE_KINDS, "$it not registered") }
    val hub = Json.parseToJsonElement(load("hub")).jsonObject
    assertTrue("type" in hub && "title" in hub)
    assertTrue("hubId" in Json.parseToJsonElement(load("section")).jsonObject)       // section route requires hubId
    val block = Json.parseToJsonElement(load("block")).jsonObject
    assertTrue("sectionId" in block && "type" in block)                               // block route requires sectionId
  }

  @Test fun `hub-tree templates pass validateHubTree — the 'template X | push --X' round-trip`() {
    // ValidateTest pins the CARD templates against validateCard; this is the hub-tree
    // parallel. A template drifting from validateHubTree (the check push --hub/--section/
    // --block applies) would silently break the first thing a new author tries.
    for ((name, resource) in listOf("hub" to "hubs", "section" to "sections", "block" to "blocks")) {
      val errs = validateHubTree(resource, load(name))
      assertTrue(errs.isEmpty(), "template $name should pass validateHubTree(\"$resource\"), got: $errs")
    }
  }
}
