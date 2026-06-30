package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone

/**
 * ADR 0044 Phase B · S3 — the device-glue seams' commonMain logic, exercised with FAKES (the platform
 * impls are verified on-device, S4). Covers: the honest provenance subtext, nearest-N geofence selection
 * (capped, live position never persisted), spec mapping, and the worker orchestration (post + log once).
 */
class NotifSeamsTest {
  private val zone = TimeZone.UTC
  private val noon = "2026-06-30T12:00:00Z"

  private fun item(id: String, geo: Boolean = false, source: String? = null, kind: String = ReasonKind.WHEN) =
    NowItem(id = id, origin = if (source != null) Origin.AUTHORED else Origin.DERIVED, reasonKind = kind,
      title = "T-$id", why = "Why-$id", subjectKey = "k:$id",
      target = DeepLinkTarget("h-$id", blockId = "b-$id"), geoActive = geo, authoredSource = source)

  @Test fun `subtext is the honest on-device provenance`() {
    assertEquals("Matched on your device", notificationSubtext(item("g", geo = true)))
    assertEquals("Matched on your device", notificationSubtext(item("g2", kind = ReasonKind.GEO)))
    assertEquals("Added by Claude", notificationSubtext(item("c", source = "claude", kind = ReasonKind.CLAUDE)))
    assertEquals("From your email", notificationSubtext(item("e", source = "email", kind = ReasonKind.EMAIL)))
    assertEquals("On your device", notificationSubtext(item("w")))
  }

  @Test fun `toNotificationSpec carries the deep-link target + urgency`() {
    val spec = item("g", geo = true).toNotificationSpec()
    assertEquals("k:g", spec.subjectKey)
    assertEquals(DeepLinkTarget("h-g", blockId = "b-g"), spec.target)
    assertTrue(spec.urgent)
    assertEquals("Matched on your device", spec.subtext)
  }

  @Test fun `geoRegionsFor selects the nearest-N capped, with a radius fallback`() {
    val here = DeviceLocation(0.0, 0.0)
    val near = Place(id = "near", label = "near", lat = 0.001, lng = 0.0, radiusM = 200)
    val mid = Place(id = "mid", label = "mid", lat = 0.01, lng = 0.0)            // no radius → default
    val far = Place(id = "far", label = "far", lat = 0.5, lng = 0.0, radiusM = 300)
    val regions = geoRegionsFor(listOf(far, near, mid), here, cap = 2)
    assertEquals(listOf("near", "mid"), regions.map { it.id })                   // farthest evicted
    assertEquals(200.0, regions[0].radiusM)
    assertEquals(DEFAULT_GEOFENCE_RADIUS_M, regions[1].radiusM)                  // fallback
  }

  // a fake notifier capturing the glue calls.
  private class FakeNotifier : LocalNotifier {
    var channelEnsured = 0
    val posted = mutableListOf<NotificationSpec>()
    val cancelled = mutableListOf<String>()
    var cancelledAll = 0
    override fun ensureChannel() { channelEnsured++ }
    override fun postGroup(specs: List<NotificationSpec>) { posted += specs }
    override fun cancel(subjectKey: String) { cancelled += subjectKey }
    override fun cancelAll() { cancelledAll++ }
  }

  private val card = Card(id = "c1", title = "Soccer at 4pm", bodyMd = "Rain at kickoff", notBefore = noon, provenance = Provenance("claude"))

  @Test fun `runner posts the plan once and records the log`() {
    val notifier = FakeNotifier()
    val log = mutableListOf<Pair<String, String>>()
    val runner = BackgroundNotificationRunner(notifier) { k, t -> log += k to t }
    val plan = runner.run(NotifSnapshot(cards = listOf(card), config = NotifConfig(enabled = true)), noon, null, zone)

    assertEquals(1, notifier.channelEnsured)
    assertEquals(listOf("card:c1"), notifier.posted.map { it.subjectKey })
    assertEquals(listOf("card:c1" to noon), log)
    assertEquals(listOf("card:c1"), plan.toPost.map { it.subjectKey })
  }

  @Test fun `runner posts nothing + writes no log when disabled`() {
    val notifier = FakeNotifier()
    val log = mutableListOf<Pair<String, String>>()
    val runner = BackgroundNotificationRunner(notifier) { k, t -> log += k to t }
    runner.run(NotifSnapshot(cards = listOf(card), config = NotifConfig(enabled = false)), noon, null, zone)
    assertEquals(0, notifier.channelEnsured)
    assertTrue(notifier.posted.isEmpty() && log.isEmpty())
  }

  @Test fun `cancelForegroundVisible cancels each visible subject`() {
    val notifier = FakeNotifier()
    cancelForegroundVisible(notifier, setOf("k:a", "k:b"))
    assertEquals(setOf("k:a", "k:b"), notifier.cancelled.toSet())
  }
}
