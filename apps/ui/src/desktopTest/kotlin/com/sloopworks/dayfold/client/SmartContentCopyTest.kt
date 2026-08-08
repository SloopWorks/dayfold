package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ADR 0064 GAP 5 — the rendered copy. :client returns structure; this is where the sentences
// are pinned, verbatim from the design.
class SmartContentCopyTest {

  private val names = mapOf("u_mom" to "Mom", "u_dad" to "Dad")

  private fun ruleRow(
    provenance: RuleProvenance,
    authorUserId: String = "u_mom",
    sublabel: String? = null,
    pending: Boolean = false,
  ) = RuleRow(
    id = "r", provenance = provenance, authorUserId = authorUserId,
    label = "Weather in Now", sublabel = sublabel, pending = pending, removable = true,
  )

  private fun doneRow(authorUserId: String, note: String?) =
    DoneRow(id = "d", title = "Verify emergency contact", authorUserId = authorUserId, note = note, pending = false)

  // Three provenances, three distinct sub-lines — never distinguished by colour alone.
  @Test
  fun eachProvenanceHasItsOwnSubline() {
    assertEquals(
      "From Morning briefing · just you",
      sublineFor(ruleRow(RuleProvenance.PERSONAL, sublabel = "From Morning briefing"), names),
    )
    assertEquals(
      "Whole family · muted by Mom · any adult can remove",
      sublineFor(ruleRow(RuleProvenance.FAMILY, authorUserId = "u_mom"), names),
    )
    assertEquals(
      "On this device · derived lane · never synced",
      sublineFor(ruleRow(RuleProvenance.DEVICE), names),
    )
  }

  @Test
  fun theThreeSublinesAreAllDifferent() {
    val lines = RuleProvenance.entries.map { sublineFor(ruleRow(it, sublabel = "From Morning briefing"), names) }
    assertEquals(lines.size, lines.toSet().size)
  }

  // GAP 6 — a rule cannot stop a run that already happened, and the copy says so rather than
  // implying instant effect.
  @Test
  fun aPendingRuleIsHonestAboutWhenItTakesEffect() {
    assertEquals(
      "Saved on this phone — syncs & takes effect next run",
      sublineFor(ruleRow(RuleProvenance.PERSONAL, pending = true), names),
    )
  }

  @Test
  fun aPersonalRuleWithNoSourceStillReads() {
    assertEquals("just you", sublineFor(ruleRow(RuleProvenance.PERSONAL), names))
  }

  @Test
  fun anUnknownMemberDegradesGracefully() {
    assertTrue(sublineFor(ruleRow(RuleProvenance.FAMILY, authorUserId = "u_ghost"), names).contains("a family member"))
    assertEquals("a family member", doneSublineFor(doneRow("u_ghost", null), names))
  }

  @Test
  fun aDoneBylineQuotesTheNoteWhenThereIsOne() {
    assertEquals(
      "\"Confirmed — used Grandma's new number.\" · Mom",
      doneSublineFor(doneRow("u_mom", "Confirmed — used Grandma's new number."), names),
    )
    assertEquals("Dad", doneSublineFor(doneRow("u_dad", null), names))
    assertEquals("Dad", doneSublineFor(doneRow("u_dad", "  "), names))   // whitespace is not a note
  }

  // The reassurance is a promise the code keeps literally — suppression is string equality on
  // opaque identifier columns.
  @Test
  fun thePrivacyNoteIsTheDesignsWording() {
    assertEquals(
      "Your routine reads these before it adds anything. The server checks by ID — it never reads your content.",
      SMART_CONTENT_PRIVACY_NOTE,
    )
  }
}
