package com.sloopworks.dayfold.client

/**
 * ADR 0064 — smart-content response actions.
 *
 * A marker interface like [RoutinePreviewAction], so privacy-bounded debug tooling can
 * recognize the family at a glance. That matters more here than elsewhere: a response
 * action's payload names a subject the household chose to suppress, and a Done note is the
 * most personal string this feature creates.
 */
sealed interface ResponseAction : Action

/** DB→store bridge — the SOLE writer of state.responses.rules (mirrors HiddenLoaded). */
data class ResponsesLoaded(val rules: List<ContentResponse>) : ResponseAction

data class OpenResponseSheet(
  val subjectRef: String,
  val subjectTitle: String,
  val reasonKind: String,
  val source: String?,
  val surface: ResponseSurface,
  /** The swipe escalation opens straight at the scope step, not at the verb list. */
  val step: ResponseStep = ResponseStep.VERBS,
) : ResponseAction

data object CloseResponseSheet : ResponseAction
data object ResponseStepScope : ResponseAction
data object ResponseStepDoneNote : ResponseAction
data object ResponseStepBack : ResponseAction

data class SetResponseMatchScope(val scope: MatchScope) : ResponseAction
data class SetResponseAudience(val audience: AudienceScope) : ResponseAction

data class ResponseReceiptShown(val receipt: ResponseReceipt) : ResponseAction
data object ResponseReceiptDismissed : ResponseAction

// ADR 0064 GAP 5 — Settings › Smart content, reached from the Account screen.
data object OpenSmartContent : ResponseAction
data object CloseSmartContent : ResponseAction
