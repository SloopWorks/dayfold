package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ADR 0064 GAP 1 — the precision step and the decided me/family choice.
class ResponseScopeStepTest {

  private val sheet = ResponseSheetState(
    subjectRef = "card:c1",
    subjectTitle = "Rain at soccer practice",
    reasonKind = "weather",
    source = "Morning briefing",
    surface = ResponseSurface.NOW,
    step = ResponseStep.SCOPE,
  )

  @Test
  fun theKindRungIsPreselected() {
    assertEquals(MatchScope.KIND, sheet.pendingMatchScope)
    assertTrue(scopeRowsFor(sheet).single { it.scope == MatchScope.KIND }.selected)
    assertFalse(scopeRowsFor(sheet).single { it.scope == MatchScope.SUBJECT }.selected)
  }

  @Test
  fun theRungsReadAsTheDesignWrotThem() {
    val rows = scopeRowsFor(sheet)
    assertEquals("Just this card", rows[0].title)
    assertEquals("This exact card won't return", rows[0].subtitle)
    assertEquals("Any weather cards", rows[1].title)
    assertEquals("Morning briefing stops making these", rows[1].subtitle)
  }

  // The control already exists in Smart Briefings; duplicating it here would create two
  // sources of truth for one behavior.
  @Test
  fun theSourceRungDeepLinksInsteadOfMinting() {
    val row = scopeRowsFor(sheet).single { it.scope == MatchScope.SOURCE }
    assertEquals("Everything from Morning briefing", row.title)
    assertEquals("Pauses the routine — manage in Smart Briefings", row.subtitle)
    assertTrue(row.deepLinksToRoutines)
    assertFalse(row.selected)   // it is never a selectable rung
  }

  @Test
  fun theCommitButtonNamesTheFinalAction() {
    assertEquals("Mute weather cards for you", commitLabel(sheet, "weather"))
    assertEquals(
      "Mute weather cards for everyone",
      commitLabel(sheet.copy(pendingAudience = AudienceScope.FAMILY), "weather"),
    )
    assertEquals(
      "Mute this card for you",
      commitLabel(sheet.copy(pendingMatchScope = MatchScope.SUBJECT), "weather"),
    )
  }

  @Test
  fun familyWideSpeaksTheConsequenceBeforeTheCommit() {
    assertEquals(
      "Family-wide mutes are visible to everyone in Settings and removable by any adult — always attributed.",
      audienceFootnote(),
    )
  }

  // Personal is the default at the model level, not just visually — the swipe path reaches
  // this step directly and must never land on family-wide.
  @Test
  fun personalIsTheDefaultAudience() {
    assertEquals(AudienceScope.PERSONAL, ResponseSheetState(
      subjectRef = "card:c1", subjectTitle = "t", reasonKind = "weather",
      source = null, surface = ResponseSurface.NOW,
    ).pendingAudience)
  }

  // A card with no routine behind it still needs readable copy.
  @Test
  fun aSourcelessSubjectStillReadsWell() {
    val noSource = sheet.copy(source = null)
    assertEquals("This routine stops making these", scopeRowsFor(noSource)[1].subtitle)
    assertEquals("Everything from this routine", scopeRowsFor(noSource)[2].title)
  }
}
