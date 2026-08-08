package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals

// ADR 0064 — these literals are the CONTRACT with apps/api/src/content/subject-ref.ts.
// The server matches client-minted rule keys by string equality, so a drift between the two
// grammars stops suppression without erroring. The mirrored expectations in
// apps/api/test/subject-ref.test.ts must stay identical to these.
class SubjectRefTest {

  @Test
  fun cardRefMatchesTheServerGrammar() {
    assertEquals("card:c_123", SubjectRef.card("c_123"))
  }

  @Test
  fun nodeRefIncludesEachLevelPresent() {
    assertEquals("hub:h_9/section:s_2/block:b_4", SubjectRef.node("h_9", "s_2", "b_4"))
    assertEquals("hub:h_9/block:b_4", SubjectRef.node("h_9", null, "b_4"))
    assertEquals("hub:h_9/section:s_2", SubjectRef.node("h_9", "s_2"))
  }

  // The hub-countdown subject. If this shortened form were not expressible, a member could
  // never mute a hub countdown — the derived lane has no block to key it on.
  @Test
  fun aHubAloneIsAValidSubject() {
    assertEquals("hub:h_9", SubjectRef.node("h_9"))
  }

  @Test
  fun classRefs() {
    assertEquals("kind:weather", SubjectRef.kind("weather"))
    assertEquals("source:morning-briefing", SubjectRef.source("morning-briefing"))
  }
}
