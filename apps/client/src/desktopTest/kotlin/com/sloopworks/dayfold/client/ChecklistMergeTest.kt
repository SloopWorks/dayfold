package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ADR 0038 §5 — the pure checklist merge. Covers the three properties the design calls
// out: convergence (order-independent), 412 re-merge (re-apply local done onto a fresh
// base), and echo idempotence (your own already-applied stamp is a no-op).
class ChecklistMergeTest {
  private fun item(id: String, text: String, done: Boolean = false, doneBy: String? = null, doneAt: String? = null, ord: Long? = null) =
    ChecklistItem(id = id, text = text, done = done, doneBy = doneBy, doneAt = doneAt, ord = ord)

  // ── per-item done-triple LWW ────────────────────────────────────────────────
  @Test fun `a newer local toggle wins over remote`() {
    val remote = listOf(item("a", "Pack", done = false))
    val local = listOf(item("a", "Pack", done = true, doneBy = "mom", doneAt = "2026-06-29T10:00:00Z"))
    val m = ChecklistMerge.mergeItems(local, remote)
    assertEquals(true, m[0].done)
    assertEquals("mom", m[0].doneBy)
  }

  @Test fun `a stale local stamp loses to a newer remote stamp`() {
    val remote = listOf(item("a", "Pack", done = false, doneBy = "dad", doneAt = "2026-06-29T11:00:00Z"))
    val local = listOf(item("a", "Pack", done = true, doneBy = "mom", doneAt = "2026-06-29T10:00:00Z"))
    val m = ChecklistMerge.mergeItems(local, remote)
    assertEquals(false, m[0].done)         // remote (newer) wins
    assertEquals("dad", m[0].doneBy)
  }

  @Test fun `loop-authoritative fields ALWAYS take remote even when local done wins`() {
    val remote = listOf(item("a", "Pack jackets (edited)", done = false, ord = 5))
    val local = listOf(item("a", "Pack", done = true, doneBy = "mom", doneAt = "2026-06-29T10:00:00Z", ord = 0))
    val m = ChecklistMerge.mergeItems(local, remote)
    assertEquals(true, m[0].done)                       // member toggle survives
    assertEquals("Pack jackets (edited)", m[0].text)    // loop text wins
    assertEquals(5L, m[0].ord)                          // loop ord wins
  }

  @Test fun `un-check is just a newer stamp (no done-wins bias)`() {
    val remote = listOf(item("a", "Pack", done = true, doneBy = "mom", doneAt = "2026-06-29T10:00:00Z"))
    val local = listOf(item("a", "Pack", done = false, doneBy = "dad", doneAt = "2026-06-29T10:05:00Z"))
    val m = ChecklistMerge.mergeItems(local, remote)
    assertEquals(false, m[0].done)   // the later un-check wins
  }

  @Test fun `equal doneAt breaks deterministically on doneBy`() {
    val t = "2026-06-29T10:00:00Z"
    val amyFalse = listOf(item("a", "x", done = false, doneBy = "amy", doneAt = t))
    val zoeTrue = listOf(item("a", "x", done = true, doneBy = "zoe", doneAt = t))
    // Determinism: the higher actor id (zoe) wins the tie REGARDLESS of which side is
    // local vs remote — so the result is the same (done=true) both ways → convergence.
    assertEquals(true, ChecklistMerge.mergeItems(zoeTrue, amyFalse)[0].done)
    assertEquals(true, ChecklistMerge.mergeItems(amyFalse, zoeTrue)[0].done)
  }

  // ── membership = remote (loop owns the item set) ────────────────────────────
  @Test fun `remote defines membership - a local-only item is dropped, a new remote item appears`() {
    val remote = listOf(item("a", "x"), item("c", "new from loop"))
    val local = listOf(item("a", "x", done = true, doneAt = "2026-06-29T10:00:00Z"), item("b", "gone"))
    val m = ChecklistMerge.mergeItems(local, remote)
    assertEquals(listOf("a", "c"), m.map { it.id })
    assertEquals(true, m.first { it.id == "a" }.done)   // the surviving toggle still applies
  }

