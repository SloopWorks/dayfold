package com.sloopworks.dayfold.client.snapshot

import com.sloopworks.dayfold.client.AudienceScope
import com.sloopworks.dayfold.client.DoneRow
import com.sloopworks.dayfold.client.MatchScope
import com.sloopworks.dayfold.client.ResponseSheetState
import com.sloopworks.dayfold.client.ResponseSurface
import com.sloopworks.dayfold.client.RuleProvenance
import com.sloopworks.dayfold.client.RuleRow
import com.sloopworks.dayfold.client.SmartContentModel

// ADR 0064 — snapshot fixtures mirroring the design's GAP-5 view: the three rule provenances
// side by side (the case where "distinguished by sub-line copy, never colour alone" is
// actually visible), and a Done record carrying both a byline and a quoted note.
object SmartContentFixtures {
  val names = mapOf("u_mom" to "Mom", "u_dad" to "Dad")

  fun model(pending: Boolean = false) = SmartContentModel(
    mutedRules = listOf(
      RuleRow("r1", RuleProvenance.PERSONAL, "u_dad", "Traffic cards", "From Morning briefing", pending, removable = true),
      RuleRow("r2", RuleProvenance.FAMILY, "u_mom", "Weather in Now", null, false, removable = true),
      RuleRow("r3", RuleProvenance.DEVICE, "u_dad", "Cabin countdown", null, false, removable = true),
    ),
    doneRecords = listOf(
      DoneRow("d1", "Verify emergency contact", "u_mom", "Confirmed — used Grandma's new number.", false),
      DoneRow("d2", "RSVP scout campout", "u_dad", null, false),
    ),
  )

  fun sheet(surface: ResponseSurface) = ResponseSheetState(
    subjectRef = "card:c_rain",
    subjectTitle = "Rain at soccer practice",
    reasonKind = "weather",
    source = "Morning briefing",
    surface = surface,
    pendingMatchScope = MatchScope.KIND,
    pendingAudience = AudienceScope.PERSONAL,
  )
}
