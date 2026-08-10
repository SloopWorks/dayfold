package com.sloopworks.dayfold.swip

import com.sloopworks.dayfold.client.AppState
import com.sloopworks.dayfold.client.CalendarCheckState
import com.sloopworks.dayfold.client.CalendarState
import com.sloopworks.dayfold.client.CandidateLocation
import com.sloopworks.dayfold.client.DayfoldEventCandidate
import com.sloopworks.dayfold.client.DeviceCalendar
import com.sloopworks.dayfold.client.FamilyCreated
import com.sloopworks.dayfold.client.HubRequestKey
import com.sloopworks.dayfold.client.HubState
import com.sloopworks.dayfold.client.HubTenantGeneration
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
}
