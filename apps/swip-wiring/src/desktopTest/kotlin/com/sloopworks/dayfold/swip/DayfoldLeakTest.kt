package com.sloopworks.dayfold.swip

import com.sloopworks.dayfold.client.AppState
import com.sloopworks.dayfold.client.FamilyCreated
import com.sloopworks.dayfold.client.HubRequestKey
import com.sloopworks.dayfold.client.HubState
import com.sloopworks.dayfold.client.HubTenantGeneration
import com.sloopworks.dayfold.client.InviteRedeemed
import com.sloopworks.dayfold.client.InviteRejected
import com.sloopworks.dayfold.client.NavToDetail
import com.sloopworks.dayfold.client.OpenHub
import com.sloopworks.dayfold.client.ProfileState
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** docs/12 §6: product-owned sanitizer leak test over SALTED real state. */
class DayfoldLeakTest {
  private val salted = AppState(
    session = SessionState(session = Session(access = "eyJSALTEDJWTACCESS", refresh = "eyJSALTEDREFRESH", userId = "u_salted")),
    profile = ProfileState(displayName = "Salted Q. User"),
    hubs = HubState(filter = "salted-search someone@example.com padding-padding-padding"), // synthetic: real values are chip literals; salt proves the fence anyway
    navigation = NavigationState(detailStack = listOf("card_salt_1")),
  )

  @Test fun journal_never_contains_salted_pii() = runTest {
    val rec = ReduxTimelineRecorder(
      specs = dayfoldSlices(),
      sanitizer = dayfoldSanitizer,
      config = RecorderConfig(appVersion = "test"),
      clock = Clock { 0L },
      scope = this,
    )
    val store = createStore({ s: AppState, _: Any -> s.copy(content = s.content.copy(syncing = !s.content.syncing)) }, salted, rec.enhancer())
    rec.activate()
    repeat(3) { store.dispatch("tick"); advanceUntilIdle() }
    val text = rec.freeze()!!.journalJson.decodeToString() + rec.freeze()!!.finalStateJson.decodeToString()
    rec.deactivate()
    // secrets/PII salts must be absent
    assertFalse("eyJSALTED" in text)
    assertFalse("Salted Q. User" in text)
    assertFalse("someone@example.com" in text)  // hubFilter carried an email → sanitizer drops it
    assertFalse("u_salted" in text)
    // pseudonymous + derived slices ARE present (registry works)
    assertTrue("card_salt_1" in text)     // detailStack ids allowed (internal debug)
    assertTrue("cardsCount" in text)
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
