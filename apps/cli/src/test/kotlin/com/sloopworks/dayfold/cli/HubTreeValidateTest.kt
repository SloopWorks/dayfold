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

  // Regression: Camp Parsons rendered checkboxes with no text because its checklist
  // items carried `label` (a budget-row field) instead of `text` (the schema-canonical
  // item text, Content.kt Item.text). The renderer reads `item.text` → blank. Catch it
  // at author time so a `label`-only item can never be pushed as a checklist.
  @Test fun `checklist item must carry text, not label`() {
    ok(validateHubTree("blocks", """{"sectionId":"s1","type":"checklist","payload":{"items":[{"text":"Pack roster copies"}]}}"""))
    bad(validateHubTree("blocks", """{"sectionId":"s1","type":"checklist","payload":{"items":[{"label":"Pack roster copies"}]}}"""), "text")
    // one good item + one label-only item → still flagged (and names the index)
    bad(validateHubTree("blocks", """{"sectionId":"s1","type":"checklist","payload":{"items":[{"text":"ok"},{"label":"bad"}]}}"""), "1")
    // a blank/whitespace text is as bad as a missing one
    bad(validateHubTree("blocks", """{"sectionId":"s1","type":"checklist","payload":{"items":[{"text":"  "}]}}"""), "text")
  }

  // Regression: Camp Parsons also had `markdown` note blocks with an empty body_md and
  // an empty `{}` payload → blank cards. text/markdown render their body_md, so an empty
  // one is always a blank card — reject it at author time.
  @Test fun `text and markdown blocks require a non-empty body_md`() {
    ok(validateHubTree("blocks", """{"sectionId":"s1","type":"markdown","body_md":"**Check-in** opens 2pm Sunday"}"""))
    ok(validateHubTree("blocks", """{"sectionId":"s1","type":"text","body_md":"Bring closed-toe water shoes"}"""))
    bad(validateHubTree("blocks", """{"sectionId":"s1","type":"markdown","payload":{}}"""), "body_md")
    bad(validateHubTree("blocks", """{"sectionId":"s1","type":"text"}"""), "body_md")
  }

  // The block-type + hub-status accept-lists are DERIVED from the generated schema
  // enums (Content.kt). This locks that derivation: every value the schema declares
  // must validate. If the schema adds/removes a value, this passes automatically —
  // and it fails loudly if anyone re-hardcodes the lists and misses one (drift).
  @Test fun `every generated BlockType and hub Status is accepted (no schema drift)`() {
    for (t in BlockType.entries) {
      // text/markdown are body_md-driven — give them a body so this drift check exercises
      // type-acceptance (its purpose) without tripping the empty-body content rule.
      val body = if (t.value == "text" || t.value == "markdown") ""","body_md":"x"""" else ""
      ok(validateHubTree("blocks", """{"sectionId":"s1","type":"${t.value}"$body}"""))
    }
    for (s in Status.entries)
      ok(validateHubTree("hubs", """{"type":"party-event","title":"x","status":"${s.value}"}"""))
  }
}
