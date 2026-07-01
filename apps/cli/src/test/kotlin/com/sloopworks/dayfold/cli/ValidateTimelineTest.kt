package com.sloopworks.dayfold.cli

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class ValidateTimelineTest {
  private fun hub(tl: String) = """{"title":"H","type":"move","timeline":$tl}"""
  @Test fun validTimelineOk() {
    val e = validateHubTree("hubs", hub("""{"tz":"UTC","stops":[{"at":"2026-08-24","title":"Move-in"}]}"""))
    assertEquals(emptyList(), e)
  }
  @Test fun missingTzFlagged() {
    val e = validateHubTree("hubs", hub("""{"stops":[{"at":"2026-08-24","title":"X"}]}"""))
    assertTrue(e.any { it.contains("tz") })
  }
  @Test fun emptyStopsFlagged() {
    assertTrue(validateHubTree("hubs", hub("""{"tz":"UTC","stops":[]}""")).any { it.contains("stops") })
  }
  @Test fun badKindFlagged() {
    val e = validateHubTree("hubs", hub("""{"tz":"UTC","stops":[{"at":"2026-01-01","title":"X","attachments":[{"kind":"df","label":"y"}]}]}"""))
    assertTrue(e.any { it.contains("kind") })
  }
  @Test fun nullStopFlagged() {
    val e = validateHubTree("hubs", hub("""{"tz":"UTC","stops":[null]}"""))
    assertTrue(e.isNotEmpty())
  }
  @Test fun nullAttachmentFlagged() {
    val e = validateHubTree("hubs", hub("""{"tz":"UTC","stops":[{"at":"2026-01-01","title":"X","attachments":[null]}]}"""))
    assertTrue(e.isNotEmpty())
  }
}
