package com.sloopworks.dayfold.client
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class TimelineModelTest {
  private val json = Json { ignoreUnknownKeys = true }
  @Test fun `parses a hub with a timeline`() {
    val h = json.decodeFromString<Hub>("""{"id":"h1","title":"Maya",
      "timeline":{"tz":"America/New_York","stops":[
        {"at":"2026-08-24T11:00:00-04:00","title":"Move-up","major":true,
         "attachments":[{"kind":"nav","label":"Map","query":"Henderson Hall"},
                        {"kind":"open","label":"List","ref":{"hubId":"h1","blockId":"b2"}}]}]}}""")
    assertEquals("America/New_York", h.timeline?.tz)
    assertEquals(1, h.timeline?.stops?.size)
    assertEquals(true, h.timeline?.stops?.first()?.major)
    assertEquals("nav", h.timeline?.stops?.first()?.attachments?.first()?.kind)
    assertEquals("b2", h.timeline?.stops?.first()?.attachments?.get(1)?.ref?.blockId)
  }
  @Test fun `hub without timeline parses null`() {
    assertEquals(null, json.decodeFromString<Hub>("""{"id":"h1","title":"X"}""").timeline)
  }
  @Test fun `stop with no attachments field deserializes to empty list`() {
    val h = json.decodeFromString<Hub>("""{"id":"h1","title":"X","timeline":{"tz":"UTC","stops":[{"at":"2026-01-01","title":"Y"}]}}""")
    assertEquals(emptyList<Attachment>(), h.timeline!!.stops.first().attachments)
  }
}
