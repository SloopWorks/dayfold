package com.sloopworks.dayfold.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Fast pre-check for push --hub/--section/--block bodies (server stays the authority).
class HubTreeValidateTest {
  private fun ok(errs: List<String>) = assertTrue(errs.isEmpty(), "expected valid, got: $errs")
  private fun bad(errs: List<String>, needle: String) =
    assertTrue(errs.any { it.contains(needle) }, "expected an error mentioning \"$needle\", got: $errs")

  @Test fun `valid hub passes; bad type, status, missing title fail`() {
    ok(validateHubTree("hubs", """{"type":"party-event","title":"Maya's birthday","status":"planning"}"""))
    bad(validateHubTree("hubs", """{"type":"party-event"}"""), "title")
    bad(validateHubTree("hubs", """{"type":"birthday-bash","title":"x"}"""), "catalog key")
    bad(validateHubTree("hubs", """{"type":"medical","title":"x","status":"pending"}"""), "status")
  }

  @Test fun `section requires hubId`() {
    ok(validateHubTree("sections", """{"hubId":"h1","title":"Shopping","ord":0}"""))
    bad(validateHubTree("sections", """{"title":"Shopping"}"""), "hubId")
  }

  @Test fun `block requires sectionId and a valid type (catches typos)`() {
    ok(validateHubTree("blocks", """{"sectionId":"s1","type":"checklist","ord":0}"""))
    bad(validateHubTree("blocks", """{"sectionId":"s1","type":"checlist"}"""), "checlist")   // typo caught
    bad(validateHubTree("blocks", """{"type":"text"}"""), "sectionId")
  }

  @Test fun `malformed JSON is reported, not thrown`() {
    bad(validateHubTree("hubs", """{not json"""), "invalid hubs JSON")
  }
}
