package com.sloopworks.dayfold.client

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import com.sloopworks.dayfold.client.cards.CardAction
import org.reduxkotlin.concurrent.NotificationContext
import org.reduxkotlin.compose.SelectorStore
import org.reduxkotlin.compose.rememberSelectorStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ADR 0064 — the wiring between a tapped affordance and the effect it names. This is the layer
// that was missing when the feature first shipped: every piece existed and none of them were
// connected, which no unit test noticed because each half passed on its own.
@OptIn(ExperimentalTestApi::class)
class ResponseWiringTest {

  // Delegates to the shipped navigation-only command set (side-effect-free) and records only
  // the response verbs, so the double cannot drift as the port grows.
  private class RecordingCommands : DayfoldCommandPort by DayfoldCommands.navigationOnly(
    createAppStore(NotificationContext.Inline, AppState(), debug = false),
  ) {
    val calls = mutableListOf<String>()
    override fun mute(subjectRef: String, matchScope: MatchScope, audience: AudienceScope, label: String, sublabel: String?) {
      calls += "mute($subjectRef,${matchScope.wire},${audience.wire},$label)"
    }
    override fun markDone(subjectRef: String, label: String, note: String?) { calls += "markDone($subjectRef,note=$note)" }
    override fun hideBlock(familyId: String, blockId: String) { calls += "hideBlock($blockId)" }
    override fun deleteBlock(familyId: String, blockId: String) { calls += "deleteBlock($blockId)" }
  }

  private fun sheet(
    subjectRef: String = "card:c1",
    matchScope: MatchScope = MatchScope.KIND,
    audience: AudienceScope = AudienceScope.PERSONAL,
  ) = ResponseSheetState(
    subjectRef = subjectRef, subjectTitle = "Rain at soccer practice", reasonKind = "weather",
    source = "Morning briefing", surface = ResponseSurface.NOW,
    pendingMatchScope = matchScope, pendingAudience = audience,
  )

  // A NowItem must hand the sheet its OWN subject key — using the card id instead would key the
  // rule to something the server never matches.
  @Test
  fun aNowItemRespondsWithItsSubjectKey() {
    val item = NowItem(
      id = "derived:countdown:h1", origin = Origin.DERIVED, reasonKind = "countdown",
      title = "Cabin weekend", why = "in 3 days", subjectKey = "hub:h1", target = null,
      authoredSource = null,
    )
    val action = item.respondAction()
    assertEquals("hub:h1", action.subjectRef)
    assertEquals("Cabin weekend", action.subjectTitle)
    assertEquals("countdown", action.reasonKind)
    assertTrue(!action.atScope)
  }

  // The swipe escalation must land on the precision step, not the verb list — the member has
  // already chosen to hide, so re-asking "what do you want to do?" is a second nag.
  @Test
  fun theSwipeEscalationOpensAtTheScopeStep() {
    val item = NowItem(
      id = "authored:c1", origin = Origin.AUTHORED, reasonKind = "weather", title = "Rain",
      why = "Rain at 5", subjectKey = "card:c1", target = null, authoredSource = "morning-briefing",
    )
    assertTrue(item.respondAction(atScope = true).atScope)
  }

  @Test
  fun respondIsInAppNavigationNotAnOsHandoff() {
    // A URI would mean the shell tries to open it externally.
    assertEquals(null, com.sloopworks.dayfold.client.cards.cardActionUri(
      CardAction.Respond("card:c1", "t", "weather", null),
    ))
  }

  @Test
  fun aHubCompletionPreservesItsSurfaceAndDirectEntryStep() = runComposeUiTest {
    val store = createAppStore(NotificationContext.Inline, AppState(), debug = false)
    val commands = RecordingCommands()
    lateinit var selectorStore: SelectorStore<AppState>
    setContent { selectorStore = rememberSelectorStore(store) }
    waitForIdle()
    val action = CardAction.Respond(
      "hub:h1/section:s1/block:b1", "Call the caterer", "text", "Weekend planner",
      surface = ResponseSurface.HUB,
      initialStep = ResponseStep.DONE_NOTE,
    )

    routeCardAction(
      selectorStore, commands, StablePlatformActions.noOp(), "fam1", fromFeedDetail = false, action,
    )

    assertEquals(ResponseSurface.HUB, store.state.responses.sheet?.surface)
    assertEquals(ResponseStep.DONE_NOTE, store.state.responses.sheet?.step)
    assertEquals(ResponseStep.DONE_NOTE, store.state.responses.sheet?.entryStep)
  }

