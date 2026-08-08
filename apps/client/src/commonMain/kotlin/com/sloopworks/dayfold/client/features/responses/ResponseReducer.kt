package com.sloopworks.dayfold.client

// Pure (ADR 0013/0058). Every effect — the network call, the optimistic DB write, ULID
// minting, undo — lives in ResponseEngine under runtime ownership. Nothing here touches the
// DB, the clock, or the network.
fun reduceResponses(state: AppState, action: Any): AppState = when (action) {
  is ResponsesLoaded -> state.copy(responses = state.responses.copy(rules = action.rules))

  is OpenResponseSheet -> state.copy(
    responses = state.responses.copy(
      sheet = ResponseSheetState(
        subjectRef = action.subjectRef,
        subjectTitle = action.subjectTitle,
        reasonKind = action.reasonKind,
        source = action.source,
        surface = action.surface,
        step = action.step,
      ),
    ),
  )

  is CloseResponseSheet -> state.copy(responses = state.responses.copy(sheet = null))

  is ResponseStepScope -> state.withSheet { it.copy(step = ResponseStep.SCOPE) }
  is ResponseStepDoneNote -> state.withSheet { it.copy(step = ResponseStep.DONE_NOTE) }
  is ResponseStepBack -> state.withSheet { it.copy(step = ResponseStep.VERBS) }

  is SetResponseMatchScope -> state.withSheet { it.copy(pendingMatchScope = action.scope) }
  is SetResponseAudience -> state.withSheet { it.copy(pendingAudience = action.audience) }

  is ResponseReceiptShown -> state.copy(responses = state.responses.copy(lastReceipt = action.receipt))
  is ResponseReceiptDismissed -> state.copy(responses = state.responses.copy(lastReceipt = null))

  else -> state
}

/** Applies [f] only when a sheet is open — a step action with no sheet is a no-op, not a crash. */
private inline fun AppState.withSheet(f: (ResponseSheetState) -> ResponseSheetState): AppState =
  responses.sheet?.let { copy(responses = responses.copy(sheet = f(it))) } ?: this
