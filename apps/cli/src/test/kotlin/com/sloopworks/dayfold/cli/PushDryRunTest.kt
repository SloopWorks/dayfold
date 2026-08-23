package com.sloopworks.dayfold.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `push --dry-run` — the guarantee is **no write**, and that what it shows is what a real
 * push would actually send.
 *
 * `--dry-run` is a bare flag, so the two parsers that split `push`'s command line have to
 * ignore it: `pushPositionals` (which must still see `<id> <file>` as the positionals, not
 * mistake the flag for one) and `pushResource` (which must still resolve the right
 * resource). Those are the parts that can silently break — a flag that shifted the
 * positionals would make `--dry-run` push the WRONG id, which is the one outcome a dry run
 * must never have. They are asserted here in every flag order.
 *
 * The printed payload is `stamped` — the post-linkify, post-checklist-id value that the PUT
 * itself carries — rather than a re-derivation, so the two cannot drift. That the stamping
 * is deterministic given a fixed minter is what makes the preview trustworthy, and is
 * covered here too.
 */
class PushDryRunTest {

  // ── the flag must not disturb positional parsing, in any order ──────────────

  @Test
  fun `--dry-run never shifts the id or file positionals`() {
    val expected = listOf("c1", "card.json")
    // A dry run that read the wrong id would preview one card and (on a later real push)
    // write another — the exact failure this asserts away.
    assertEquals(expected, pushPositionals(arrayOf("push", "c1", "card.json", "--dry-run")))
    assertEquals(expected, pushPositionals(arrayOf("push", "--dry-run", "c1", "card.json")))
    assertEquals(expected, pushPositionals(arrayOf("push", "c1", "--dry-run", "card.json")))
  }

  @Test
  fun `--dry-run composes with the value-flag --type without eating the file`() {
    assertEquals(
      listOf("c1", "card.json"),
      pushPositionals(arrayOf("push", "c1", "card.json", "--type", "invite", "--dry-run")),
    )
    assertEquals(
      listOf("c1", "card.json"),
      pushPositionals(arrayOf("push", "--dry-run", "--type", "invite", "c1", "card.json")),
    )
  }

  @Test
  fun `--dry-run does not change which resource is targeted`() {
    assertEquals("cards", pushResource(arrayOf("push", "c1", "card.json", "--dry-run")))
    assertEquals("hubs", pushResource(arrayOf("push", "h1", "hub.json", "--hub", "--dry-run")))
    assertEquals("sections", pushResource(arrayOf("push", "--dry-run", "s1", "sec.json", "--section")))
    assertEquals("blocks", pushResource(arrayOf("push", "b1", "blk.json", "--block", "--dry-run")))
  }

  // ── what it previews is what a push would send ─────────────────────────────

  @Test
  fun `the previewed payload is the stamped one, so the preview cannot drift from the write`() {
    // stampChecklistIds is the last transform before the PUT. --dry-run prints its output,
    // not the raw file, so a checklist item minted an id in the preview gets that same id
    // written on a real push of the same file.
    val raw = """{"sectionId":"s1","type":"checklist","payload":{"items":[{"text":"sunscreen"}]}}"""
    var n = 0
    val stamped = stampChecklistIds("blocks", raw) { "ULID${++n}" }
    assertTrue(stamped.contains("ULID1"), "the preview must show the minted item id: $stamped")
    assertTrue(stamped.contains("\"ord\":0"), "ADR 0038 stamps `ord` too; the preview must show it: $stamped")
    assertTrue(stamped.contains("sunscreen"))
  }

  @Test
  fun `stamping is deterministic given a fixed minter, so the preview is reproducible`() {
    val raw = """{"sectionId":"s1","type":"checklist","payload":{"items":[{"text":"a"},{"text":"b"}]}}"""
    fun run(): String { var n = 0; return stampChecklistIds("blocks", raw) { "ULID${++n}" } }
    assertEquals(run(), run(), "the same input must preview identically every time")
  }

  @Test
  fun `an item that already has an id keeps it, so a re-push previews no churn`() {
    // Ids from `pull` must survive: a dry run of an unchanged file should show the same
    // ids the server already holds, otherwise the preview would imply a rewrite that
    // isn't one.
    val raw = """{"sectionId":"s1","type":"checklist","payload":{"items":[{"id":"KEEP","text":"sunscreen"}]}}"""
    val stamped = stampChecklistIds("blocks", raw) { "MINTED" }
    assertTrue(stamped.contains("KEEP"), "an existing item id must be preserved: $stamped")
    assertTrue(!stamped.contains("MINTED"), "no new id may be minted over an existing one: $stamped")
  }
}
