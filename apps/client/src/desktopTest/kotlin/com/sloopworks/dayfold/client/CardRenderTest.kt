package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CardRenderTest {
  @Test fun `allowlisted link is tappable`() {
    assertTrue(hasActionLinks("Party Sat — [order groceries](https://instacart.com)?"))
    assertTrue(hasActionLinks("[reply](mailto:school@x.edu) by Thu"))
  }

  @Test fun `disallowed scheme is dropped (xss-safe)`() {
    assertFalse(hasActionLinks("[x](javascript:alert(1))"))
    assertFalse(hasActionLinks("[x](file:///etc/passwd)"))
  }

  @Test fun `body text inlines the label, not the url`() {
    val out = renderCardBody("Tap [list](https://store) now")
    assertEquals("Tap list now", out.text) // label kept; url not in visible text
  }

  @Test fun `kind + source labels`() {
    assertEquals("Action", kindLabel("action"))
    assertNull(kindLabel("info"))
    assertEquals("Added by Claude", sourceLabel("claude"))
    assertEquals("From your email", sourceLabel("email"))
    assertNull(sourceLabel("user"))
    assertNull(sourceLabel(null))
  }
}
