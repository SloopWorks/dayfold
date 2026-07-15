package com.sloopworks.dayfold.swip

import com.sloopworks.dayfold.client.*
import kotlinx.serialization.json.JsonElement
import works.sloop.swip.ConsentDecision
import works.sloop.swip.ConsentScope
import works.sloop.swip.FlushResult
import works.sloop.swip.SloopAnalytics
import works.sloop.swip.SwipEvent
import works.sloop.swip.rk.ReplayGuard
import works.sloop.swip.rk.swipMiddleware
import works.sloop.swip.schema.dayfold.AccountSignedIn
import works.sloop.swip.schema.dayfold.CardOpened
import works.sloop.swip.schema.dayfold.HubOpened
import works.sloop.swip.schema.dayfold.InviteRejected as InviteRejectedEvent
import org.reduxkotlin.concurrent.NotificationContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DayfoldMapperTableTest {
  private val hubRequest = HubRequestKey(HubTenantGeneration(1L, 1L), 1L)
  private class Rec : SloopAnalytics {
    val events = mutableListOf<SwipEvent>()
    override fun track(event: SwipEvent) { events.add(event) }
    override fun identify(distinctId: String, traits: Map<String, JsonElement?>) = error("mapper must never identify (PII linkage)")
    override fun alias(previousId: String) = error("no alias")
    override fun reset() {}
    override suspend fun flush() = FlushResult(0, 0)
    override fun setConsent(consent: Map<ConsentScope, ConsentDecision>) {}
    override fun optIn(scope: ConsentScope) {}
    override fun optOut(scope: ConsentScope) {}
  }
  private fun emit(action: Any): List<SwipEvent> {
    val rec = Rec()
    val store = createAppStore(notificationContext = NotificationContext.Inline, debug = false)
    val chain = swipMiddleware<AppState>(rec, NoOpErrors, dayfoldMappers(), null, ReplayGuard.fixed(false))(store)({ it })
    chain(action)
    return rec.events
  }

  @Test fun sign_in_maps_to_account_signed_in_no_pii() =
    assertEquals(AccountSignedIn, emit(SignInSucceeded(Session("a1", "r1"))).single())
  @Test fun signed_out_maps() =
    assertEquals(works.sloop.swip.schema.dayfold.SignedOut, emit(SignedOut).single())
  @Test fun family_created_maps_no_name() =
    assertEquals(works.sloop.swip.schema.dayfold.FamilyCreated, emit(FamilyCreated("fam1", "Smith")).single())
  @Test fun invite_redeemed_maps_no_name() =
    assertEquals(works.sloop.swip.schema.dayfold.InviteRedeemed, emit(InviteRedeemed("Smith")).single())
  @Test fun invite_rejected_maps_reason() =
    assertEquals(InviteRejectedEvent(InviteRejectedEvent.Reason.EXPIRED), emit(InviteRejected("expired")).single())
  @Test fun invite_rejected_unknown_reason_falls_back_to_error() =
    assertEquals(InviteRejectedEvent(InviteRejectedEvent.Reason.ERROR), emit(InviteRejected("weird_value")).single())
  @Test fun hub_opened_maps_no_id() =
    assertEquals(HubOpened, emit(OpenHub("hub1", hubRequest)).single())
  @Test fun card_opened_maps_no_id() =
    assertEquals(CardOpened, emit(NavToDetail("card1")).single())
  @Test fun sync_failed_maps_no_message() =
    assertEquals(works.sloop.swip.schema.dayfold.SyncFailed, emit(SyncFailed("boom @host")).single())
  @Test fun unmapped_action_emits_nothing() =
    assertTrue(emit(NavBack).isEmpty())

  @Test fun purity_double_dispatch_identical() {
    val script = listOf<Any>(SignInSucceeded(Session("a1","r1")), OpenHub("h", hubRequest), NavToDetail("c"), SyncFailed("x"), InviteRejected("locked"))
    fun run(): List<String> {
      val rec = Rec(); val store = createAppStore(notificationContext = NotificationContext.Inline, debug = false)
      val chain = swipMiddleware<AppState>(rec, NoOpErrors, dayfoldMappers(), null, ReplayGuard.fixed(false))(store)({ it })
      script.forEach { chain(it) }
      return rec.events.map { it.schema }
    }
    assertEquals(run(), run())
  }
}
