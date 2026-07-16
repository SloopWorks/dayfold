package com.sloopworks.dayfold.swip

import com.sloopworks.dayfold.client.AppState
import com.sloopworks.dayfold.client.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonPrimitive
import works.sloop.swip.bugreport.lane.Clock
import works.sloop.swip.rk.recorder.RecorderConfig
import works.sloop.swip.rk.recorder.ReduxTimelineRecorder
import works.sloop.swip.rk.recorder.SliceSpec
import works.sloop.swip.rk.recorder.StateSanitizer
import works.sloop.swip.rk.recorder.slices

/**
 * Dayfold's slice registry — the FIRST privacy fence (allowlist). Only low-risk
 * slices are journaled:
 *  - route: enum name (nav position)
 *  - syncing: Boolean
 *  - detailStack: card ids (pseudonymous — internal debug builds only)
 *  - cardsCount: derived Int (NOT card content; no apply — derived)
 *  - hubFilter: filter chips `all|active|planning` (Model.kt) — low-risk
 *    enum-ish string; the sanitizer's 32-char truncation is defense-in-depth
 *
 * NEVER register: session, mintedInvite, members, pendingApprovals, families,
 * devices, pendingDevice, myDisplayName, cards content, error/authError (may
 * embed server messages). Changing this list requires re-running DayfoldLeakTest
 * with new salts covering the added slice.
 */
fun dayfoldSlices(): List<SliceSpec<AppState>> = slices {
  slice("route", String.serializer(), { s -> s.route.name }, { s, v -> s.copy(route = Route.valueOf(v)) })
  slice("syncing", Boolean.serializer(), { s -> s.syncing }, { s, v -> s.copy(syncing = v) })
  slice("detailStack", ListSerializer(String.serializer()), { s -> s.detailStack }, { s, v -> s.copy(detailStack = v) })
  slice("cardsCount", Int.serializer()) { s -> s.cards.size } // derived — no apply
  slice("hubFilter", String.serializer(), { s -> s.hubs.filter }, { s, v -> s.copy(hubs = s.hubs.copy(filter = v)) })
}

/**
 * The SECOND fence (sanitize-at-write, swip docs/12 §6): drops any journaled
 * value that looks like a JWT (`eyJ` prefix) or an email (`@`) — nothing in the
 * registry should ever carry either, so a hit means a fence-1 regression; drop
 * beats leak. hubFilter is additionally truncated to 32 chars.
 */
val dayfoldSanitizer: StateSanitizer = StateSanitizer { slice, value ->
  val text = value.toString()
  when {
    "eyJ" in text || "@" in text -> null            // JWT-shaped or email-shaped → drop slice value
    slice == "hubFilter" -> JsonPrimitive(text.trim('"').take(32))
    else -> value
  }
}

/** The recorder androidApp's debug variant installs (innermost store enhancer). */
fun dayfoldRecorder(scope: CoroutineScope, appVersion: String, clock: Clock): ReduxTimelineRecorder<AppState> =
  ReduxTimelineRecorder(
    specs = dayfoldSlices(),
    sanitizer = dayfoldSanitizer,
    config = RecorderConfig(appVersion = appVersion),
    clock = clock,
    scope = scope,
  )
