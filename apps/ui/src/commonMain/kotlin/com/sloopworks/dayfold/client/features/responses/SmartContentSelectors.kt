package com.sloopworks.dayfold.client

import androidx.compose.runtime.Immutable

/**
 * ADR 0064 GAP 5 — the Settings › Smart content view state.
 *
 * Names are resolved HERE, at render time, from the identity-layer roster — not stored on the
 * rule. A response row carries `created_by` (an id), exactly as a checklist item carries
 * `doneBy`: the content layer stays content-blind and the byline is a render-side join
 * (ADR 0015). A rule written by a member who has since left resolves to "a family member".
 */
@Immutable
data class SmartContentViewState(
  val model: SmartContentModel,
  val memberNames: Map<String, String>,
  val offline: Boolean,
)

fun smartContentViewState(state: AppState): SmartContentViewState = SmartContentViewState(
  model = smartContentSections(state),
  memberNames = state.familyAdmin.members
    .mapNotNull { m ->
      displayNameFor(m.uid, state.familyAdmin.members, state.session.session?.userId)?.let { m.uid to it }
    }
    .toMap(),
  // A queued response is the honest signal here — the rule list is the one surface where
  // "saved but not yet in effect" has to be legible.
  offline = state.responses.rules.any { it.pending },
)
