package com.sloopworks.dayfold.swip

import com.sloopworks.dayfold.client.AppState
import com.sloopworks.dayfold.client.CalendarCheckCompleted
import com.sloopworks.dayfold.client.CalendarCheckState
import com.sloopworks.dayfold.client.CalendarEventObservation
import com.sloopworks.dayfold.client.CalendarImportProposal
import com.sloopworks.dayfold.client.CalendarPermission
import com.sloopworks.dayfold.client.CalendarState
import com.sloopworks.dayfold.client.CandidateLocation
import com.sloopworks.dayfold.client.ConfirmMatch
import com.sloopworks.dayfold.client.DayfoldEventCandidate
import com.sloopworks.dayfold.client.DeviceCalendar
import com.sloopworks.dayfold.client.EventInstant
import com.sloopworks.dayfold.client.FamilyCreated
import com.sloopworks.dayfold.client.HubRequestKey
import com.sloopworks.dayfold.client.HubState
import com.sloopworks.dayfold.client.HubTenantGeneration
import com.sloopworks.dayfold.client.HubVisibilityChoice
import com.sloopworks.dayfold.client.ImportDestination
import com.sloopworks.dayfold.client.ImportProposalState
import com.sloopworks.dayfold.client.ImportSaved
import com.sloopworks.dayfold.client.InviteRedeemed
import com.sloopworks.dayfold.client.InviteRejected
import com.sloopworks.dayfold.client.NavToDetail
import com.sloopworks.dayfold.client.OpenSmartBriefings
import com.sloopworks.dayfold.client.OpenHub
import com.sloopworks.dayfold.client.ProfileState
import com.sloopworks.dayfold.client.ReconcileResult
import com.sloopworks.dayfold.client.RoutineExternalSource
import com.sloopworks.dayfold.client.RoutineProvider
import com.sloopworks.dayfold.client.RoutineProviderSelected
import com.sloopworks.dayfold.client.RoutineState
import com.sloopworks.dayfold.client.Session
import com.sloopworks.dayfold.client.SessionState
import com.sloopworks.dayfold.client.NavigationState
import com.sloopworks.dayfold.client.SignInSucceeded
import com.sloopworks.dayfold.client.StartCalendarCheck
import com.sloopworks.dayfold.client.StartCalendarImport
import com.sloopworks.dayfold.client.SyncFailed
import com.sloopworks.dayfold.client.createAppStore
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import org.reduxkotlin.createStore
import org.reduxkotlin.concurrent.NotificationContext
import works.sloop.swip.ConsentDecision
import works.sloop.swip.ConsentScope
import works.sloop.swip.FlushResult
import works.sloop.swip.NoOpErrors
import works.sloop.swip.SloopAnalytics
import works.sloop.swip.SwipEvent
import works.sloop.swip.bugreport.lane.Clock
import works.sloop.swip.rk.ReplayGuard
import works.sloop.swip.rk.recorder.RecorderConfig
import works.sloop.swip.rk.recorder.ReduxTimelineRecorder
import works.sloop.swip.rk.swipMiddleware
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** docs/12 §6: product-owned sanitizer leak test over SALTED real state. */
class DayfoldLeakTest {
  private val salted = AppState(
    session = SessionState(session = Session(access = "eyJSALTEDJWTACCESS", refresh = "eyJSALTEDREFRESH", userId = "u_salted")),
    profile = ProfileState(displayName = "Salted Q. User"),
    hubs = HubState(filter = "salted-search someone@example.com padding-padding-padding"), // synthetic: real values are chip literals; salt proves the fence anyway
    navigation = NavigationState(detailStack = listOf("card_salt_1")),
    routines = RoutineState.preview().copy(
      provider = RoutineProvider.CLAUDE,
      externalSources = setOf(RoutineExternalSource.GMAIL),
    ),
    // ADR 0063 §3/§6 acceptance gate — calendar content must never enter a journal. `calendar` is
    // simply absent from dayfoldSlices() (docs/12 fence 1), but this proves it concretely: a raw
    // event title/location/masked-account-looking string salted into state stays out of the text.
    calendar = CalendarState(
      check = CalendarCheckState(
        results = ReconcileResult(
          dayfoldOnly = listOf(
            DayfoldEventCandidate(
              subjectKey = "hub:salted", title = "Salted Recital Event", startAt = "2026-08-09T00:00:00Z",
              endAt = null, allDay = false, timezone = "UTC",
              location = CandidateLocation(address = "123 Salted Ave"), sourceVersion = "v1", deepLink = null,
            ),
          ),
        ),
      ),
      availableCalendars = listOf(DeviceCalendar(id = "cal-salted", displayName = "Salted Calendar", accountLabel = "s•••@example.com")),
      // WI-451 (CAL-11) — the CAL-10 import wizard's in-progress review is ALSO part of
      // state.calendar and must stay out of the journal exactly like `check`: a mid-review member
      // is actively looking at a raw title/location the household hasn't decided to keep.
      importState = ImportProposalState.ChoosingDestination(
        CalendarImportProposal(
          proposalId = "prop-salted",
          title = "Salted Surprise Party",
          start = EventInstant.Timed("2026-08-09T18:00:00Z"),
          end = null,
          timezone = "UTC",
          location = com.sloopworks.dayfold.client.StructuredLocation(label = "Salted Venue", address = "456 Salted Blvd"),
          destination = ImportDestination.NewHub(HubVisibilityChoice.RESTRICTED, listOf("u_salted_importer")),
        ),
      ),
    ),
  )

