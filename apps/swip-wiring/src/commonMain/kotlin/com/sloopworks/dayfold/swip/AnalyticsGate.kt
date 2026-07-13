package com.sloopworks.dayfold.swip

import kotlinx.serialization.json.JsonElement
import works.sloop.swip.ConsentScope
import works.sloop.swip.ConsentState
import works.sloop.swip.FlushResult
import works.sloop.swip.SloopAnalytics
import works.sloop.swip.SwipEvent

/**
 * Drops tracked events while [enabled] is false — the enforcement point for
 * `analytics.events.enabled` (see [DayfoldConfig]).
 *
 * It has to live HERE, not in `swipMiddleware`'s `consentGate`. That gate only guards the
 * breadcrumb and the exposure read; `analytics.track` runs under the middleware's `active`
 * check alone (swip-rk Middleware.kt), so a config check wired into `consentGate` would
 * silently fail to stop a single event.
 *
 * [enabled] is invoked per [track] — not captured once — so flipping the override in the
 * debug drawer takes effect on the very next action, with no restart. That per-call read is
 * also what read-tracks the key into the Config panel.
 *
 * Only [track] is gated. identify/alias/reset/flush/consent are identity + pipeline
 * plumbing, not events, and muting them would strand state rather than suppress data.
 */
fun SloopAnalytics.gatedBy(enabled: () -> Boolean): SloopAnalytics = GatedAnalytics(this, enabled)

private class GatedAnalytics(
  private val delegate: SloopAnalytics,
  private val enabled: () -> Boolean,
) : SloopAnalytics {
  override fun track(event: SwipEvent) {
    if (enabled()) delegate.track(event)
  }

  override fun identify(distinctId: String, traits: Map<String, JsonElement?>) = delegate.identify(distinctId, traits)
  override fun alias(previousId: String) = delegate.alias(previousId)
  override fun reset() = delegate.reset()
  override suspend fun flush(): FlushResult = delegate.flush()
  override fun setConsent(consent: ConsentState) = delegate.setConsent(consent)
  override fun optIn(scope: ConsentScope) = delegate.optIn(scope)
  override fun optOut(scope: ConsentScope) = delegate.optOut(scope)
}
