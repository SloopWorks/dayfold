package com.sloopworks.dayfold.client

/**
 * ADR 0064 GAP 4 — what the snackbar says after a swipe-hide.
 *
 * Swipe stays hide-only (W5). When the hidden card was smart content, the snackbar's single
 * action slot carries the escalation — a standard one-action snackbar, not a two-button
 * invention. Undo is not lost: it moves to the hidden section's "Show".
 *
 * The offer is made once per subject, EVER. A second hide of the same subject gets a plain
 * snackbar, because a repeat offer is a nag — and declining costs nothing either way, since
 * the card is already hidden.
 */
data class SnackbarSpec(val message: String, val actionLabel: String?)

fun swipeSnackbarFor(
  subjectRef: String,
  isSmartContent: Boolean,
  alreadyOffered: Boolean,
): SnackbarSpec = SnackbarSpec(
  message = "Hidden for you",
  actionLabel = if (isSmartContent && !alreadyOffered) "Don't add again?" else null,
)

/**
 * ADR 0064 GAP 2/§Done — the two done flows.
 *
 * The card-face pill is an INSTANT done whose receipt offers "Add note" after the fact; the
 * sheet's Mark done row opens a note step with "Just done" always one tap away. The note is
 * optional and never demanded — it is context twice over, for the family and for the next run.
 */
data class DoneAction(val label: String, val enabled: Boolean, val primary: Boolean)

fun doneNoteActions(noteDraft: String): List<DoneAction> = listOf(
  // Never gated on typing: the fast path out of this step is always one tap.
  DoneAction("Just done", enabled = true, primary = false),
  DoneAction("Save note", enabled = noteDraft.isNotBlank(), primary = true),
)

fun instantDoneReceipt(): SnackbarSpec = SnackbarSpec("Marked done", "Add note")

/**
 * ADR 0064 GAP 3 — "Remove, and don't re-add" is one action with two effects, in this order:
 * the W4 delete first, then a subject-scoped rule so the next run cannot re-mint it.
 */
data class ResponsePlanStep(val kind: String, val subjectRef: String, val matchScope: MatchScope?)

fun deleteAndMutePlan(subjectRef: String): List<ResponsePlanStep> = listOf(
  ResponsePlanStep("delete", subjectRef, null),
  ResponsePlanStep("mute", subjectRef, MatchScope.SUBJECT),
)
