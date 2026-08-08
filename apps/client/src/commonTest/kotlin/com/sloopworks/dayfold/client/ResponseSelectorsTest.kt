package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ADR 0064 GAP 5 — the Settings projection returns STRUCTURED data. The rendered sentences are
// asserted in :ui (SmartContentCopyTest), so a copy change never churns these logic tests.
class ResponseSelectorsTest {

  private fun mute(
    id: String,
    audience: AudienceScope = AudienceScope.FAMILY,
    userId: String? = null,
    createdBy: String = "u_mom",
    sublabel: String? = null,
    pending: Boolean = false,
  ) = ContentResponse(
    id = id, kind = ResponseKind.MUTE, subjectRef = "kind:weather", matchScope = MatchScope.KIND,
    audienceScope = audience, userId = userId, createdBy = createdBy,
    label = "Weather in Now", sublabel = sublabel, pending = pending,
  )

  private fun done(id: String, createdBy: String, note: String?) = ContentResponse(
    id = id, kind = ResponseKind.DONE, subjectRef = "card:c1", matchScope = MatchScope.SUBJECT,
    audienceScope = AudienceScope.FAMILY, userId = null, createdBy = createdBy,
    label = "Verify emergency contact", note = note,
  )

  @Test
  fun eachRuleCarriesItsProvenanceAsData() {
    val m = smartContentSections(
      rules = listOf(
        mute("r1", AudienceScope.PERSONAL, userId = "u_dad", createdBy = "u_dad", sublabel = "From Morning briefing"),
        mute("r2", AudienceScope.FAMILY, createdBy = "u_mom"),
      ),
      doneRecords = emptyList(),
      viewerUserId = "u_dad",
    )
    assertEquals(RuleProvenance.PERSONAL, m.mutedRules[0].provenance)
    assertEquals(RuleProvenance.FAMILY, m.mutedRules[1].provenance)
    assertEquals("u_mom", m.mutedRules[1].authorUserId)   // the byline's input, not the byline
    assertEquals("From Morning briefing", m.mutedRules[0].sublabel)
  }

  // Decided Q2: any adult removes a family rule; a personal rule is its owner's alone.
  @Test
  fun removabilityFollowsTheAudienceRules() {
    val m = smartContentSections(
      rules = listOf(
        mute("mine", AudienceScope.PERSONAL, userId = "u_dad", createdBy = "u_dad"),
        mute("theirs", AudienceScope.PERSONAL, userId = "u_mom", createdBy = "u_mom"),
        mute("family", AudienceScope.FAMILY, createdBy = "u_mom"),
      ),
      doneRecords = emptyList(),
      viewerUserId = "u_dad",
    )
    assertTrue(m.mutedRules.single { it.id == "mine" }.removable)
    assertFalse(m.mutedRules.single { it.id == "theirs" }.removable)
    assertTrue(m.mutedRules.single { it.id == "family" }.removable)
  }

  @Test
  fun doneRowsCarryTheAuthorAndTheRawNote() {
    val m = smartContentSections(
      emptyList(),
      listOf(done("d1", "u_mom", "Confirmed — used Grandma's new number.")),
      "u_dad",
    )
    assertEquals("Verify emergency contact", m.doneRecords[0].title)
    assertEquals("u_mom", m.doneRecords[0].authorUserId)
    // Raw, unquoted — :ui decides how to present it.
    assertEquals("Confirmed — used Grandma's new number.", m.doneRecords[0].note)
  }

  @Test
  fun aDoneRowWithNoNoteHasANullNote() {
    assertNull(smartContentSections(emptyList(), listOf(done("d2", "u_dad", null)), "u_mom").doneRecords[0].note)
  }

  @Test
  fun aPendingRuleIsFlaggedPending() {
    val m = smartContentSections(listOf(mute("r1", pending = true)), emptyList(), "u_dad")
    assertTrue(m.mutedRules[0].pending)
  }

  // One synced table, two Settings sections.
  @Test
  fun theStateOverloadSplitsMutesFromDoneRecords() {
    val state = AppState(
      session = SessionState(session = Session("t", "r", userId = "u_dad")),
      responses = ResponseState(rules = listOf(mute("r1"), done("d1", "u_mom", null))),
    )
    val m = smartContentSections(state)
    assertEquals(listOf("r1"), m.mutedRules.map { it.id })
    assertEquals(listOf("d1"), m.doneRecords.map { it.id })
  }
}
