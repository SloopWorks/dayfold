package com.sloopworks.dayfold.swip

import kotlinx.serialization.json.JsonElement
import works.sloop.swip.ConsentScope
import works.sloop.swip.ConsentState
import works.sloop.swip.FlushResult
import works.sloop.swip.SloopAnalytics
import works.sloop.swip.SwipEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The enforcement point for `analytics.events.enabled`. This is deliberately NOT wired into
 * swipMiddleware's `consentGate` — that gate only guards the breadcrumb and the exposure
 * read, while `analytics.track` runs under the middleware's `active` check alone, so a config
 * check there would silently fail to suppress any event.
 */
class AnalyticsGateTest {

  private class Event(override val schema: String) : SwipEvent {
    override val props: Map<String, JsonElement?> = emptyMap()
  }

  private class RecordingAnalytics : SloopAnalytics {
    val tracked = mutableListOf<String>()
    var identified: String? = null
    var resets = 0
    override fun track(event: SwipEvent) { tracked += event.schema }
    override fun identify(distinctId: String, traits: Map<String, JsonElement?>) { identified = distinctId }
    override fun alias(previousId: String) {}
    override fun reset() { resets++ }
    override suspend fun flush(): FlushResult = FlushResult(0, 0)
    override fun setConsent(consent: ConsentState) {}
    override fun optIn(scope: ConsentScope) {}
    override fun optOut(scope: ConsentScope) {}
  }

  @Test
  fun `forwards events while enabled`() {
    val inner = RecordingAnalytics()
    val gated = inner.gatedBy { true }

    gated.track(Event("swip:event:a:1"))

    assertEquals(listOf("swip:event:a:1"), inner.tracked)
  }

  @Test
  fun `drops events while disabled`() {
    val inner = RecordingAnalytics()
    val gated = inner.gatedBy { false }

    gated.track(Event("swip:event:a:1"))

    assertTrue(inner.tracked.isEmpty())
  }

  @Test
  fun `re-reads the predicate per event so a panel override applies without restart`() {
    val inner = RecordingAnalytics()
    var enabled = true
    val gated = inner.gatedBy { enabled }

    gated.track(Event("before"))
    enabled = false                  // the override lands
    gated.track(Event("during"))
    enabled = true                   // cleared again
    gated.track(Event("after"))

    assertEquals(listOf("before", "after"), inner.tracked)
  }

  @Test
  fun `identity and lifecycle plumbing is never gated`() {
    val inner = RecordingAnalytics()
    val gated = inner.gatedBy { false }

    gated.identify("user-1")
    gated.reset()

    // Muting these would strand identity/pipeline state rather than suppress data.
    assertEquals("user-1", inner.identified)
    assertEquals(1, inner.resets)
  }
}
