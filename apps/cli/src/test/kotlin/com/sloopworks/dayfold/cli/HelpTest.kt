package com.sloopworks.dayfold.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The command registry (Help.kt) is the single source of truth for the index, per-command
// help, and `--json`. These tests enforce the "everything is documented" invariant and pin
// the help routing so `dayfold <command> --help` / `dayfold help <command>` / `--json` work.
class HelpTest {

  // ── Completeness: every command/arg/option carries a description + an example ──
  @Test fun `every command has a summary and at least one example`() {
    COMMANDS.forEach { c ->
      assertTrue(c.summary.isNotBlank(), "command '${c.name}' has no summary")
      assertTrue(c.synopsis.isNotBlank(), "command '${c.name}' has no synopsis")
      assertTrue(c.examples.isNotEmpty(), "command '${c.name}' has no example")
    }
  }

  @Test fun `every option and every arg has a non-blank description`() {
    COMMANDS.forEach { c ->
      c.options.forEach { o ->
        assertTrue(o.flag.isNotBlank(), "command '${c.name}' has an option with no flag")
        assertTrue(o.description.isNotBlank(), "option '${o.flag}' of '${c.name}' has no description")
      }
      c.args.forEach { a ->
        assertTrue(a.description.isNotBlank(), "arg '${a.name}' of '${c.name}' has no description")
      }
    }
  }

  // ── Alias resolution ──
  @Test fun `commandByToken resolves names and aliases`() {
    assertEquals("delete", commandByToken("rm")?.name)
    assertEquals("update", commandByToken("upgrade")?.name)
    assertEquals("version", commandByToken("-v")?.name)
    assertEquals("push", commandByToken("push")?.name)
    assertNull(commandByToken("nope"))
    assertNull(commandByToken(null))
  }

  // ── Routing: helpInvocation decides help BEFORE the normal dispatch ──
  @Test fun `top-level help flags resolve to the index`() {
    listOf("help", "-h", "--help").forEach { tok ->
      val req = helpInvocation(arrayOf(tok))
      assertNotNull(req, "'$tok' should be a help invocation")
      assertNull(req.command, "'$tok' should be the index (no command)")
      assertTrue(!req.json)
    }
  }

  @Test fun `command --help resolves to that command`() {
    assertEquals("push", helpInvocation(arrayOf("push", "--help"))?.command?.name)
    assertEquals("pull", helpInvocation(arrayOf("pull", "-h"))?.command?.name)
    // alias before the help flag resolves too
    assertEquals("delete", helpInvocation(arrayOf("rm", "--help"))?.command?.name)
  }

  @Test fun `help command form resolves to that command`() {
    assertEquals("push", helpInvocation(arrayOf("help", "push"))?.command?.name)
    assertEquals("delete", helpInvocation(arrayOf("help", "rm"))?.command?.name)
  }

  @Test fun `json flag is detected for index and scoped help`() {
    assertTrue(helpInvocation(arrayOf("help", "--json"))!!.json)
    assertNull(helpInvocation(arrayOf("help", "--json"))!!.command)
    val scoped = helpInvocation(arrayOf("push", "--help", "--json"))!!
    assertTrue(scoped.json)
    assertEquals("push", scoped.command?.name)
  }

  @Test fun `a normal invocation is not a help invocation`() {
    assertNull(helpInvocation(arrayOf("push", "01JID", "card.json", "--type", "file")))
    assertNull(helpInvocation(arrayOf("pull")))
    assertNull(helpInvocation(arrayOf("whoami")))
  }

  // ── JSON output is valid + complete ──
  @Test fun `index json parses and lists every command`() {
    val root = Json.parseToJsonElement(renderJson(helpModel())).jsonObject
    val names = root["commands"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
    assertTrue(names.containsAll(COMMANDS.map { it.name }), "index json missing a command: got $names")
    assertTrue(root.containsKey("env") && root.containsKey("exitCodes"))
  }

  @Test fun `scoped command json parses and carries that command's options`() {
    val push = Json.parseToJsonElement(renderJsonCommand(commandByToken("push")!!)).jsonObject
    assertEquals("push", push["name"]!!.jsonPrimitive.content)
    val flags = push["options"]!!.jsonArray.map { it.jsonObject["flag"]!!.jsonPrimitive.content }
    assertTrue(flags.contains("--no-linkify"), "push json options missing --no-linkify: $flags")
  }

  // ── Index text renders every command name ──
  @Test fun `index text starts with usage and lists every command`() {
    val idx = renderIndex(helpModel())
    assertTrue(idx.startsWith("usage: dayfold"))
    COMMANDS.forEach { c -> assertTrue(idx.contains(c.name), "index missing '${c.name}'") }
  }
}