  @Test
  fun theMuteCommitUsesTheScopeTheMemberChose() {
    val c = RecordingCommands()
    commitResponseMute({ _: Any -> }, c, sheet(matchScope = MatchScope.KIND))
    assertEquals(listOf("mute(kind:weather,kind,personal,Weather cards)"), c.calls)
  }

  @Test
  fun aSubjectScopedMuteKeysOnTheSubjectItself() {
    val c = RecordingCommands()
    commitResponseMute({ _: Any -> }, c, sheet(matchScope = MatchScope.SUBJECT))
    assertEquals(listOf("mute(card:c1,subject,personal,Rain at soccer practice)"), c.calls)
  }

  @Test
  fun familyWideCarriesThroughToTheCommand() {
    val c = RecordingCommands()
    commitResponseMute({ _: Any -> }, c, sheet(audience = AudienceScope.FAMILY))
    assertTrue(c.calls.single().contains(",family,"))
  }

  // One action, two effects, delete FIRST — the reverse order would leave a rule with nothing
  // to suppress if the delete failed.
  @Test
  fun theHubPairingDeletesThenMutes() {
    val c = RecordingCommands()
    routeResponseVerb({ _: Any -> }, c, "fam1", sheet(subjectRef = "hub:h1/block:b1"), Verb.DELETE_AND_MUTE)
    assertEquals(2, c.calls.size)
    assertTrue(c.calls[0].startsWith("deleteBlock(b1)"))
    assertTrue(c.calls[1].startsWith("mute(hub:h1/block:b1,subject,family,"))
  }

  // Mark done OPENS the note step rather than writing immediately. The note is optional but
  // must be offered — writing straight through is what made it unreachable the first time.
  @Test
  fun markDoneOpensTheNoteStepAndWritesNothingYet() {
    val c = RecordingCommands()
    val dispatched = mutableListOf<Any>()
    routeResponseVerb({ dispatched += it }, c, "fam1", sheet(), Verb.DONE)
    assertTrue(c.calls.isEmpty())
    assertEquals(listOf<Any>(ResponseStepDoneNote), dispatched)
  }

  @Test
  fun justDoneCompletesWithNoNote() {
    val c = RecordingCommands()
    commitResponseDone({ _: Any -> }, c, sheet(), null)
    assertEquals(listOf("markDone(card:c1,note=null)"), c.calls)
  }

  @Test
  fun aNoteIsCarriedThrough() {
    val c = RecordingCommands()
    commitResponseDone({ _: Any -> }, c, sheet(), "Confirmed — used Grandma's new number.")
    assertEquals(listOf("markDone(card:c1,note=Confirmed — used Grandma's new number.)"), c.calls)
  }

  // Whitespace is not a note — it must not be stored as one.
  @Test
  fun aBlankNoteIsTreatedAsNoNote() {
    val c = RecordingCommands()
    commitResponseDone({ _: Any -> }, c, sheet(), "   ")
    assertEquals(listOf("markDone(card:c1,note=null)"), c.calls)
  }

  @Test
  fun justDoneIsNeverGatedOnTyping() {
    assertTrue(doneNoteActions(noteDraft = "").first { it.label == "Just done" }.enabled)
  }

  // Hide is device-local (W5) and acts on a BLOCK — it must not fire for a card subject, which
  // has no block to hide.
  @Test
  fun hideOnlyAppliesToABlockSubject() {
    val onBlock = RecordingCommands()
    routeResponseVerb({ _: Any -> }, onBlock, "fam1", sheet(subjectRef = "hub:h1/block:b1"), Verb.HIDE)
    assertEquals(listOf("hideBlock(b1)"), onBlock.calls)

    val onCard = RecordingCommands()
    routeResponseVerb({ _: Any -> }, onCard, "fam1", sheet(subjectRef = "card:c1"), Verb.HIDE)
    assertTrue(onCard.calls.isEmpty())
  }

  // MUTE writes nothing until the member has chosen a scope.
  @Test
  fun muteDefersToTheScopeStepAndWritesNothingYet() {
    val c = RecordingCommands()
    routeResponseVerb({ _: Any -> }, c, "fam1", sheet(), Verb.MUTE)
    assertTrue(c.calls.isEmpty())
  }
}
