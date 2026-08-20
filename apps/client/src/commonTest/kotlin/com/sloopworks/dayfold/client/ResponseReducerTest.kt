package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResponseReducerTest {

  private fun open(step: ResponseStep = ResponseStep.VERBS) = OpenResponseSheet(
    subjectRef = "card:c1", subjectTitle = "Rain at soccer practice",
    reasonKind = "weather", source = "morning-briefing",
    surface = ResponseSurface.NOW, step = step,
  )

  private val rule = ContentResponse(
    id = "r", kind = ResponseKind.MUTE, subjectRef = "kind:weather",
    matchScope = MatchScope.KIND, audienceScope = AudienceScope.FAMILY,
    userId = null, createdBy = "u_mom", label = "Weather cards",
  )

  @Test
  fun openingTheSheetStartsOnTheVerbStepWithPersonalPreselected() {
    val s = reduceResponses(AppState(), open())
    assertEquals(ResponseStep.VERBS, s.responses.sheet?.step)
    // Personal is the default and the only scope the swipe path can reach (ADR 0064 §4).
    assertEquals(AudienceScope.PERSONAL, s.responses.sheet?.pendingAudience)
  }

  // The swipe escalation jumps straight to the precision step — the member already chose to
  // hide, so re-asking "what do you want to do?" would be a second nag.
  @Test
  fun theSheetCanOpenDirectlyAtTheScopeStep() {
    val s = reduceResponses(AppState(), open(ResponseStep.SCOPE))
    assertEquals(ResponseStep.SCOPE, s.responses.sheet?.step)
    assertEquals(ResponseStep.SCOPE, s.responses.sheet?.entryStep)
  }

  @Test
  fun backFromANowChildStepReturnsToVerbs() {
    var s = reduceResponses(AppState(), open())
    s = reduceResponses(s, ResponseStepDoneNote)
    s = reduceResponses(s, ResponseStepBack)
    assertEquals(ResponseStep.VERBS, s.responses.sheet?.step)
  }

  @Test
  fun backFromADirectHubCompletionDismissesTheSheet() {
    val direct = open(ResponseStep.DONE_NOTE).copy(surface = ResponseSurface.HUB)
    var s = reduceResponses(AppState(), direct)
    s = reduceResponses(s, ResponseStepBack)
    assertNull(s.responses.sheet)
  }

  @Test
  fun scopeStepAndAudienceAreIndependent() {
    var s = reduceResponses(AppState(), open())
    s = reduceResponses(s, ResponseStepScope)
    s = reduceResponses(s, SetResponseAudience(AudienceScope.FAMILY))
    assertEquals(ResponseStep.SCOPE, s.responses.sheet?.step)
    assertEquals(AudienceScope.FAMILY, s.responses.sheet?.pendingAudience)
  }

  @Test
  fun closingClearsTheSheetButNotTheRules() {
    var s = reduceResponses(AppState(responses = ResponseState(rules = listOf(rule))), open())
    s = reduceResponses(s, CloseResponseSheet)
    assertNull(s.responses.sheet)
    assertEquals(listOf(rule), s.responses.rules)
  }

  // A step action with no sheet open must be inert, not a crash: the receipt's Undo and the
  // sheet's dismiss can race on a slow frame.
  @Test
  fun stepActionsWithNoSheetAreNoOps() {
    val s = AppState()
    assertEquals(s, reduceResponses(s, ResponseStepScope))
    assertEquals(s, reduceResponses(s, SetResponseAudience(AudienceScope.FAMILY)))
  }

  @Test
  fun receiptsAreShownAndDismissed() {
    val receipt = ResponseReceipt("r1", "Muted", undoable = true, offline = false)
    var s = reduceResponses(AppState(), ResponseReceiptShown(receipt))
    assertEquals(receipt, s.responses.lastReceipt)
    s = reduceResponses(s, ResponseReceiptDismissed)
    assertNull(s.responses.lastReceipt)
  }

  @Test
  fun responsesLoadedIsTheSoleWriterOfRules() {
    val s = reduceResponses(AppState(), ResponsesLoaded(listOf(rule)))
    assertEquals(listOf(rule), s.responses.rules)
  }

  // Family-scoped synced content: carrying rules across a family switch would let one
  // family's mutes suppress another's feed until the first sync landed.
  @Test
  fun aFamilySwitchClearsTheRules() {
    val before = AppState(
      session = SessionState(activeFamilyId = "fam_a"),
      responses = ResponseState(rules = listOf(rule)),
    )
    val after = rootReducer(
      before,
      MembershipsLoaded(listOf(FamilyMembership(familyId = "fam_b", name = "Other"))),
    )
    assertEquals("fam_b", after.session.activeFamilyId)   // the switch actually happened
    assertTrue(after.responses.rules.isEmpty())
  }
}