  @Test fun journal_never_contains_salted_pii() = runTest {
    val rec = ReduxTimelineRecorder(
      specs = dayfoldSlices(),
      sanitizer = dayfoldSanitizer,
      config = RecorderConfig(appVersion = "test"),
      clock = Clock { 0L },
      scope = this,
    )
    val routineRouteState = salted.copy(navigation = salted.navigation.copy(route = com.sloopworks.dayfold.client.Route.SmartBriefings))
    val store = createStore(
      { s: AppState, _: Any -> s.copy(content = s.content.copy(syncing = !s.content.syncing)) },
      routineRouteState,
      dayfoldRecorderEnhancer(rec),
    )
    rec.activate()
    repeat(3) { store.dispatch("tick"); advanceUntilIdle() }
    store.dispatch(RoutineProviderSelected(RoutineProvider.CHATGPT)); advanceUntilIdle()
    val frozen = rec.freeze()!!
    val text = frozen.journalJson.decodeToString() + frozen.finalStateJson.decodeToString()
    rec.deactivate()
    // secrets/PII salts must be absent
    assertFalse("eyJSALTED" in text)
    assertFalse("Salted Q. User" in text)
    assertFalse("someone@example.com" in text)  // hubFilter carried an email → sanitizer drops it
    assertFalse("u_salted" in text)
    assertFalse("CLAUDE" in text)               // the entire routine slice stays outside the SWIP allowlist
    assertFalse("CHATGPT" in text)
    assertFalse("GMAIL" in text)
    assertFalse("SmartBriefings" in text)       // route is mapped to its non-routine Account entry point
    assertFalse("RoutineProviderSelected" in text) // routine actions are recorded only as PrivateUiAction
    assertFalse("Salted Recital Event" in text) // ADR 0063 §3/§6 — calendar slice stays outside the SWIP allowlist
    assertFalse("123 Salted Ave" in text)
    assertFalse("Salted Calendar" in text)
    assertFalse("cal-salted" in text)
    // WI-451 — the CAL-10 import wizard's in-progress proposal (also under state.calendar) is
    // equally absent, mid-review title/location included.
    assertFalse("Salted Surprise Party" in text)
    assertFalse("Salted Venue" in text)
    assertFalse("456 Salted Blvd" in text)
    assertFalse("prop-salted" in text)
    assertFalse("u_salted_importer" in text)
    // pseudonymous + derived slices ARE present (registry works)
    assertTrue("card_salt_1" in text)     // detailStack ids allowed (internal debug)
    assertTrue("cardsCount" in text)
  }

  @Test fun routine_route_and_actions_are_redacted_without_changing_reducer_behavior() = runTest {
    val rec = ReduxTimelineRecorder(
      specs = dayfoldSlices(), sanitizer = dayfoldSanitizer,
      config = RecorderConfig(appVersion = "test"), clock = Clock { 0L }, scope = this,
    )
    val store = createStore(
      { state: AppState, action: Any ->
        when (action) {
          is OpenSmartBriefings -> state.copy(navigation = state.navigation.copy(route = com.sloopworks.dayfold.client.Route.SmartBriefings))
          is RoutineProviderSelected -> state.copy(routines = state.routines.copy(provider = action.provider))
          else -> state
        }
      },
      AppState(routines = RoutineState.preview()),
      dayfoldRecorderEnhancer(rec),
    )
    rec.activate()

    store.dispatch(OpenSmartBriefings)
    store.dispatch(RoutineProviderSelected(RoutineProvider.CLAUDE))
    advanceUntilIdle()

    assertEquals(com.sloopworks.dayfold.client.Route.SmartBriefings, store.state.navigation.route)
    assertEquals(RoutineProvider.CLAUDE, store.state.routines.provider)
    val frozen = rec.freeze()!!
    val text = frozen.journalJson.decodeToString() + frozen.finalStateJson.decodeToString()
    rec.deactivate()
    assertFalse("SmartBriefings" in text)
    assertFalse("OpenSmartBriefings" in text)
    assertFalse("RoutineProviderSelected" in text)
    assertFalse("CLAUDE" in text)
    assertTrue("PrivateUiAction" in text, "redaction wrapper should keep the action lane non-vacuous")
    assertTrue("Account" in text, "routine route should use the non-routine Account value")
  }

