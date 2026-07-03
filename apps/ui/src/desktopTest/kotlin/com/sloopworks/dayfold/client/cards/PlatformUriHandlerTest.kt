package com.sloopworks.dayfold.client.cards

import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformUriHandlerTest {
  @Test fun `forwards uri to callback`() {
    var seen: String? = null
    val handler = PlatformUriHandler { seen = it }
    handler.openUri("tel:+15551234567")
    assertEquals("tel:+15551234567", seen)
  }
}
