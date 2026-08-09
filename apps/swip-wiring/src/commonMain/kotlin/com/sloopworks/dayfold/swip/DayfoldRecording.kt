package com.sloopworks.dayfold.swip

import com.sloopworks.dayfold.client.AppState
import com.sloopworks.dayfold.client.Route
import com.sloopworks.dayfold.client.RoutinePreviewAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonPrimitive
import org.reduxkotlin.Dispatcher
import org.reduxkotlin.Reducer
import org.reduxkotlin.Store
import org.reduxkotlin.StoreCreator
import org.reduxkotlin.StoreEnhancer
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
 * embed server messages), and — ADR 0064 — anything from `responses`: a rule's
 * label/sublabel names a subject the household chose to suppress, and a Done
 * `note` is the most personal string that feature creates ("used Grandma's new
 * number"). Not even a derived rulesCount: it is not worth re-opening this fence
 * for a number nobody has asked for. Changing this list requires re-running
 * DayfoldLeakTest with new salts covering the added slice.
 */
fun dayfoldSlices(): List<SliceSpec<AppState>> = slices {
  // Until the routine observability ADR is accepted, the preview route is
  // indistinguishable from its Account entry point in debug journals.
  slice("route", String.serializer(), { s -> recordedRoute(s.navigation.route) }, { s, v -> s.copy(navigation = s.navigation.copy(route = Route.valueOf(v))) })
  slice("syncing", Boolean.serializer(), { s -> s.content.syncing }, { s, v -> s.copy(content = s.content.copy(syncing = v)) })
  slice("detailStack", ListSerializer(String.serializer()), { s -> s.navigation.detailStack }, { s, v -> s.copy(navigation = s.navigation.copy(detailStack = v)) })
  slice("cardsCount", Int.serializer()) { s -> s.content.cards.size } // derived — no apply
  slice("hubFilter", String.serializer(), { s -> s.hubs.filter }, { s, v -> s.copy(hubs = s.hubs.copy(filter = v)) })
}

// This map has NO exhaustiveness guard — a new Route silently journals its own name — so any
// route naming a privacy-shaped surface must be added here deliberately. Smart content lists
// mute labels and Done notes, so even the route name is a weak signal about what a household
// suppresses; journal it as its Account entry point, exactly as SmartBriefings already is.
private fun recordedRoute(route: Route): String =
  if (route == Route.SmartBriefings || route == Route.SmartContent) Route.Account.name else route.name

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

/**
 * Recorder enhancer with a product-owned action privacy fence. SWIP records an
 * action's class name before sanitizing state, so routine preview actions must be
 * wrapped before they reach the recorder and unwrapped before the real reducer.
 * The wrapper retains no serialized fields and its generic name discloses no
 * provider, source, route, or routine usage.
 */
fun dayfoldRecorderEnhancer(recorder: ReduxTimelineRecorder<AppState>): StoreEnhancer<AppState> = { next ->
  val recordedCreator = recorder.enhancer()(next)
  val privateCreator: StoreCreator<AppState> = { reducer, initialState, enhancer ->
    fun privacyReducer(actual: Reducer<AppState>): Reducer<AppState> = { state, action ->
      actual(state, (action as? PrivateUiAction)?.delegate ?: action)
    }

    val recordedStore = recordedCreator(privacyReducer(reducer), initialState, enhancer)
    object : Store<AppState> {
      override val store: Store<AppState> get() = this
      override val getState = recordedStore.getState
      override var dispatch: Dispatcher = { action ->
        if (action is RoutinePreviewAction) {
          recordedStore.dispatch(PrivateUiAction(action))
          action
        } else {
          recordedStore.dispatch(action)
        }
      }
      override val subscribe = recordedStore.subscribe
      override val replaceReducer: (Reducer<AppState>) -> Unit = { replacement ->
        recordedStore.replaceReducer(privacyReducer(replacement))
      }
    }
  }
  privateCreator
}

private class PrivateUiAction(val delegate: Any)