  @Test fun hub_filter_without_pii_is_truncated_not_dropped() = runTest {
    val rec = ReduxTimelineRecorder(
      specs = dayfoldSlices(), sanitizer = dayfoldSanitizer,
      config = RecorderConfig(appVersion = "test"), clock = Clock { 0L }, scope = this,
    )
    val longFilter = "x".repeat(100)
    val store = createStore({ s: AppState, _: Any -> s }, AppState(hubs = HubState(filter = longFilter)), rec.enhancer())
    rec.activate()
    store.dispatch("tick"); advanceUntilIdle()
    val text = rec.freeze()!!.journalJson.decodeToString()
    rec.deactivate()
    assertFalse(longFilter in text)
    assertTrue("x".repeat(32) in text)
  }

  @Test fun analytics_events_never_carry_salted_pii() {
    val SALT = "LEAKSALT8842"
    val actions = listOf<Any>(
      SignInSucceeded(Session(access = "eyJ$SALT", refresh = "eyJ$SALT", userId = "u_$SALT")),
      FamilyCreated("fam_$SALT", "The $SALT Family"),
      InviteRedeemed("The $SALT Family"),
      SyncFailed("boom $SALT someone@$SALT.com"),
      OpenHub("hub_$SALT", HubRequestKey(HubTenantGeneration(1L, 1L), 1L)),
      NavToDetail("card_$SALT"),
      InviteRejected("expired"),
    )
    val rec = object : SloopAnalytics {
      val events = mutableListOf<SwipEvent>()
      override fun track(event: SwipEvent) { events.add(event) }
      override fun identify(distinctId: String, traits: Map<String, JsonElement?>) = error("must not identify")
      override fun alias(previousId: String) {}
      override fun reset() {}
      override suspend fun flush() = FlushResult(0, 0)
      override fun setConsent(consent: Map<ConsentScope, ConsentDecision>) {}
      override fun optIn(scope: ConsentScope) {}
      override fun optOut(scope: ConsentScope) {}
    }
    val store = createAppStore(notificationContext = NotificationContext.Inline, debug = false)
    val chain = swipMiddleware<AppState>(rec, NoOpErrors, dayfoldMappers(), null, ReplayGuard.fixed(false))(store)({ it })
    actions.forEach { chain(it) }
    val dump = rec.events.joinToString(" | ") { it.schema + " " + it.props.toString() }
    assertFalse(SALT in dump, "analytics leak: $dump")
    // sanity: the mappers DID emit (guard isn't vacuous)
    assertTrue(rec.events.isNotEmpty())
  }

  @Test fun local_search_query_and_result_text_never_enter_redux_journal_or_action_analytics() = runTest {
    val salt = "SEARCHLEAK8842"
    val card = com.sloopworks.dayfold.client.Card(
      id = "safe-card-id",
      title = "$salt private appointment",
    )
    val response = com.sloopworks.dayfold.client.searchCorpus(
      com.sloopworks.dayfold.client.buildSearchCorpus(
        com.sloopworks.dayfold.client.SearchContentSnapshot(
          revision = 1,
          readiness = com.sloopworks.dayfold.client.SearchReadiness.READY,
          cards = listOf(card),
        ),
      ),
      query = salt,
      corpusGeneration = 1,
    )
    assertTrue(response.results.single().title.contains(salt), "search proof must be non-vacuous")

    val recorder = ReduxTimelineRecorder(
      specs = dayfoldSlices(), sanitizer = dayfoldSanitizer,
      config = RecorderConfig(appVersion = "test"), clock = Clock { 0L }, scope = this,
    )
    val store = createStore(
      { state: AppState, action: Any -> com.sloopworks.dayfold.client.rootReducer(state, action) },
      AppState(
        navigation = com.sloopworks.dayfold.client.NavigationState(
          route = com.sloopworks.dayfold.client.Route.Feed,
        ),
        content = com.sloopworks.dayfold.client.ContentState(cards = listOf(card)),
      ),
      dayfoldRecorderEnhancer(recorder),
    )
    recorder.activate()
    store.dispatch(com.sloopworks.dayfold.client.OpenSearch(com.sloopworks.dayfold.client.SearchOrigin.NOW))
    store.dispatch(com.sloopworks.dayfold.client.OpenDetailFromSearch("safe-card-id"))
    advanceUntilIdle()
    val frozen = recorder.freeze()!!
    val journal = frozen.journalJson.decodeToString() + frozen.finalStateJson.decodeToString()
    recorder.deactivate()
    assertFalse(salt in journal, "local search content reached the Redux/SWIP journal: $journal")

    val analytics = object : SloopAnalytics {
      val events = mutableListOf<SwipEvent>()
      override fun track(event: SwipEvent) { events += event }
      override fun identify(distinctId: String, traits: Map<String, JsonElement?>) = error("must not identify")
      override fun alias(previousId: String) {}
      override fun reset() {}
      override suspend fun flush() = FlushResult(0, 0)
      override fun setConsent(consent: Map<ConsentScope, ConsentDecision>) {}
      override fun optIn(scope: ConsentScope) {}
      override fun optOut(scope: ConsentScope) {}
    }
    val analyticsStore = createAppStore(notificationContext = NotificationContext.Inline, debug = false)
    val chain = swipMiddleware<AppState>(analytics, NoOpErrors, dayfoldMappers(), null, ReplayGuard.fixed(false))(analyticsStore)({ it })
    chain(com.sloopworks.dayfold.client.OpenSearch(com.sloopworks.dayfold.client.SearchOrigin.NOW))
    chain(com.sloopworks.dayfold.client.OpenDetailFromSearch("safe-card-id"))
    assertTrue(analytics.events.isEmpty(), "search actions reached analytics: ${analytics.events}")
  }

