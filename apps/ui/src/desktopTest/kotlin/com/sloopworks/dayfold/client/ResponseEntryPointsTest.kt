package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ADR 0064 GAP 2/3/4 — the swipe escalation, the two done flows, and the hub delete pairing.
class ResponseEntryPointsTest {

  @Test
  fun smartContentGetsTheOfferExactlyOnce() {
    val first = swipeSnackbarFor("card:c1", isSmartContent = true, alreadyOffered = false)
    assertEquals("Hidden for you", first.message)
    assertEquals("Don't add again?", first.actionLabel)

    // A repeat offer is a nag; the card is hidden either way.
    val second = swipeSnackbarFor("card:c1", isSmartContent = true, alreadyOffered = true)
    assertEquals("Hidden for you", second.message)
    assertNull(second.actionLabel)
  }

  @Test
  fun memberAuthoredContentNeverGetsTheOffer() {
    assertNull(swipeSnackbarFor("card:c1", isSmartContent = false, alreadyOffered = false).actionLabel)
    assertNull(swipeSnackbarFor("card:c1", isSmartContent = false, alreadyOffered = true).actionLabel)
  }

  // A standard single-action snackbar — no two-button invention (GAP 4).
  @Test
  fun thereIsOnlyEverOneAction() {
    val spec = swipeSnackbarFor("card:c1", isSmartContent = true, alreadyOffered = false)
    assertEquals(1, listOfNotNull(spec.actionLabel).size)
  }

  // The message never changes: the hide already happened, and the offer is an addition to it.
  @Test
  fun theMessageIsTheSameWithOrWithoutTheOffer() {
    assertEquals(
      swipeSnackbarFor("card:c1", isSmartContent = true, alreadyOffered = false).message,
      swipeSnackbarFor("card:c1", isSmartContent = false, alreadyOffered = false).message,
    )
  }

  @Test
  fun justDoneIsAlwaysOneTapAway() {
    val actions = doneNoteActions(noteDraft = "")
    assertEquals(listOf("Just done", "Save note"), actions.map { it.label })
    assertTrue(actions.first { it.label == "Just done" }.enabled)   // never gated on typing
    assertTrue(!actions.first { it.label == "Save note" }.enabled)  // nothing to save yet
  }

  @Test
  fun savingBecomesAvailableOnceThereIsANote() {
    assertTrue(doneNoteActions("Confirmed — used Grandma's new number.").first { it.label == "Save note" }.enabled)
    assertTrue(!doneNoteActions("   ").first { it.label == "Save note" }.enabled)  // whitespace is not a note
  }

  @Test
  fun theInstantPathOffersAddNoteOnTheReceipt() {
    assertEquals("Marked done", instantDoneReceipt().message)
    assertEquals("Add note", instantDoneReceipt().actionLabel)
  }

  // One action, two effects — and the delete happens FIRST, so a failure to write the rule
  // still leaves the block removed rather than leaving a rule with nothing to suppress.
  @Test
  fun theHubPairingDeletesThenMutesTheSameSubject() {
    val plan = deleteAndMutePlan("hub:h9/section:s2/block:b4")
    assertEquals(listOf("delete", "mute"), plan.map { it.kind })
    assertTrue(plan.all { it.subjectRef == "hub:h9/section:s2/block:b4" })
    assertEquals(MatchScope.SUBJECT, plan.last().matchScope)
  }
}
