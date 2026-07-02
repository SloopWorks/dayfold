package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Pure render-decision logic for hub blocks (no Compose). Locks the live-data bug:
// a typed block (contact/document/checklist…) whose content the author put in
// body_md — with no structured payload — must render its markdown, not an empty
// "Contact"/"document" typed layout.
class HubBlockRenderLogicTest {
  private fun blk(type: String, bodyMd: String? = null, payload: BlockPayload? = null) =
    HubBlock(id = "b", type = type, bodyMd = bodyMd, payload = payload)

  @Test fun `typed block with content only in body_md falls back to markdown`() {
    assertTrue(blockFallsBackToBodyMd(blk("contact", bodyMd = "**Butler offices** — Office of Admissions")))
    assertTrue(blockFallsBackToBodyMd(blk("document", bodyMd = "📄 2026-27 Immunization Requirements")))
    assertTrue(blockFallsBackToBodyMd(blk("checklist", bodyMd = "- [ ] Submit FAFSA")))
    assertTrue(blockFallsBackToBodyMd(blk("link", bodyMd = "see the housing portal")))
  }

  @Test fun `a structured payload renders typed, not the markdown fallback`() {
    assertFalse(blockFallsBackToBodyMd(blk("contact", bodyMd = "x", payload = BlockPayload(name = "Admissions"))))
    assertFalse(blockFallsBackToBodyMd(blk("link", bodyMd = "x", payload = BlockPayload(url = "https://butler.edu"))))
    assertFalse(blockFallsBackToBodyMd(blk("document", bodyMd = "x", payload = BlockPayload(docRef = "ref://imm"))))
    assertFalse(blockFallsBackToBodyMd(blk("checklist", bodyMd = "x", payload = BlockPayload(items = listOf(ChecklistItem(text = "do it"))))))
  }

  @Test fun `markdown + empty blocks do NOT use the typed-fallback path`() {
    assertFalse(blockFallsBackToBodyMd(blk("markdown", bodyMd = "hi")))   // its own branch renders body_md
    assertFalse(blockFallsBackToBodyMd(blk("contact", bodyMd = null)))    // nothing to fall back to → keep typed placeholder
    assertFalse(blockFallsBackToBodyMd(blk("contact", bodyMd = "   ")))   // blank
  }

  @Test fun `a block authored with canonical schema names renders typed, not the markdown fallback (ADR 0035)`() {
    // document via the schema `ref` (not the client `docRef`) is recognized as a typed payload
    assertFalse(blockFallsBackToBodyMd(blk("document", bodyMd = "x", payload = BlockPayload(ref = "url://imm"))))
    // location via the schema `mapUrl`
    assertFalse(blockFallsBackToBodyMd(blk("location", bodyMd = "x", payload = BlockPayload(mapUrl = "https://maps/x"))))
  }

  @Test fun `budgetTotals uses the summary, else derives from an itemized (schema) budget`() {
    assertEquals(1000.0 to 250.0, budgetTotals(BlockPayload(total = 1000.0, spent = 250.0)))   // client summary
    val itemized = BlockPayload(items = listOf(
      ChecklistItem(label = "Tuition", amount = 800.0, paid = true),
      ChecklistItem(label = "Books", amount = 200.0, paid = false),
    ))
    assertEquals(1000.0 to 800.0, budgetTotals(itemized))   // total = Σamount; spent = Σ(amount where paid)
  }
}
