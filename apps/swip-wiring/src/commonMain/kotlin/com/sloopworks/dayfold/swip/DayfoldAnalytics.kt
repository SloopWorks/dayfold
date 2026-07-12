package com.sloopworks.dayfold.swip

import com.sloopworks.dayfold.client.FamilyCreated
import com.sloopworks.dayfold.client.InviteRedeemed
import com.sloopworks.dayfold.client.InviteRejected
import com.sloopworks.dayfold.client.NavToDetail
import com.sloopworks.dayfold.client.OpenHub
import com.sloopworks.dayfold.client.SignInSucceeded
import com.sloopworks.dayfold.client.SignedOut
import com.sloopworks.dayfold.client.SyncFailed
import kotlinx.serialization.json.JsonElement
import works.sloop.swip.SloopErrors
import works.sloop.swip.rk.SwipActionMappers
import works.sloop.swip.rk.swipMappers
import works.sloop.swip.schema.dayfold.AccountSignedIn
import works.sloop.swip.schema.dayfold.CardOpened
import works.sloop.swip.schema.dayfold.HubOpened
import works.sloop.swip.schema.dayfold.FamilyCreated as FamilyCreatedEvent
import works.sloop.swip.schema.dayfold.InviteRedeemed as InviteRedeemedEvent
import works.sloop.swip.schema.dayfold.InviteRejected as InviteRejectedEvent
import works.sloop.swip.schema.dayfold.SignedOut as SignedOutEvent
import works.sloop.swip.schema.dayfold.SyncFailed as SyncFailedEvent

/**
 * Dayfold's analytics tracking spec (count-only, ADR 0055). The mapper lambda gets the
 * ACTION ONLY — never store state — so events carry only classified action fields.
 * NEVER project session/name/message/ids: those are dropped here on purpose.
 */
fun dayfoldMappers(): SwipActionMappers = swipMappers {
  map<SignInSucceeded> { AccountSignedIn }            // drop session (PII)
  map<SignedOut> { SignedOutEvent }
  map<FamilyCreated> { FamilyCreatedEvent }           // drop name + id
  map<InviteRedeemed> { InviteRedeemedEvent }         // drop familyName
  map<InviteRejected> { InviteRejectedEvent(inviteReason(it.reason)) }  // reason is a closed enum
  map<OpenHub> { HubOpened }                          // drop hubId (count-only)
  map<NavToDetail> { CardOpened }                     // drop cardId (count-only)
  map<SyncFailed> { SyncFailedEvent }                 // drop free-text message
}

/** Total, non-throwing string→enum (mapper lambdas must never throw). */
private fun inviteReason(s: String): InviteRejectedEvent.Reason = when (s) {
  "expired" -> InviteRejectedEvent.Reason.EXPIRED
  "locked" -> InviteRejectedEvent.Reason.LOCKED
  "already" -> InviteRejectedEvent.Reason.ALREADY
  "removed" -> InviteRejectedEvent.Reason.REMOVED
  else -> InviteRejectedEvent.Reason.ERROR
}

/** swipMiddleware requires a SloopErrors; the analytics-only build has no error runtime. */
object NoOpErrors : SloopErrors {
  override fun record(error: Throwable, attrs: Map<String, JsonElement?>, mechanism: String) {}
  override fun breadcrumb(category: String, message: String) {}
}
