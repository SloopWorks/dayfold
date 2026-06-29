package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ADR 0038 §6 — the outbox sender state machine (pure). One classify() decision per
// HTTP outcome; the SyncEngine loop just applies the returned action.
class OutboxSenderTest {
  @Test fun `200 acks`() {
    assertEquals(SendOutcome.Acked, OutboxSender.classify(200, attempt = 0))
  }

  // Slice 5b (ADR 0038 §W4) — DELETE returns 204 No Content on success (and on an
  // idempotent re-delete). The sender must ack it exactly like a 200.
  @Test fun `204 acks (delete success - no content)`() {
    assertEquals(SendOutcome.Acked, OutboxSender.classify(204, attempt = 0))
  }

  @Test fun `412 re-merges (until the cap, then fails)`() {
    assertEquals(SendOutcome.ReMerge, OutboxSender.classify(412, attempt = 0))
    assertEquals(SendOutcome.ReMerge, OutboxSender.classify(412, attempt = 3, maxAttempts = 5))
    assertEquals(SendOutcome.Failed, OutboxSender.classify(412, attempt = 4, maxAttempts = 5)) // 5th → cap
  }

  @Test fun `410 and 404 drop the op (give up, no retry)`() {
    assertEquals(SendOutcome.Drop, OutboxSender.classify(410, attempt = 0))
    assertEquals(SendOutcome.Drop, OutboxSender.classify(404, attempt = 0))
  }

  @Test fun `other 4xx (malformed, 403, 422) drop — not retryable`() {
    assertEquals(SendOutcome.Drop, OutboxSender.classify(400, attempt = 0))
    assertEquals(SendOutcome.Drop, OutboxSender.classify(403, attempt = 0))
    assertEquals(SendOutcome.Drop, OutboxSender.classify(422, attempt = 0))
  }

  @Test fun `401, 5xx, and network errors back off (transient)`() {
    assertTrue(OutboxSender.classify(401, attempt = 0) is SendOutcome.Backoff)
    assertTrue(OutboxSender.classify(500, attempt = 0) is SendOutcome.Backoff)
    assertTrue(OutboxSender.classify(503, attempt = 0) is SendOutcome.Backoff)
    assertTrue(OutboxSender.classify(null, attempt = 0) is SendOutcome.Backoff) // network error
  }

  @Test fun `a transient error parks the op as failed once the attempt cap is reached`() {
    assertTrue(OutboxSender.classify(500, attempt = 3, maxAttempts = 5) is SendOutcome.Backoff)
    assertEquals(SendOutcome.Failed, OutboxSender.classify(500, attempt = 4, maxAttempts = 5))
    assertEquals(SendOutcome.Failed, OutboxSender.classify(null, attempt = 4, maxAttempts = 5))
  }

  @Test fun `backoff is exponential, monotonic, and capped at 60s`() {
    val b1 = (OutboxSender.classify(500, attempt = 0) as SendOutcome.Backoff).delayMs
    val b2 = (OutboxSender.classify(500, attempt = 1) as SendOutcome.Backoff).delayMs
    assertTrue(b2 > b1, "backoff should grow: $b1 -> $b2")
    assertEquals(1_000L, OutboxSender.backoffMs(1))
    assertEquals(2_000L, OutboxSender.backoffMs(2))
    assertEquals(60_000L, OutboxSender.backoffMs(20)) // capped
    assertTrue((1..30).map { OutboxSender.backoffMs(it) }.zipWithNext().all { (a, b) -> b >= a }) // monotonic
  }

  @Test fun `the new attempt count is carried on backoff`() {
    assertEquals(1, (OutboxSender.classify(500, attempt = 0) as SendOutcome.Backoff).attempt)
    assertEquals(3, (OutboxSender.classify(503, attempt = 2) as SendOutcome.Backoff).attempt)
  }
}