  @Test fun `an id-less remote item is taken as-is`() {
    val remote = listOf(ChecklistItem(id = null, text = "loose"))
    val local = listOf(item("a", "x", done = true, doneAt = "2026-06-29T10:00:00Z"))
    assertEquals(remote, ChecklistMerge.mergeItems(local, remote))
  }

  // ── echo idempotence (§5.5) ─────────────────────────────────────────────────
  @Test fun `re-applying your own already-applied stamp is a no-op (echo cannot flicker)`() {
    val applied = listOf(item("a", "x", done = true, doneBy = "mom", doneAt = "2026-06-29T10:00:00Z"))
    // the /sync echo returns exactly what we wrote; merging it against our local must be identity
    val echoed = ChecklistMerge.mergeItems(applied, applied)
    assertEquals(applied, echoed)
    // and merging twice is stable
    assertEquals(echoed, ChecklistMerge.mergeItems(echoed, applied))
  }

  // ── convergence (order independence) ────────────────────────────────────────
  @Test fun `two devices converge regardless of merge order`() {
    val base = listOf(item("a", "x", done = false), item("b", "y", done = false))
    val momEdit = listOf(item("a", "x", done = true, doneBy = "mom", doneAt = "2026-06-29T10:00:00Z"), item("b", "y"))
    val dadEdit = listOf(item("a", "x"), item("b", "y", done = true, doneBy = "dad", doneAt = "2026-06-29T10:01:00Z"))
    // device 1 sees mom then dad; device 2 sees dad then mom — both start from base
    val d1 = ChecklistMerge.mergeItems(dadEdit, ChecklistMerge.mergeItems(momEdit, base))
    val d2 = ChecklistMerge.mergeItems(momEdit, ChecklistMerge.mergeItems(dadEdit, base))
    assertEquals(d1.map { it.id to it.done }, d2.map { it.id to it.done })
    assertTrue(d1.first { it.id == "a" }.done && d1.first { it.id == "b" }.done) // both toggles survive
  }

  // ── 412 re-merge (§5.4 step 4) ──────────────────────────────────────────────
  @Test fun `412 re-merge - local pending toggle re-applies onto a fresh remote base`() {
    // we toggled item a at v1; the PUT 412'd because the loop edited the text to v2.
    val pendingLocal = listOf(item("a", "Pack", done = true, doneBy = "mom", doneAt = "2026-06-29T10:00:00Z"))
    val freshRemote = listOf(item("a", "Pack jackets", done = false, ord = 3)) // loop bumped text+ord
    val remerged = ChecklistMerge.mergeItems(pendingLocal, freshRemote)
    assertEquals(true, remerged[0].done)             // our toggle survives the re-merge
    assertEquals("Pack jackets", remerged[0].text)   // on top of the loop's fresh base
    assertEquals(3L, remerged[0].ord)
  }

  // ── block-level dispatch ────────────────────────────────────────────────────
  @Test fun `mergeBlock reconciles checklist items but takes remote for one-way blocks`() {
    val localCk = HubBlock(id = "b1", type = "checklist", payload = BlockPayload(items = listOf(item("a", "x", done = true, doneAt = "2026-06-29T10:00:00Z"))))
    val remoteCk = HubBlock(id = "b1", type = "checklist", payload = BlockPayload(items = listOf(item("a", "x", done = false))))
    assertEquals(true, ChecklistMerge.mergeBlock(localCk, remoteCk).payload!!.items!![0].done)

    val localText = HubBlock(id = "b2", type = "markdown", bodyMd = "stale local")
    val remoteText = HubBlock(id = "b2", type = "markdown", bodyMd = "fresh remote")
    assertEquals("fresh remote", ChecklistMerge.mergeBlock(localText, remoteText).bodyMd) // one-way → remote
  }
}
