package com.sloopworks.dayfold.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// CL-3 — local typed-card validation (against the generated com.sloopworks.dayfold.schema
// types). Mirrors the CL-2 server gate.
class ValidateTest {
  private val types = listOf("file", "link", "invite", "contact", "geo", "email")

  private fun template(t: String): String =
    ValidateTest::class.java.getResourceAsStream("/templates/$t.json")!!.readBytes().decodeToString()

  @Test fun `every shipped template validates clean for its type`() {
    for (t in types) {
      val errs = validateCard(t, template(t))
      assertTrue(errs.isEmpty(), "template $t should validate clean, got: $errs")
    }
  }

  @Test fun `a valid card with no --type assertion passes`() {
    assertTrue(validateCard(null, template("invite")).isEmpty())
  }

  @Test fun `payload variant must match the type`() {
    val mismatch = """{"id":"c","kind":"action","title":"x",
      "provenance":{"source":"claude","at":"2026-06-20T10:00:00Z"},
      "type":"file","payload":{"invite":{"eventName":"nope"}}}"""
    val errs = validateCard(null, mismatch)
    assertTrue(errs.any { it.contains("does not match") }, "got: $errs")
  }

  @Test fun `type without payload (and vice versa) is rejected`() {
    val noPayload = """{"id":"c","kind":"action","title":"x",
      "provenance":{"source":"claude","at":"2026-06-20T10:00:00Z"},"type":"file"}"""
    assertTrue(validateCard(null, noPayload).any { it.contains("payload") })
    val noType = """{"id":"c","kind":"action","title":"x",
      "provenance":{"source":"claude","at":"2026-06-20T10:00:00Z"},
      "payload":{"file":{"filename":"a.pdf"}}}"""
    assertTrue(validateCard(null, noType).any { it.contains("type") })
  }

  @Test fun `--type assertion must equal the card type`() {
    val errs = validateCard("link", template("file").let { withId(it, "c1") })
    assertTrue(errs.any { it.contains("--type link") }, "got: $errs")
  }

  @Test fun `unknown payload field is rejected (strict)`() {
    val bad = """{"id":"c","kind":"action","title":"x",
      "provenance":{"source":"claude","at":"2026-06-20T10:00:00Z"},
      "type":"file","payload":{"file":{"filename":"a.pdf","bogusField":1}}}"""
    assertTrue(validateCard(null, bad).isNotEmpty())
  }

  @Test fun `legacy kind-only card (no type or payload) passes`() {
    val legacy = """{"id":"c","kind":"info","title":"plain",
      "provenance":{"source":"claude","at":"2026-06-20T10:00:00Z"}}"""
    assertEquals(emptyList(), validateCard(null, legacy))
  }

  @Test fun `withId injects the path id`() {
    val withId = withId("""{"kind":"info","title":"x"}""", "card-42")
    assertTrue(withId.contains("\"id\":\"card-42\""))
  }
}