  /**
   * WI-451 (CAL-11, ADR 0063 §3/§6 acceptance gate 6, ADR 0057) — no calendar action is mapped by
   * [dayfoldMappers], so a full calendar-check/import pass tracks ZERO SwipEvents, salted content
   * included. This is also the transitive proof for the ADR-0057 debug inspector:
   * `SwipInspectorGlue.debugSink()`'s `RingDebugSink` is fed via the SAME `Swip.init(debugSink =
   * sink)` call that receives exactly what reaches [SloopAnalytics.track] (ADR 0057 §2 — "shared
   * between plugin registration and Swip.init") — there is no second, inspector-only data path a
   * calendar action could take. Zero tracked events therefore means zero inspector entries to mask
   * (or fail to mask); this repo has no desktop target for `works.sloop.swip:swip-debug` itself
   * (Android-debug-only per ADR 0057 §6), so this transitive proof is the strongest guard available
   * from `:swip-wiring`'s desktopTest gate.
   */
  @Test fun calendar_actions_produce_zero_analytics_events_so_the_debug_inspector_never_sees_them_either() {
    val SALT = "CALLEAK9931"
    val actions = listOf<Any>(
      StartCalendarCheck,
      CalendarCheckCompleted(
        results = ReconcileResult(
          dayfoldOnly = listOf(
            DayfoldEventCandidate(
              subjectKey = "hub:$SALT", title = "$SALT Family Reunion", startAt = "2026-08-09T00:00:00Z",
              endAt = null, allDay = false, timezone = "UTC", location = null, sourceVersion = "v1", deepLink = null,
            ),
          ),
        ),
        permission = CalendarPermission.Granted,
        checkedAt = "2026-08-09T00:00:00Z",
      ),
      ConfirmMatch("hub:$SALT", "evt_$SALT"),
      StartCalendarImport(
        CalendarImportProposal(
          proposalId = "prop_$SALT", title = "$SALT Party", start = EventInstant.Timed("2026-08-09T18:00:00Z"),
          end = null, timezone = "UTC", location = null,
        ),
      ),
      ImportSaved("only you can see it — $SALT"),
    )
    val rec = object : SloopAnalytics {
      val events = mutableListOf<SwipEvent>()
      override fun track(event: SwipEvent) { events.add(event) }
      override fun identify(distinctId: String, traits: Map<String, JsonElement?>) = error("must not identify")
      override fun alias(previousId: String) {}
      override fun reset() {}
      override suspend fun flush() = FlushResult(0, 0)
      override fun setConsent(consent: Map<ConsentScope, ConsentDecision>) {}
      override fun optIn(scope: ConsentScope) {}
      override fun optOut(scope: ConsentScope) {}
    }
    val store = createAppStore(notificationContext = NotificationContext.Inline, debug = false)
    val chain = swipMiddleware<AppState>(rec, NoOpErrors, dayfoldMappers(), null, ReplayGuard.fixed(false))(store)({ it })
    actions.forEach { chain(it) }
    assertTrue(rec.events.isEmpty(), "a calendar action reached analytics (and therefore the inspector): ${rec.events}")
  }
}
