package com.sloopworks.dayfold.cli

import com.sloopworks.dayfold.schema.BlockType
import com.sloopworks.dayfold.schema.Status
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Fast pre-check for push --hub/--section/--block bodies (server stays the authority).
class HubTreeValidateTest {
  private fun ok(errs: List<String>) = assertTrue(errs.isEmpty(), "expected valid, got: $errs")
  private fun bad(errs: List<String>, needle: String) =
    assertTrue(errs.any { it.contains(needle) }, "expected an error mentioning \"$needle\", got: $errs")

  @Test fun `valid hub passes, bad type or status or missing title fail`() {
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

  @Test fun `a payload present must carry its core field (ADR 0035 Option C)`() {
    // checklist needs a non-empty items array
    ok(validateHubTree("blocks", """{"sectionId":"s1","type":"checklist","payload":{"items":[{"text":"Submit FAFSA"}]}}"""))
    bad(validateHubTree("blocks", """{"sectionId":"s1","type":"checklist","payload":{"items":[]}}"""), "checklist")
    // contact needs a name; link needs a url
    ok(validateHubTree("blocks", """{"sectionId":"s1","type":"contact","payload":{"name":"Admissions"}}"""))
    bad(validateHubTree("blocks", """{"sectionId":"s1","type":"contact","payload":{"phone":"888"}}"""), "contact")
    bad(validateHubTree("blocks", """{"sectionId":"s1","type":"link","payload":{"label":"portal"}}"""), "link")
    // location needs a label; milestone needs a date OR label
    ok(validateHubTree("blocks", """{"sectionId":"s1","type":"location","payload":{"label":"Butler University"}}"""))
    bad(validateHubTree("blocks", """{"sectionId":"s1","type":"location","payload":{"address":"4600 Sunset Ave"}}"""), "location")
    ok(validateHubTree("blocks", """{"sectionId":"s1","type":"milestone","payload":{"date":"2026-08-01"}}"""))
    bad(validateHubTree("blocks", """{"sectionId":"s1","type":"milestone","payload":{"note":"x"}}"""), "milestone")
    // milestone fallback: a payload missing date/label is fine when body_md carries the content
    ok(validateHubTree("blocks", """{"sectionId":"s1","type":"milestone","payload":{"note":"x"},"body_md":"**E-Bill** due Aug 1"}"""))
  }

  @Test fun `validation is tolerant of BOTH schema and client field names (no side picked yet)`() {
    // document: schema `ref` AND client `docRef` both accepted; location label; budget items OR total/spent
    ok(validateHubTree("blocks", """{"sectionId":"s1","type":"document","payload":{"ref":"url://x"}}"""))
    ok(validateHubTree("blocks", """{"sectionId":"s1","type":"document","payload":{"docRef":"url://x"}}"""))
    ok(validateHubTree("blocks", """{"sectionId":"s1","type":"budget","payload":{"items":[{"label":"Tuition","amount":100}]}}"""))
    ok(validateHubTree("blocks", """{"sectionId":"s1","type":"budget","payload":{"total":1000,"spent":250}}"""))
  }

  @Test fun `a block with no payload is unaffected (body_md or placeholder, hash113)`() {
    ok(validateHubTree("blocks", """{"sectionId":"s1","type":"contact","body_md":"**Admissions** 888-940-8100"}"""))
    ok(validateHubTree("blocks", """{"sectionId":"s1","type":"checklist","ord":0}"""))   // no payload, no body — placeholder
  }

  // The block-type + hub-status accept-lists are DERIVED from the generated schema
  // enums (Content.kt). This locks that derivation: every value the schema declares
  // must validate. If the schema adds/removes a value, this passes automatically —
  // and it fails loudly if anyone re-hardcodes the lists and misses one (drift).
  @Test fun `every generated BlockType and hub Status is accepted (no schema drift)`() {
    for (t in BlockType.entries)
      ok(validateHubTree("blocks", """{"sectionId":"s1","type":"${t.value}"}"""))
    for (s in Status.entries)
      ok(validateHubTree("hubs", """{"type":"party-event","title":"x","status":"${s.value}"}"""))
  }
}
